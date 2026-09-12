//! Bounded-range HTTP input for sources that pace a single long request.

use anyhow::{Context as _, Result, bail};
use ffmpeg::ffi;
use ffmpeg_next as ffmpeg;
use log::{debug, warn};
use std::ffi::{CString, c_int, c_void};
use std::ptr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::time::{Duration, Instant};

/// How much of the file one request covers.
const CHUNK_BYTES: i64 = 8 * 1024 * 1024;

/// [CHUNK_BYTES], or the environment's override.
fn chunk_bytes() -> i64 {
    std::env::var("DD_LAV_CHUNK_BYTES")
        .ok()
        .and_then(|v| v.parse::<i64>().ok())
        .map(|v| v.clamp(64 * 1024, 64 * 1024 * 1024))
        .unwrap_or(CHUNK_BYTES)
}

const AVIO_BUFFER_BYTES: usize = 64 * 1024;
const SKIP_FORWARD_BYTES: i64 = 256 * 1024;
const BURST_BYTES: f64 = 16.0 * 1024.0 * 1024.0;
const PACE_MULTIPLIER: f64 = 3.0;
const PACE_FLOOR_BYTES_PER_SEC: f64 = 1_500_000.0;
const PACE_SLEEP_CAP: Duration = Duration::from_millis(250);
const REOPEN_ATTEMPTS: u32 = 3;
const FAILURES_BEFORE_GIVING_UP: u32 = 6;

static CONSECUTIVE_FAILURES: AtomicU32 = AtomicU32::new(0);

/// True for sources known to pace a single long request: googlevideo's `gir` formats. Everything
/// else opens normally — this path costs an extra request per chunk and buys nothing there.
pub fn should_chunk(url: &str) -> bool {
    CONSECUTIVE_FAILURES.load(Ordering::Relaxed) < FAILURES_BEFORE_GIVING_UP
        && url.contains("googlevideo.com/")
        && query_value(url, "gir").is_some_and(|v| v == "yes")
        && content_length(url).is_some()
}

/// Reads a query parameter out of `url`, without decoding it.
fn query_value<'a>(url: &'a str, key: &str) -> Option<&'a str> {
    url.split_once('?')?
        .1
        .split('&')
        .filter_map(|pair| pair.split_once('='))
        .find(|(k, _)| *k == key)
        .map(|(_, v)| v)
}

/// Total size from the URL's own `clen`, which every paced format carries.
fn content_length(url: &str) -> Option<i64> {
    query_value(url, "clen")?.parse().ok().filter(|&n| n > 0)
}

/// Sustained byte ceiling from the URL's `clen` / `dur` (the video's own bitrate).
fn pace_bytes_per_sec(url: &str) -> f64 {
    let bitrate = match (content_length(url), query_value(url, "dur").and_then(|d| d.parse::<f64>().ok())) {
        (Some(clen), Some(dur)) if dur > 0.0 => clen as f64 / dur,
        _ => 0.0,
    };
    (bitrate * PACE_MULTIPLIER).max(PACE_FLOOR_BYTES_PER_SEC)
}

/// Owns the custom AVIO context and the reader behind it. Must outlive the `AVFormatContext` that
/// reads through it, and is dropped after it (see the field order in the session's read state).
pub struct ChunkedIo {
    avio: *mut ffi::AVIOContext,
    reader: *mut ChunkedReader,
}

// The context is only ever touched by the single reader thread that owns the session.
unsafe impl Send for ChunkedIo {}

impl ChunkedIo {
    fn new(
        url: &str,
        net_opts: &[(&str, String)],
        interrupted: &Arc<AtomicBool>,
        chunk_bytes: i64,
    ) -> Result<Self> {
        let total = content_length(url).context("source URL carries no clen")?;
        let reader = Box::into_raw(Box::new(ChunkedReader {
            url: CString::new(url).context("URL contains a NUL byte")?,
            net_opts: net_opts
                .iter()
                .map(|(k, v)| Ok((CString::new(*k)?, CString::new(v.as_str())?)))
                .collect::<Result<Vec<_>, std::ffi::NulError>>()
                .context("request option contains a NUL byte")?,
            total,
            chunk_bytes,
            pos: 0,
            chunk_end: 0,
            inner: ptr::null_mut(),
            interrupted: Arc::clone(interrupted),
            bytes_per_sec: pace_bytes_per_sec(url),
            started: Instant::now(),
            read_bytes: 0,
            scratch: vec![0u8; 32 * 1024],
        }));
        unsafe {
            let buffer = ffi::av_malloc(AVIO_BUFFER_BYTES) as *mut u8;
            if buffer.is_null() {
                drop(Box::from_raw(reader));
                bail!("could not allocate the AVIO buffer.");
            }
            let avio = ffi::avio_alloc_context(
                buffer,
                AVIO_BUFFER_BYTES as c_int,
                0,
                reader as *mut c_void,
                Some(read_packet),
                None,
                Some(seek),
            );
            if avio.is_null() {
                ffi::av_free(buffer as *mut c_void);
                drop(Box::from_raw(reader));
                bail!("could not allocate the AVIO context.");
            }
            Ok(ChunkedIo { avio, reader })
        }
    }
}

impl Drop for ChunkedIo {
    fn drop(&mut self) {
        unsafe {
            if !self.reader.is_null() {
                (*self.reader).close_inner();
            }
            if !self.avio.is_null() {
                ffi::av_free((*self.avio).buffer as *mut c_void);
                ffi::avio_context_free(&mut self.avio);
            }
            if !self.reader.is_null() {
                drop(Box::from_raw(self.reader));
                self.reader = ptr::null_mut();
            }
        }
    }
}

/// Fetch state behind the AVIO callbacks. Only the session's reader thread touches it.
struct ChunkedReader {
    url: CString,
    net_opts: Vec<(CString, CString)>,
    total: i64,
    chunk_bytes: i64,
    pos: i64,
    chunk_end: i64,
    inner: *mut ffi::AVIOContext,
    interrupted: Arc<AtomicBool>,
    bytes_per_sec: f64,
    started: Instant,
    read_bytes: u64,
    scratch: Vec<u8>,
}

impl ChunkedReader {
    fn read(&mut self, buf: *mut u8, size: c_int) -> c_int {
        if self.interrupted.load(Ordering::Relaxed) {
            return ffi::AVERROR_EXIT;
        }
        if self.pos >= self.total {
            return ffi::AVERROR_EOF;
        }
        self.pace();
        for _ in 0..=REOPEN_ATTEMPTS {
            if self.inner.is_null() {
                if let Err(rc) = self.open_chunk() {
                    return rc;
                }
            }
            let capped = (self.chunk_end - self.pos).min(size as i64).max(0) as c_int;
            if capped == 0 {
                self.close_inner();
                continue;
            }
            let n = unsafe { ffi::avio_read(self.inner, buf, capped) };
            if n > 0 {
                self.pos += n as i64;
                self.read_bytes += n as u64;
                if self.pos >= self.chunk_end {
                    self.close_inner();
                }
                return n;
            }
            self.close_inner();
            if self.interrupted.load(Ordering::Relaxed) {
                return ffi::AVERROR_EXIT;
            }
            warn!(
                "Chunked read ended {} bytes early at offset {}; reopening.",
                self.chunk_end - self.pos,
                self.pos,
            );
        }
        ffi::AVERROR_EOF
    }

    fn seek_to(&mut self, target: i64) -> i64 {
        let target = target.clamp(0, self.total);
        if target == self.pos {
            return self.pos;
        }
        let ahead = target - self.pos;
        let within_open_request =
            !self.inner.is_null() && ahead > 0 && target < self.chunk_end && ahead <= SKIP_FORWARD_BYTES;
        if within_open_request && self.discard(ahead) {
            return self.pos;
        }
        self.close_inner();
        self.pos = target;
        self.pos
    }

    fn discard(&mut self, count: i64) -> bool {
        let mut left = count;
        while left > 0 {
            let want = left.min(self.scratch.len() as i64) as c_int;
            let n = unsafe { ffi::avio_read(self.inner, self.scratch.as_mut_ptr(), want) };
            if n <= 0 {
                return false;
            }
            self.pos += n as i64;
            self.read_bytes += n as u64;
            left -= n as i64;
        }
        true
    }

    fn open_chunk(&mut self) -> Result<(), c_int> {
        self.chunk_end = (self.pos + self.chunk_bytes).min(self.total);
        let rc = unsafe {
            let mut dict: *mut ffi::AVDictionary = ptr::null_mut();
            for (key, value) in &self.net_opts {
                ffi::av_dict_set(&mut dict, key.as_ptr(), value.as_ptr(), 0);
            }
            let offset = CString::new(self.pos.to_string()).unwrap_or_default();
            let end = CString::new(self.chunk_end.to_string()).unwrap_or_default();
            ffi::av_dict_set(&mut dict, c"offset".as_ptr(), offset.as_ptr(), 0);
            ffi::av_dict_set(&mut dict, c"end_offset".as_ptr(), end.as_ptr(), 0);
            let rc = ffi::avio_open2(
                &mut self.inner,
                self.url.as_ptr(),
                ffi::AVIO_FLAG_READ,
                ptr::null(),
                &mut dict,
            );
            ffi::av_dict_free(&mut dict);
            rc
        };
        if rc < 0 {
            self.inner = ptr::null_mut();
            let failures = CONSECUTIVE_FAILURES.fetch_add(1, Ordering::Relaxed) + 1;
            warn!(
                "Bounded request for bytes {}-{} failed: {} ({failures} in a row).",
                self.pos,
                self.chunk_end - 1,
                ffmpeg::Error::from(rc),
            );
            if failures == FAILURES_BEFORE_GIVING_UP {
                warn!(
                    "Giving up on bounded requests for the rest of this run; \
                     sources will be opened as one long request again.",
                );
            }
            return Err(rc);
        }
        CONSECUTIVE_FAILURES.store(0, Ordering::Relaxed);
        Ok(())
    }

    fn close_inner(&mut self) {
        if !self.inner.is_null() {
            unsafe { ffi::avio_closep(&mut self.inner) };
            self.inner = ptr::null_mut();
        }
    }

    fn pace(&mut self) {
        let allowed = BURST_BYTES + self.bytes_per_sec * self.started.elapsed().as_secs_f64();
        let over = self.read_bytes as f64 - allowed;
        if over <= 0.0 {
            return;
        }
        std::thread::sleep(Duration::from_secs_f64(over / self.bytes_per_sec).min(PACE_SLEEP_CAP));
    }
}

/// AVIO read callback; `opaque` is the [ChunkedReader] the context was built with.
unsafe extern "C" fn read_packet(opaque: *mut c_void, buf: *mut u8, size: c_int) -> c_int {
    unsafe { (*(opaque as *mut ChunkedReader)).read(buf, size) }
}

/// AVIO seek callback, including the size query libavformat makes while probing.
unsafe extern "C" fn seek(opaque: *mut c_void, offset: i64, whence: c_int) -> i64 {
    unsafe {
        let reader = &mut *(opaque as *mut ChunkedReader);
        if whence & ffi::AVSEEK_SIZE as c_int != 0 {
            return reader.total;
        }
        let base = match whence & !(ffi::AVSEEK_FORCE as c_int) {
            0 => 0,               // SEEK_SET
            1 => reader.pos,      // SEEK_CUR
            2 => reader.total,    // SEEK_END
            _ => return ffi::AVERROR(ffi::EINVAL) as i64,
        };
        reader.seek_to(base.saturating_add(offset))
    }
}

/// Opens `url` for demuxing through bounded requests, or `Ok(None)` when the source doesn't need
/// them. The returned [ChunkedIo] must be kept alive for as long as the input is read.
pub fn open_input(
    url: &str,
    net_opts: &[(&str, String)],
    format_opts: ffmpeg::Dictionary,
    interrupted: &Arc<AtomicBool>,
) -> Result<Option<(ffmpeg::format::context::Input, ChunkedIo)>> {
    if !should_chunk(url) {
        return Ok(None);
    }
    open_forced(url, net_opts, format_opts, interrupted, chunk_bytes()).map(Some)
}

/// [open_input] without the source-class gate, for callers that already decided (and for tests).
fn open_forced(
    url: &str,
    net_opts: &[(&str, String)],
    format_opts: ffmpeg::Dictionary,
    interrupted: &Arc<AtomicBool>,
    chunk_bytes: i64,
) -> Result<(ffmpeg::format::context::Input, ChunkedIo)> {
    let io = ChunkedIo::new(url, net_opts, interrupted, chunk_bytes)?;
    let c_url = CString::new(url).context("URL contains a NUL byte")?;
    unsafe {
        let mut ps = ffi::avformat_alloc_context();
        if ps.is_null() {
            bail!("could not allocate the input context.");
        }
        (*ps).pb = io.avio;
        (*ps).flags |= ffi::AVFMT_FLAG_CUSTOM_IO;

        // avformat_open_input frees the context it was handed when it fails; the custom-IO flag
        // keeps it from touching (or closing) our AVIO context either way.
        let mut dict = format_opts.disown();
        let rc = ffi::avformat_open_input(&mut ps, c_url.as_ptr(), ptr::null_mut(), &mut dict);
        ffi::av_dict_free(&mut dict);
        if rc < 0 {
            return Err(ffmpeg::Error::from(rc)).context("open input through bounded requests");
        }
        let rc = ffi::avformat_find_stream_info(ps, ptr::null_mut());
        if rc < 0 {
            ffi::avformat_close_input(&mut ps);
            return Err(ffmpeg::Error::from(rc)).context("find stream info");
        }
        debug!(
            "Reading in {} KiB bounded requests (paced at {:.1} Mbit/s after a burst).",
            chunk_bytes / 1024,
            (*io.reader).bytes_per_sec * 8.0 / 1e6,
        );
        Ok((ffmpeg::format::context::Input::wrap(ps), io))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Write};
    use std::net::TcpListener;
    use std::sync::Mutex;

    fn serve_ranges(body: Vec<u8>) -> (u16, Arc<Mutex<Vec<(u64, u64)>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind a local port");
        let port = listener.local_addr().unwrap().port();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let recorded = Arc::clone(&seen);
        std::thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(mut stream) = stream else { break };
                let Ok(peek) = stream.try_clone() else { continue };
                let mut head = String::new();
                let mut reader = BufReader::new(peek);
                loop {
                    let mut line = String::new();
                    match reader.read_line(&mut line) {
                        Ok(0) => break,
                        Ok(_) if line == "\r\n" => break,
                        Ok(_) => head.push_str(&line),
                        Err(_) => break,
                    }
                }
                if head.is_empty() {
                    continue;
                }
                let last = body.len() as u64 - 1;
                let (start, end) = head
                    .lines()
                    .find_map(|line| {
                        let value = line
                            .strip_prefix("Range: bytes=")
                            .or_else(|| line.strip_prefix("range: bytes="))?;
                        let (from, to) = value.trim().split_once('-')?;
                        Some((
                            from.parse::<u64>().ok()?,
                            to.trim().parse::<u64>().unwrap_or(last).min(last),
                        ))
                    })
                    .unwrap_or((0, last));
                recorded.lock().unwrap().push((start, end));
                let slice = &body[start as usize..=end as usize];
                let response = format!(
                    "HTTP/1.1 206 Partial Content\r\nAccept-Ranges: bytes\r\n\
                     Content-Range: bytes {start}-{end}/{}\r\nContent-Length: {}\r\n\
                     Connection: close\r\n\r\n",
                    body.len(),
                    slice.len(),
                );
                let _ = stream.write_all(response.as_bytes());
                let _ = stream.write_all(slice);
                let _ = stream.flush();
            }
        });
        (port, seen)
    }

    /// Drives the whole custom-IO path against a local range server: every request must be
    /// bounded (an open-ended one is exactly what this exists to avoid), the chunks must stitch
    /// back into a demuxable file, and the teardown must not trip over its own pointers.
    #[test]
    fn bounded_requests_stitch_back_into_a_demuxable_file() {
        let ffmpeg_bin = std::env::var("DD_TEST_FFMPEG").unwrap_or_else(|_| "ffmpeg".into());
        let dir = std::env::temp_dir().join("dd-lav-chunked-test");
        std::fs::create_dir_all(&dir).unwrap();
        let clip = dir.join("clip.mp4");
        let made = std::process::Command::new(&ffmpeg_bin)
            .args([
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=640x360:rate=30:duration=3",
                "-pix_fmt",
                "yuv420p",
                clip.to_str().unwrap(),
            ])
            .status();
        let Ok(made) = made else { return };
        if !made.success() {
            return;
        }
        let body = std::fs::read(&clip).unwrap();
        let total = body.len() as u64;
        let (port, seen) = serve_ranges(body);

        crate::session::init_ffmpeg().unwrap();
        let url = format!("http://127.0.0.1:{port}/clip.mp4?gir=yes&clen={total}&dur=3");
        let net_opts = vec![("rw_timeout", "5000000".to_string())];
        let mut format_opts = ffmpeg::Dictionary::new();
        format_opts.set("protocol_whitelist", "http,tcp");
        let interrupted = Arc::new(AtomicBool::new(false));

        // Small chunks so a clip this size still needs several requests
        let (mut ictx, io) = open_forced(&url, &net_opts, format_opts, &interrupted, 64 * 1024)
            .expect("open through bounded requests");

        let video = ictx
            .streams()
            .best(ffmpeg::media::Type::Video)
            .expect("a video stream");
        assert_eq!(video.parameters().medium(), ffmpeg::media::Type::Video);
        let index = video.index();

        let mut packets = 0;
        let mut bytes = 0u64;
        let mut packet = ffmpeg::Packet::empty();
        while packet.read(&mut ictx).is_ok() {
            if packet.stream() == index {
                packets += 1;
                bytes += packet.size() as u64;
            }
        }
        assert!(packets >= 80, "Expected the whole 3 s clip, got {packets} packets.");
        assert!(bytes > 0);

        // The seek path is the reason this exists: it must reposition through the callback and
        // keep delivering, both backwards (a fresh request) and forwards (a discard within one).
        for target_seconds in [0i64, 2] {
            let target = target_seconds * i64::from(ffi::AV_TIME_BASE);
            ictx.seek(target, ..target).expect("seek through bounded requests");
            let mut after = 0;
            let mut packet = ffmpeg::Packet::empty();
            while packet.read(&mut ictx).is_ok() {
                if packet.stream() == index {
                    after += 1;
                }
                if after >= 10 {
                    break;
                }
            }
            assert!(
                after >= 10,
                "Only {after} packets after seeking to {target_seconds} s.",
            );
        }

        let ranges = seen.lock().unwrap().clone();
        assert!(
            ranges.len() >= 3,
            "Expected several bounded requests, saw {ranges:?}.",
        );
        for (start, end) in &ranges {
            assert!(
                end >= start && *end < total,
                "Range {start}-{end} is outside the {total}-byte file.",
            );
            assert!(
                end - start < 64 * 1024,
                "Range {start}-{end} is not bounded to the chunk size.",
            );
        }
        drop(ictx);
        drop(io);
    }

    const PACED: &str = "https://rr3---sn-oxup5.googlevideo.com/videoplayback?itag=135&gir=yes&clen=110694680&dur=1820.353";

    #[test]
    fn only_paced_google_formats_are_chunked() {
        assert!(should_chunk(PACED));
        assert!(
            !should_chunk(&PACED.replace("gir=yes", "gir=no")),
            "Only the gir formats are paced.",
        );
        assert!(
            !should_chunk(&PACED.replace("clen=110694680", "clen=0")),
            "Without a size there is nothing to bound requests against.",
        );
        assert!(!should_chunk("https://example.com/video.mp4?gir=yes&clen=1000"));
        assert!(!should_chunk("file:/tmp/clip.mp4"));
    }

    #[test]
    fn pacing_follows_the_video_bitrate() {
        // 110694680 B / 1820.353 s = ~60.8 KiB/s
        assert_eq!(pace_bytes_per_sec(PACED), PACE_FLOOR_BYTES_PER_SEC);
        let fat = PACED.replace("clen=110694680", "clen=3000000000");
        assert!(
            (pace_bytes_per_sec(&fat) - 3_000_000_000.0 / 1820.353 * PACE_MULTIPLIER).abs() < 1.0,
            "A high-bitrate source paces off its own bitrate.",
        );
    }

    #[test]
    fn content_length_and_query_parsing() {
        assert_eq!(content_length(PACED), Some(110_694_680));
        assert_eq!(query_value(PACED, "itag"), Some("135"));
        assert_eq!(query_value(PACED, "missing"), None);
        assert_eq!(content_length("https://x/y"), None);
    }
}
