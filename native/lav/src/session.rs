//! In-process decode sessions: avformat network input, avcodec decode (VideoToolbox,
//! D3D11VA / DXVA2, VAAPI, or CUDA when requested and available; software otherwise),
//! swscale aspect-fit, I420 output.

use anyhow::{Context as AnyhowContext, Result, anyhow, bail};
use log::{LevelFilter, debug, error, info, warn};
use std::collections::{HashMap, VecDeque};
use std::ffi::{c_int, c_void};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, Once};
use std::{mem, ptr};

use ffmpeg::ffi;
use ffmpeg::format::Pixel;
use ffmpeg::format::context::Input;
use ffmpeg::media::Type;
use ffmpeg::util::frame::video::Video as VideoFrame;
use ffmpeg::util::log::Level;
use ffmpeg::{Dictionary, codec};
use ffmpeg_next as ffmpeg;

use crate::cache::{CachedPacket, CodecParams, PacketRing, packets_from_position};
use crate::scale::BandedScaler;
use crate::surface::{ERR_UNSUPPORTED, LavSurfaceDesc, LavSurfaceFrame, LavSurfaceTable};

/// Read result codes shared with the JVM bridge (mirror the main library).
pub const READ_OK: i32 = 0;
pub const READ_EOF: i32 = 1;
pub const READ_INTERRUPTED: i32 = 2;
pub const READ_PREVIEW: i32 = 3;
pub const ERR_BAD_HANDLE: i32 = -1;
pub const ERR_BAD_ARGS: i32 = -2;
pub const ERR_IO: i32 = -3;
pub const NO_PTS_NANOS: i64 = i64::MIN;

const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
                          (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

/// Some CDNs 403 a correct URL unless `Referer` matches their own site (seen on Bilibili's
/// `bilivideo.com`); mirrors the host mapping in the client's `Thumbnails.refererFor`.
fn referer_for(url: &str) -> Option<&'static str> {
    let host = url
        .split("://")
        .nth(1)
        .and_then(|rest| rest.split('/').next())
        .and_then(|authority| authority.rsplit('@').next())
        .map(|hostport| hostport.split(':').next().unwrap_or("").to_ascii_lowercase())
        .unwrap_or_default();
    let covers = |domain: &str| host == domain || host.ends_with(&format!(".{domain}"));
    if covers("kick.com") {
        Some("https://kick.com/")
    } else if covers("vimeo.com") || covers("vimeocdn.com") {
        Some("https://vimeo.com/")
    } else if covers("bilibili.com") || covers("hdslb.com") || covers("bilivideo.com") {
        Some("https://www.bilibili.com/")
    } else if covers("twitch.tv") || covers("ttvnw.net") || covers("jtvnw.net") || covers("live-video.net") {
        Some("https://www.twitch.tv/")
    } else if covers("youtube.com")
        || covers("youtu.be")
        || covers("googlevideo.com")
        || covers("ytimg.com")
        || covers("googleusercontent.com")
    {
        Some("https://www.youtube.com/")
    } else {
        None
    }
}

const SEEK_PREROLL_TOLERANCE_NANOS: i64 = 50_000_000;
const PREROLL_FAST_CUTOFF_NANOS: i64 = 1_000_000_000;
const SLOW_SEEK_WARN_MS: u128 = 1_000;
const SLOW_PREROLL_WARN_MS: u128 = 2_000;
const SLOW_READ_WARN_MS: u128 = 1_000;
const STATS_WINDOW_SECS: u64 = 5;
const WIRE_READ_NANOS: u128 = 1_000_000;
const MAX_DECODED_PIXELS: i64 = 16_384 * 16_384;
const MAX_INPUT_STREAMS: &str = "64";

/// Limited-range black for the padding borders.
const BLACK_Y: u8 = 16;
const BLACK_C: u8 = 128;

static FFMPEG_LOG_INIT: Once = Once::new();

/// libav I / O interrupt callback: returns non-zero to abort a blocked read. The opaque pointer is the
/// session's `interrupted` flag (an `AtomicBool` kept alive for the format context's lifetime).
unsafe extern "C" fn interrupt_cb(opaque: *mut c_void) -> i32 {
    if opaque.is_null() {
        return 0;
    }
    let flag = unsafe {
        &*(opaque as *const AtomicBool)
    };
    if flag.load(Ordering::Relaxed) { 1 } else { 0 }
}

pub(crate) fn init_ffmpeg() -> Result<()> {
    ffmpeg::init().context("initialize libav")?;
    FFMPEG_LOG_INIT.call_once(|| {
        let level = match log::max_level() {
            LevelFilter::Trace => Level::Debug,
            _ => Level::Warning,
        };
        ffmpeg::util::log::set_level(level);
    });
    Ok(())
}

/// False when `DD_LAV_CHUNKED=0` turns bounded requests off.
fn chunked_enabled() -> bool {
    !matches!(
        std::env::var("DD_LAV_CHUNKED").as_deref(),
        Ok("0") | Ok("false") | Ok("off")
    )
}

/// Query parameters that identify a googlevideo format class — which decides whether the source
/// paces a single long GET at the video's own bitrate. Never includes the signature or any token.
fn url_class_for_log(url: &str) -> String {
    const KEYS: [&str; 6] = ["itag", "mime", "gir", "ratebypass", "clen", "dur"];
    let Some(query) = url.split_once('?').map(|(_, q)| q) else {
        return "no query".to_string();
    };
    let shown: Vec<String> = query
        .split('&')
        .filter_map(|pair| pair.split_once('='))
        .filter(|(k, _)| KEYS.contains(k))
        .map(|(k, v)| format!("{k}={v}"))
        .collect();
    if shown.is_empty() {
        "no format params".to_string()
    } else {
        shown.join(" ")
    }
}

/// Strips the query string (stream URLs carry expiring tokens) and caps the length, keeping log
/// lines readable and free of secrets.
fn url_for_log(url: &str) -> &str {
    let base = url.split('?').next().unwrap_or(url);
    match base.char_indices().nth(120) {
        Some((i, _)) => &base[..i],
        None => base,
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum HwAccelRequest {
    None,
    Auto,
    VideoToolbox,
    D3d11va,
    Vaapi,
    Cuda,
}

impl HwAccelRequest {
    fn from_code(code: u32) -> HwAccelRequest {
        match code {
            1 => HwAccelRequest::Auto,
            2 => HwAccelRequest::VideoToolbox,
            3 => HwAccelRequest::D3d11va,
            4 => HwAccelRequest::Vaapi,
            5 => HwAccelRequest::Cuda,
            _ => HwAccelRequest::None,
        }
    }

    fn candidates(self) -> &'static [HwBackend] {
        match self {
            HwAccelRequest::None => &[],
            HwAccelRequest::VideoToolbox => &[HW_VIDEOTOOLBOX],
            HwAccelRequest::D3d11va => &[HW_D3D11VA, HW_DXVA2],
            HwAccelRequest::Vaapi => &[HW_VAAPI],
            HwAccelRequest::Cuda => &[HW_CUDA],
            HwAccelRequest::Auto => auto_hw_candidates(),
        }
    }
}

#[derive(Clone, Copy)]
struct HwBackend {
    name: &'static str,
    device_type: ffi::AVHWDeviceType,
    pix_fmts: &'static [ffi::AVPixelFormat],
}

const HW_VIDEOTOOLBOX: HwBackend = HwBackend {
    name: "VideoToolbox",
    device_type: ffi::AVHWDeviceType::AV_HWDEVICE_TYPE_VIDEOTOOLBOX,
    pix_fmts: &[ffi::AVPixelFormat::AV_PIX_FMT_VIDEOTOOLBOX],
};

const HW_D3D11VA: HwBackend = HwBackend {
    name: "D3D11VA",
    device_type: ffi::AVHWDeviceType::AV_HWDEVICE_TYPE_D3D11VA,
    pix_fmts: &[
        ffi::AVPixelFormat::AV_PIX_FMT_D3D11,
        ffi::AVPixelFormat::AV_PIX_FMT_D3D11VA_VLD,
    ],
};

const HW_DXVA2: HwBackend = HwBackend {
    name: "DXVA2",
    device_type: ffi::AVHWDeviceType::AV_HWDEVICE_TYPE_DXVA2,
    pix_fmts: &[ffi::AVPixelFormat::AV_PIX_FMT_DXVA2_VLD],
};

const HW_VAAPI: HwBackend = HwBackend {
    name: "VAAPI",
    device_type: ffi::AVHWDeviceType::AV_HWDEVICE_TYPE_VAAPI,
    pix_fmts: &[ffi::AVPixelFormat::AV_PIX_FMT_VAAPI],
};

const HW_CUDA: HwBackend = HwBackend {
    name: "CUDA",
    device_type: ffi::AVHWDeviceType::AV_HWDEVICE_TYPE_CUDA,
    pix_fmts: &[ffi::AVPixelFormat::AV_PIX_FMT_CUDA],
};

#[cfg(target_os = "macos")]
fn auto_hw_candidates() -> &'static [HwBackend] {
    &[HW_VIDEOTOOLBOX]
}
#[cfg(target_os = "windows")]
fn auto_hw_candidates() -> &'static [HwBackend] {
    &[HW_D3D11VA, HW_DXVA2, HW_CUDA]
}
#[cfg(all(unix, not(target_os = "macos")))]
fn auto_hw_candidates() -> &'static [HwBackend] {
    &[HW_VAAPI, HW_CUDA]
}

#[cfg(not(any(
    target_os = "macos",
    target_os = "windows",
    all(unix, not(target_os = "macos"))
)))]
fn auto_hw_candidates() -> &'static [HwBackend] {
    &[]
}

struct HwSelection {
    pix_fmt: ffi::AVPixelFormat,
    device_ctx: *mut ffi::AVBufferRef,
}

impl Drop for HwSelection {
    fn drop(&mut self) {
        unsafe {
            if !self.device_ctx.is_null() {
                ffi::av_buffer_unref(&mut self.device_ctx);
            }
        }
    }
}

/// Packet source for a decode state: live demuxer or replay snapshot.
enum PacketSource {
    Live {
        ictx: Input,
        stream_index: usize,
        stream_start_time: Option<i64>,
    },
    Replay {
        packets: Vec<CachedPacket>,
        next_packet: usize,
        resume_nanos: i64,
    },
}

/// Mutable decode state; locked only by the (single) reader thread.
struct ReadState {
    decoder: codec::decoder::Video,
    _hw_selection: Option<Box<HwSelection>>,
    time_base: ffmpeg::Rational,
    scaler: Option<BandedScaler>,
    sw_frame: VideoFrame,
    draining: bool,
    seek_target_nanos: Option<i64>,
    seek_debug: Option<SeekDebug>,
    preroll_fast: bool,
    pending_replay: VecDeque<CachedPacket>,
    stats: ReadStats,
    source: PacketSource,
    #[allow(dead_code, reason = "owned for its lifetime, read through the AVIO pointer")]
    chunked: Option<crate::chunked::ChunkedIo>,
}

/// Per-stage cost of one delivered frame, averaged over [STATS_WINDOW_SECS]. A pipe that cannot
/// keep the source's cadence shows up here as a stage whose own budget exceeds the frame interval,
/// which is what decides between hardware decode, a cheaper conversion and a lower stream quality.
#[derive(Default)]
struct ReadStats {
    frames: u64,
    reads: u64,
    slow_reads: u64,
    wait_nanos: u128,
    demux_cpu_nanos: u128,
    send_nanos: u128,
    decode_nanos: u128,
    transfer_nanos: u128,
    scale_nanos: u128,
    write_nanos: u128,
    demux_bytes: u64,
    scale_geometry: Option<(u32, u32, u32, u32, usize)>,
    window: Option<std::time::Instant>,
}

impl ReadStats {
    fn record_read(&mut self, nanos: u128, bytes: u64) {
        self.reads += 1;
        self.demux_bytes += bytes;
        if nanos >= WIRE_READ_NANOS {
            self.slow_reads += 1;
            self.wait_nanos += nanos;
        } else {
            self.demux_cpu_nanos += nanos;
        }
    }

    /// Accounts one delivered frame, returning a report once the window is up.
    fn record_frame(&mut self, write_nanos: u128) -> Option<String> {
        self.write_nanos += write_nanos;
        self.frames += 1;
        let elapsed = self
            .window
            .get_or_insert_with(std::time::Instant::now)
            .elapsed();
        if elapsed.as_secs() < STATS_WINDOW_SECS || self.frames == 0 {
            return None;
        }
        let per_frame = |total: u128| total as f64 / self.frames as f64 / 1_000_000.0;
        let wait = per_frame(self.wait_nanos);
        let demux_cpu = per_frame(self.demux_cpu_nanos);
        let send = per_frame(self.send_nanos);
        let decode = per_frame(self.decode_nanos);
        let transfer = per_frame(self.transfer_nanos);
        let scale = per_frame(self.scale_nanos);
        let write = per_frame(self.write_nanos);
        let waited_secs = self.wait_nanos as f64 / 1e9;
        let mbits = if waited_secs > 0.0 {
            self.demux_bytes as f64 * 8.0 / waited_secs / 1e6
        } else {
            0.0
        };
        let report = format!(
            "{} frames in {:.1} s ({:.1} fps delivered); per frame: network wait {wait:.2} ms, \
             demux CPU {demux_cpu:.2} ms, send {send:.2} ms, decoder {decode:.2} ms, \
             hw transfer {transfer:.2} ms, swscale{} {scale:.2} ms (convert total {write:.2} ms) \
             — ceiling {:.1} fps; {:.1} KiB/frame over {} reads, {} of them on the wire \
             at {:.1} Mbit/s ({:.1} MiB total).",
            self.frames,
            elapsed.as_secs_f64(),
            self.frames as f64 / elapsed.as_secs_f64(),
            match self.scale_geometry {
                Some((sw, sh, dw, dh, bands)) => format!(" {sw} x {sh}->{dw} x {dh} on {bands} band(s)"),
                None => String::new(),
            },
            1_000.0 / (wait + demux_cpu + send + decode + write).max(0.001),
            self.demux_bytes as f64 / self.frames as f64 / 1024.0,
            self.reads,
            self.slow_reads,
            mbits,
            self.demux_bytes as f64 / 1_048_576.0,
        );
        *self = ReadStats::default();
        Some(report)
    }
}

/// Paints the letterbox bars around a fitted picture in a target-sized I420 frame. Only the bars:
/// the scaler overwrites the picture area on the very next call, and on a 1080p frame clearing the
/// whole buffer costs several times what the bars themselves do.
fn fill_bars(dst: &mut [u8], tw: usize, th: usize, x0: usize, y0: usize, fw: usize, fh: usize) {
    let cw = (tw + 1) / 2;
    let ch = (th + 1) / 2;
    let c_size = cw * ch;
    let (luma, chroma) = dst.split_at_mut(tw * th);
    fill_plane_bars(luma, tw, x0, y0, fw, fh, BLACK_Y);
    let (u, v) = chroma[..2 * c_size].split_at_mut(c_size);
    for plane in [u, v] {
        fill_plane_bars(plane, cw, x0 / 2, y0 / 2, fw / 2, fh / 2, BLACK_C);
    }
}

/// Fills everything outside the `(x0, y0, w, h)` rectangle of one plane with `value`.
fn fill_plane_bars(plane: &mut [u8], stride: usize, x0: usize, y0: usize, w: usize, h: usize, value: u8) {
    let height = plane.len() / stride;
    let bottom = (y0 + h).min(height);
    plane[..y0 * stride].fill(value);
    plane[bottom * stride..height * stride].fill(value);
    if x0 == 0 && w >= stride {
        return;
    }
    for row in y0..bottom {
        let line = &mut plane[row * stride..(row + 1) * stride];
        line[..x0].fill(value);
        line[x0 + w..].fill(value);
    }
}

/// Applies (or clears) the aggressive pre-roll decode mode: skip non-reference frames and the loop
/// filter while every decoded frame is thrown away anyway. Roughly halves the decode-through time
/// from the landing keyframe to the seek target — the dominant share of in-place seek latency.
fn set_preroll_discard(decoder: &mut codec::decoder::Video, fast: bool) {
    unsafe {
        let p = decoder.as_mut_ptr();
        (*p).skip_frame = if fast {
            ffi::AVDiscard::AVDISCARD_NONREF
        } else {
            ffi::AVDiscard::AVDISCARD_DEFAULT
        };
        (*p).skip_loop_filter = if fast {
            ffi::AVDiscard::AVDISCARD_ALL
        } else {
            ffi::AVDiscard::AVDISCARD_DEFAULT
        };
    }
}

/// Where the demuxer landed after a seek and how much pre-roll it cost to reach the target —
/// the difference between a fast keyframe-adjacent seek and a silent multi-second decode-through.
struct SeekDebug {
    started: std::time::Instant,
    landed_pts_nanos: Option<i64>,
    dropped: u64,
    bytes: u64,
}

impl SeekDebug {
    fn begin() -> SeekDebug {
        SeekDebug {
            started: std::time::Instant::now(),
            landed_pts_nanos: None,
            dropped: 0,
            bytes: 0,
        }
    }
}

// The libav state holds raw pointers that are not Send by default. Access is serialized by
// the mutex (single reader contract), and sessions are only dropped after the reader thread
// has been joined, so moving the state between threads is safe.
unsafe impl Send for ReadState {}

impl ReadState {
    fn stream_start_time(&self) -> Option<i64> {
        match &self.source {
            PacketSource::Live {
                stream_start_time, ..
            } => *stream_start_time,
            PacketSource::Replay { .. } => None,
        }
    }

    fn should_drop_replay_preroll(&self, pts_nanos: i64) -> bool {
        match &self.source {
            PacketSource::Replay { resume_nanos, .. } => {
                pts_nanos != NO_PTS_NANOS && pts_nanos < *resume_nanos
            }
            PacketSource::Live { .. } => false,
        }
    }
}

enum SurfaceReadError {
    Io(String),
    Unsupported(String),
}

impl SurfaceReadError {
    fn message(&self) -> &str {
        match self {
            SurfaceReadError::Io(e) | SurfaceReadError::Unsupported(e) => e,
        }
    }
}

pub struct LavSession {
    id: AtomicI64,
    w: usize,
    h: usize,
    read: Mutex<ReadState>,
    ring: Mutex<Option<PacketRing>>,
    interrupted: Arc<AtomicBool>,
    error: Mutex<String>,
    codec_params: CodecParams,
}

/// Global handle table, mirroring the main library's `Sessions`.
pub struct LavSessions {
    map: Mutex<HashMap<i64, Arc<LavSession>>>,
    next: AtomicI64,
    surfaces: LavSurfaceTable,
}

impl LavSessions {
    pub fn new() -> LavSessions {
        LavSessions {
            map: Mutex::new(HashMap::new()),
            next: AtomicI64::new(1),
            surfaces: LavSurfaceTable::new(),
        }
    }

    fn get(&self, handle: i64) -> Option<Arc<LavSession>> {
        self.map.lock().ok()?.get(&handle).cloned()
    }

    pub fn open(&self, url: &str, w: usize, h: usize, start_micros: i64, hw_accel: u32) -> i64 {
        match LavSession::open(url, w, h, start_micros, hw_accel) {
            Ok(session) => {
                let handle = self.insert(session);
                info!(
                    "Opened LAV session #{handle}: {} ({w}x{h}, start {} ms) [{}].",
                    url_for_log(url),
                    start_micros / 1_000,
                    url_class_for_log(url),
                );
                handle
            }
            Err(e) => {
                error!("Failed to open LAV session for {}: {e:#}.", url_for_log(url));
                0
            }
        }
    }

    pub fn open_replay(&self, blob: &[u8], w: usize, h: usize, resume_nanos: i64) -> i64 {
        match LavSession::open_replay(blob, w, h, resume_nanos) {
            Ok(session) => {
                let handle = self.insert(session);
                info!(
                    "Opened LAV replay session #{handle} from a {} byte snapshot (resume at {} ms).",
                    blob.len(),
                    resume_nanos / 1_000_000,
                );
                handle
            }
            Err(e) => {
                error!(
                    "Failed to open LAV replay session from a {} byte snapshot: {e:#}.",
                    blob.len()
                );
                0
            }
        }
    }

    fn insert(&self, session: LavSession) -> i64 {
        let handle = self.next.fetch_add(1, Ordering::Relaxed);
        session.id.store(handle, Ordering::Relaxed);
        if let Ok(mut map) = self.map.lock() {
            map.insert(handle, Arc::new(session));
            handle
        } else {
            0
        }
    }

    pub fn read_frame(&self, handle: i64, dst: &mut [u8]) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        session.read_frame(dst)
    }

    pub fn read_frame_with_pts(&self, handle: i64, dst: &mut [u8], pts_nanos: &mut i64) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        session.read_frame_with_pts(dst, pts_nanos)
    }

    pub fn seek(&self, handle: i64, target_micros: i64) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        let started = std::time::Instant::now();
        match session.seek(target_micros.max(0)) {
            Ok(()) => {
                let elapsed_ms = started.elapsed().as_millis();
                // DD_NATIVE_LOG=debug
                if elapsed_ms >= SLOW_SEEK_WARN_MS {
                    warn!(
                        "LAV session #{handle} sought to {} ms (slow: {elapsed_ms} ms; see libav \
                         warnings above for cause, likely CDN reconnect/timeout).",
                        target_micros / 1_000
                    );
                } else {
                    debug!(
                        "LAV session #{handle} sought to {} ms ({elapsed_ms} ms).",
                        target_micros / 1_000
                    );
                }
                READ_OK
            }
            Err(e) => {
                warn!(
                    "LAV session #{handle} seek to {} ms failed after {} ms: {e:#}.",
                    target_micros / 1_000,
                    started.elapsed().as_millis(),
                );
                if let Ok(mut err) = session.error.lock() {
                    *err = format!("{e:#}.");
                }
                ERR_IO
            }
        }
    }

    pub fn read_surface(&self, handle: i64, desc: &mut LavSurfaceDesc) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        match session.read_surface() {
            Ok(Some(surface)) => self.surfaces.insert(surface, desc),
            Ok(None) if session.interrupted.load(Ordering::Relaxed) => READ_INTERRUPTED,
            Ok(None) => READ_EOF,
            Err(e) => {
                warn!("LAV session #{handle} surface read failed: {}.", e.message());
                if let Ok(mut err) = session.error.lock() {
                    *err = e.message().to_string();
                }
                match e {
                    SurfaceReadError::Io(_) => ERR_IO,
                    SurfaceReadError::Unsupported(_) => ERR_UNSUPPORTED,
                }
            }
        }
    }

    pub fn bind_surface_plane_gl(&self, surface_handle: i64, plane: u32, texture_id: u32) -> i32 {
        self.surfaces
            .bind_plane_gl(surface_handle, plane, texture_id)
    }

    pub fn release_surface(&self, surface_handle: i64) {
        self.surfaces.release(surface_handle);
    }

    pub fn error(&self, handle: i64, dst: &mut [u8]) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        let Ok(err) = session.error.lock() else {
            return ERR_IO;
        };
        let bytes = err.as_bytes();
        let n = bytes.len().min(dst.len());
        dst[..n].copy_from_slice(&bytes[..n]);
        n as i32
    }

    pub fn enable_cache(&self, handle: i64, window_nanos: i64, max_bytes: usize) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        debug!(
            "LAV session #{handle}: packet cache enabled ({} ms window, {} KiB cap).",
            window_nanos / 1_000_000,
            max_bytes / 1024,
        );
        session.enable_cache(window_nanos, max_bytes);
        READ_OK
    }

    pub fn snapshot(&self, handle: i64, dst: &mut [u8]) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        let blob = session.snapshot();
        if blob.len() <= dst.len() {
            dst[..blob.len()].copy_from_slice(&blob);
        }
        blob.len().min(i32::MAX as usize) as i32
    }

    pub fn snapshot_at(
        &self,
        handle: i64,
        position_nanos: i64,
        dst: &mut [u8],
        top_up: bool,
    ) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        let blob = session.snapshot_at(position_nanos, top_up);
        if blob.len() <= dst.len() {
            dst[..blob.len()].copy_from_slice(&blob);
        }
        blob.len().min(i32::MAX as usize) as i32
    }

    pub fn kill(&self, handle: i64) {
        if let Some(session) = self.get(handle) {
            debug!("Interrupting LAV session #{handle}");
            session.interrupted.store(true, Ordering::Relaxed);
        }
    }

    pub fn close(&self, handle: i64) {
        if let Ok(mut map) = self.map.lock()
            && map.remove(&handle).is_some()
        {
            debug!("Closed LAV session #{handle}.");
        }
    }
}

impl LavSession {
    fn open(url: &str, w: usize, h: usize, start_micros: i64, hw_accel: u32) -> Result<LavSession> {
        init_ffmpeg()?;

        let mut net_opts: Vec<(&str, String)> = vec![
            ("user_agent", USER_AGENT.to_string()),
            ("reconnect", "1".into()),
            ("reconnect_streamed", "1".into()),
            ("reconnect_delay_max", "10".into()),
            ("reconnect_on_network_error", "1".into()),
            ("reconnect_on_http_error", "5xx".into()),
            ("rw_timeout", "15000000".into()),
            ("recv_buffer_size", "4194304".into()),
        ];

        if let Some(referer) = referer_for(url) {
            net_opts.push(("headers", format!("Referer: {referer}\r\n")));
        }

        let mut opts = Dictionary::new();
        for (key, value) in &net_opts {
            opts.set(key, value);
        }

        #[cfg(not(test))]
        opts.set("protocol_whitelist", "https,tls,tcp,crypto,data,http");
        #[cfg(test)]
        opts.set("protocol_whitelist", "https,tls,tcp,crypto,data,http,file");
        opts.set("flags", "low_delay");
        opts.set("probesize", "524288");
        opts.set("analyzeduration", "500000");
        opts.set("fpsprobesize", "10");
        opts.set("max_streams", MAX_INPUT_STREAMS);

        // A seek on a paced source has to fetch everything from the landing keyframe to the target
        // before it can show a frame, and a single long request hands that over at playback speed.
        // Bounded requests lift that; a source that doesn't pace, or a failure setting it up, just
        // opens directly.
        let interrupted = Arc::new(AtomicBool::new(false));
        let chunked = if chunked_enabled() {
            crate::chunked::open_input(url, &net_opts, opts.clone(), &interrupted)
                .unwrap_or_else(|e| {
                    warn!("Bounded-request open failed ({e:#}); opening the source directly.");
                    None
                })
        } else {
            None
        };
        let (mut ictx, chunked) = match chunked {
            Some((ictx, io)) => (ictx, Some(io)),
            None => (
                ffmpeg::format::input_with_dictionary(&url, opts).context("open input stream")?,
                None,
            ),
        };

        // Route blocked network I / O through an interrupt callback so a kill() / teardown aborts the
        // current read promptly instead of waiting out the 15 s rw_timeout. Deliberately armed
        // after the open: the protocols underneath were opened without it, so an interrupt stops
        // the demuxer between reads instead of tearing down a request mid-response — which is what
        // makes reconnect re-splice the byte stream at a stale offset (corrupt packets, partial
        // atoms) every time a seek kills the reader.
        unsafe {
            let p = ictx.as_mut_ptr();
            (*p).interrupt_callback.callback = Some(interrupt_cb);
            (*p).interrupt_callback.opaque = Arc::as_ptr(&interrupted) as *mut c_void;
        }

        if start_micros > 0 {
            // AV_TIME_BASE units; keyframe at or before the target. Retry unbounded when the bounded
            // lookup can't be satisfied.
            if ictx.seek(start_micros, ..start_micros).is_err() {
                ictx.seek(start_micros, ..)
                    .with_context(|| format!("initial seek to {} ms.", start_micros / 1_000))?;
            }
        }

        let input = ictx
            .streams()
            .best(Type::Video)
            .context("no video stream found in input")?;
        let stream_index = input.index();
        let time_base = input.time_base();
        let stream_start_time = match input.start_time() {
            ffi::AV_NOPTS_VALUE => None,
            start => Some(start),
        };

        let parameters = input.parameters();
        let codec_params = unsafe { codec_params_from(&parameters, time_base) };
        let (mut decoder, hw_selection) =
            open_video_decoder(&parameters, time_base, HwAccelRequest::from_code(hw_accel))
                .context("open video decoder")?;
        let preroll_fast = start_micros > 0;
        if preroll_fast {
            set_preroll_discard(&mut decoder, true);
        }

        Ok(LavSession {
            id: AtomicI64::new(0),
            w,
            h,
            read: Mutex::new(ReadState {
                decoder,
                _hw_selection: hw_selection,
                time_base,
                scaler: None,
                sw_frame: VideoFrame::empty(),
                draining: false,
                seek_target_nanos: start_micros.checked_mul(1_000).filter(|_| start_micros > 0),
                seek_debug: (start_micros > 0).then(SeekDebug::begin),
                preroll_fast,
                pending_replay: VecDeque::new(),
                stats: ReadStats::default(),
                source: PacketSource::Live {
                    ictx,
                    stream_index,
                    stream_start_time,
                },
                chunked,
            }),
            ring: Mutex::new(None),
            interrupted,
            error: Mutex::new(String::new()),
            codec_params,
        })
    }

    fn open_replay(blob: &[u8], w: usize, h: usize, resume_nanos: i64) -> Result<LavSession> {
        init_ffmpeg()?;

        let (codec_params, snapshot_packets) = crate::cache::deserialize_snapshot(blob)
            .context("bad magic or truncated blob.")?;
        if codec_params.time_base_den == 0 {
            bail!("snapshot carries an invalid time base (den is 0).");
        }
        let packets = packets_from_position(&snapshot_packets, resume_nanos);
        if packets.is_empty() {
            bail!(
                "no keyframe-aligned packets at or before the resume position ({} ms; {} packets cached).",
                resume_nanos / 1_000_000,
                snapshot_packets.len(),
            );
        }

        let time_base =
            ffmpeg::Rational::new(codec_params.time_base_num, codec_params.time_base_den);
        let parameters = unsafe {
            parameters_from_codec_params(&codec_params)
                .context("rebuild codec parameters from snapshot.")?
        };
        let (decoder, hw_selection) =
            open_video_decoder(&parameters, time_base, HwAccelRequest::None)
                .context("open replay video decoder.")?;

        Ok(LavSession {
            id: AtomicI64::new(0),
            w,
            h,
            read: Mutex::new(ReadState {
                decoder,
                _hw_selection: hw_selection,
                time_base,
                scaler: None,
                sw_frame: VideoFrame::empty(),
                draining: false,
                seek_target_nanos: None,
                seek_debug: None,
                preroll_fast: false,
                pending_replay: VecDeque::new(),
                stats: ReadStats::default(),
                chunked: None,
                source: PacketSource::Replay {
                    packets,
                    next_packet: 0,
                    resume_nanos,
                },
            }),
            ring: Mutex::new(None),
            interrupted: Arc::new(AtomicBool::new(false)),
            error: Mutex::new(String::new()),
            codec_params,
        })
    }

    fn enable_cache(&self, window_nanos: i64, max_bytes: usize) {
        if let Ok(mut ring) = self.ring.lock() {
            *ring = Some(PacketRing::new(window_nanos, max_bytes));
        }
    }

    fn snapshot(&self) -> Vec<u8> {
        self.snapshot_at(i64::MIN, false)
    }

    fn snapshot_at(&self, position_nanos: i64, _top_up: bool) -> Vec<u8> {
        let Ok(ring) = self.ring.lock() else {
            return Vec::new();
        };
        let Some(ring) = ring.as_ref() else {
            return Vec::new();
        };
        let packets = ring.drain_from(position_nanos);
        if packets.is_empty() {
            return Vec::new();
        }
        crate::cache::serialize_snapshot(&self.codec_params, &packets)
    }

    fn capture_packet(
        &self,
        time_base: ffmpeg::Rational,
        stream_start_time: Option<i64>,
        packet: &ffmpeg::Packet,
    ) {
        if let Ok(mut ring) = self.ring.lock() {
            if let Some(ring) = ring.as_mut() {
                capture_packet_into_ring(time_base, stream_start_time, ring, packet);
            }
        }
    }

    fn read_frame(&self, dst: &mut [u8]) -> i32 {
        let mut pts_nanos = NO_PTS_NANOS;
        loop {
            let rc = self.read_frame_with_pts(dst, &mut pts_nanos);
            if rc != READ_PREVIEW {
                return rc;
            }
        }
    }

    fn read_frame_with_pts(&self, dst: &mut [u8], pts_nanos: &mut i64) -> i32 {
        *pts_nanos = NO_PTS_NANOS;
        let c = ((self.w + 1) / 2) * ((self.h + 1) / 2);
        if dst.len() < self.w * self.h + 2 * c {
            return ERR_BAD_ARGS;
        }
        let Ok(mut state) = self.read.lock() else {
            return ERR_IO;
        };
        match self.next_frame(&mut state, dst) {
            Ok(Some((pts, preview))) => {
                *pts_nanos = pts;
                if preview { READ_PREVIEW } else { READ_OK }
            }
            Ok(None) if self.interrupted.load(Ordering::Relaxed) => READ_INTERRUPTED,
            Ok(None) => READ_EOF,
            Err(e) => {
                warn!("LAV frame decode failed: {e:#}");
                if let Ok(mut err) = self.error.lock() {
                    *err = format!("{e:#}");
                }
                ERR_IO
            }
        }
    }

    fn cached_packets_for_seek(&self, target_nanos: i64) -> Option<Vec<CachedPacket>> {
        let ring_guard = self.ring.lock().ok()?;
        let ring = ring_guard.as_ref()?;
        let newest = ring.newest_ts();
        if newest == crate::cache::NO_PTS || target_nanos > newest {
            return None;
        }
        let packets = ring.drain_from(target_nanos);
        let first_pts = packets.first()?.pts_nanos;
        if first_pts == NO_PTS_NANOS || first_pts > target_nanos + SEEK_PREROLL_TOLERANCE_NANOS {
            return None;
        }
        Some(packets)
    }

    fn seek(&self, target_micros: i64) -> Result<()> {
        self.interrupted.store(false, Ordering::Relaxed);
        let mut state = self
            .read
            .lock()
            .map_err(|_| anyhow!("LAV reader lock poisoned."))?;
        if matches!(state.source, PacketSource::Replay { .. }) {
            bail!("replay sessions are not seekable.");
        }
        let target_nanos = target_micros.saturating_mul(1_000);
        if let Some(packets) = self.cached_packets_for_seek(target_nanos) {
            debug!(
                "LAV session #{}: seek to {} ms served from the packet cache ({} packets from {} ms).",
                self.id.load(Ordering::Relaxed),
                target_micros / 1_000,
                packets.len(),
                packets[0].pts_nanos / 1_000_000,
            );
            state.pending_replay = packets.into();
        } else {
            state.pending_replay.clear();
            let PacketSource::Live { ictx, .. } = &mut state.source else {
                unreachable!("replay sources bail out above");
            };
            let demux_started = std::time::Instant::now();
            let bounded = ictx.seek(target_micros, ..target_micros).is_ok();
            if !bounded {
                ictx.seek(target_micros, ..)
                    .with_context(|| format!("demuxer seek to {} ms.", target_micros / 1_000))?;
            }
            debug!(
                "LAV session #{}: demuxer seek to {} ms took {} ms ({}).",
                self.id.load(Ordering::Relaxed),
                target_micros / 1_000,
                demux_started.elapsed().as_millis(),
                if bounded { "bounded" } else { "unbounded fallback" },
            );
            let _ = ictx.play();
            if let Ok(mut ring) = self.ring.lock() {
                if let Some(ring) = ring.as_mut() {
                    ring.clear();
                }
            }
        }
        unsafe {
            ffi::avcodec_flush_buffers(state.decoder.as_mut_ptr());
            ffi::av_frame_unref(state.sw_frame.as_mut_ptr());
        }
        state.seek_target_nanos = Some(target_nanos);
        state.seek_debug = Some(SeekDebug::begin());
        state.preroll_fast = true;
        set_preroll_discard(&mut state.decoder, true);
        state.draining = false;
        Ok(())
    }

    fn read_surface(&self) -> Result<Option<LavSurfaceFrame>, SurfaceReadError> {
        let mut state = self
            .read
            .lock()
            .map_err(|_| SurfaceReadError::Io("LAV reader lock poisoned.".to_string()))?;
        match self
            .receive_frame(&mut state)
            .map_err(|e| SurfaceReadError::Io(format!("{e:#}.")))?
        {
            Some(frame) => LavSurfaceFrame::from_video_frame(&frame)
                .map(Some)
                .map_err(SurfaceReadError::Unsupported),
            None => Ok(None),
        }
    }

    fn next_frame(&self, state: &mut ReadState, dst: &mut [u8]) -> Result<Option<(i64, bool)>> {
        loop {
            let Some(decoded) = self.receive_frame(state)? else {
                return Ok(None);
            };
            let pts_nanos = frame_pts_nanos(&decoded, state.time_base, state.stream_start_time());
            if state.should_drop_replay_preroll(pts_nanos) {
                continue;
            }
            if let Some(target) = state.seek_target_nanos {
                if pts_nanos != NO_PTS_NANOS && pts_nanos + SEEK_PREROLL_TOLERANCE_NANOS < target {
                    let preview = state
                        .seek_debug
                        .as_ref()
                        .is_some_and(|d| d.landed_pts_nanos.is_none());
                    if let Some(dbg) = state.seek_debug.as_mut() {
                        dbg.landed_pts_nanos.get_or_insert(pts_nanos);
                        if !preview {
                            dbg.dropped += 1;
                        }
                    }
                    if state.preroll_fast && pts_nanos + PREROLL_FAST_CUTOFF_NANOS >= target {
                        state.preroll_fast = false;
                        set_preroll_discard(&mut state.decoder, false);
                    }
                    if preview {
                        // Deliver the keyframe the demuxer landed on immediately: the pre-roll to
                        // the exact target is network-bound (seconds on slow CDNs), and showing
                        // the nearest earlier keyframe right away beats freezing on the old
                        // picture. The JVM presents it without starting the playback clock.
                        self.write_i420(state, &decoded, dst)?;
                        return Ok(Some((pts_nanos, true)));
                    }
                    continue;
                }
                state.seek_target_nanos = None;
                if state.preroll_fast {
                    state.preroll_fast = false;
                    set_preroll_discard(&mut state.decoder, false);
                }
                if let Some(dbg) = state.seek_debug.take() {
                    let elapsed_ms = dbg.started.elapsed().as_millis();
                    let landed = dbg
                        .landed_pts_nanos
                        .or_else(|| (pts_nanos != NO_PTS_NANOS).then_some(pts_nanos));
                    let landed_ms = landed.unwrap_or(target) / 1_000_000;
                    let msg = format!(
                        "LAV session #{}: seek pre-roll to {} ms done in {elapsed_ms} ms; demuxer \
                         landed on keyframe at {landed_ms} ms ({} s before target), decoded and \
                         dropped {} pre-roll frames.",
                        self.id.load(Ordering::Relaxed),
                        target / 1_000_000,
                        (target / 1_000_000 - landed_ms) as f64 / 1_000.0,
                        dbg.dropped,
                    );
                    if elapsed_ms >= SLOW_PREROLL_WARN_MS {
                        warn!("{msg}");
                    } else {
                        debug!("{msg}");
                    }
                }
            }
            let write_started = std::time::Instant::now();
            self.write_i420(state, &decoded, dst)?;
            if let Some(report) = state.stats.record_frame(write_started.elapsed().as_nanos()) {
                debug!(
                    "LAV session #{}: {report}",
                    self.id.load(Ordering::Relaxed),
                );
            }
            return Ok(Some((pts_nanos, false)));
        }
    }

    fn receive_frame(&self, state: &mut ReadState) -> Result<Option<VideoFrame>> {
        let mut decoded = VideoFrame::empty();
        loop {
            if self.interrupted.load(Ordering::Relaxed) {
                return Ok(None);
            }

            let receive_started = std::time::Instant::now();
            let received = state.decoder.receive_frame(&mut decoded).is_ok();
            state.stats.decode_nanos += receive_started.elapsed().as_nanos();
            if received {
                return Ok(Some(decoded));
            }
            if state.draining {
                return Ok(None);
            }

            let time_base = state.time_base;
            let mut read_nanos = 0u128;
            let mut demux_bytes = 0u64;
            let mut send_nanos = 0u128;
            match &mut state.source {
                PacketSource::Live {
                    ictx,
                    stream_index,
                    stream_start_time,
                } => {
                    // A cache-served seek scheduled packets ahead of the live head: feed those
                    // first (network-free); live reads resume exactly where the ring left off.
                    // They are already in the ring, so they are not re-captured.
                    if let Some(cached) = state.pending_replay.pop_front() {
                        let packet = packet_from_cached(&cached, time_base, *stream_start_time);
                        let send_started = std::time::Instant::now();
                        state
                            .decoder
                            .send_packet(&packet)
                            .context("send cached packet to live decoder.")?;
                        state.stats.send_nanos += send_started.elapsed().as_nanos();
                        continue;
                    }
                    let mut packet = ffmpeg::Packet::empty();
                    let read_started = std::time::Instant::now();
                    let read_result = packet.read(ictx);
                    let read_elapsed = read_started.elapsed();
                    read_nanos = read_elapsed.as_nanos();
                    let read_ms = read_elapsed.as_millis();
                    if read_ms >= SLOW_READ_WARN_MS {
                        warn!(
                            "LAV session #{}: demuxer read blocked for {read_ms} ms (network stall).",
                            self.id.load(Ordering::Relaxed),
                        );
                    }
                    match read_result {
                        Ok(()) => {
                            demux_bytes += packet.size() as u64;
                            if let Some(dbg) = state.seek_debug.as_mut() {
                                dbg.bytes += packet.size() as u64;
                            }
                            if packet.stream() == *stream_index {
                                self.capture_packet(time_base, *stream_start_time, &packet);
                                let send_started = std::time::Instant::now();
                                state
                                    .decoder
                                    .send_packet(&packet)
                                    .context("send demuxed packet to decoder.")?;
                                send_nanos += send_started.elapsed().as_nanos();
                            }
                        }
                        Err(ffmpeg::Error::Eof) => {
                            state.decoder.send_eof().context("send EOF to decoder.")?;
                            state.draining = true;
                        }
                        Err(ffmpeg::Error::Other { errno })
                            if errno == ffmpeg::util::error::EAGAIN => {}
                        Err(ffmpeg::Error::Exit) => return Ok(None),
                        Err(_) if self.interrupted.load(Ordering::Relaxed) => return Ok(None),
                        Err(e) => return Err(e).context("read packet from input."),
                    }
                }
                PacketSource::Replay {
                    packets,
                    next_packet,
                    ..
                } => {
                    if *next_packet >= packets.len() {
                        state.decoder.send_eof().context("send EOF to decoder.")?;
                        state.draining = true;
                    } else {
                        let packet = packet_from_cached(&packets[*next_packet], state.time_base, None);
                        *next_packet += 1;
                        state
                            .decoder
                            .send_packet(&packet)
                            .context("send cached packet to replay decoder.")?;
                    }
                }
            }
            if read_nanos > 0 {
                state.stats.record_read(read_nanos, demux_bytes);
            }
            state.stats.send_nanos += send_nanos;
        }
    }

    fn write_i420(&self, state: &mut ReadState, frame: &VideoFrame, dst: &mut [u8]) -> Result<()> {
        // Hardware frames live outside normal CPU memory; pull them down to the best software
        // format FFmpeg can provide before scaling to the target I420 frame.
        let src: &VideoFrame = if is_hardware_frame(frame.format()) {
            let started = std::time::Instant::now();
            unsafe {
                ffi::av_frame_unref(state.sw_frame.as_mut_ptr());
                let rc =
                    ffi::av_hwframe_transfer_data(state.sw_frame.as_mut_ptr(), frame.as_ptr(), 0);
                if rc < 0 {
                    return Err(ffmpeg::Error::from(rc))
                        .context("transfer hardware frame to system memory.");
                }
            }
            state.stats.transfer_nanos += started.elapsed().as_nanos();
            &state.sw_frame
        } else {
            frame
        };

        let (sw, sh) = (src.width(), src.height());
        if sw == 0 || sh == 0 {
            bail!("decoded frame has zero dimensions ({sw} x {sh}).");
        }

        // Aspect-fit into the target, even dimensions for clean 4:2:0 chroma.
        let fit = (self.w as f64 / sw as f64).min(self.h as f64 / sh as f64);
        let fw = (((sw as f64 * fit) as u32) & !1).max(2).min(self.w as u32);
        let fh = (((sh as f64 * fit) as u32) & !1).max(2).min(self.h as u32);

        let format = src.format();
        let rebuild = match &state.scaler {
            Some(s) => s.src_format != format || s.src_w != sw || s.src_h != sh || s.dst_w != fw || s.dst_h != fh,
            None => true,
        };
        if rebuild {
            let scaler = BandedScaler::new(format, sw, sh, fw, fh)
                .with_context(|| format!("create swscale context {format:?} {sw} x {sh} -> {fw} x {fh}."))?;
            debug!(
                "LAV session #{}: scaling {format:?} {sw} x {sh} -> {fw} x {fh} across {} band(s).",
                self.id.load(Ordering::Relaxed),
                scaler.bands(),
            );
            state.scaler = Some(scaler);
        }
        // Compose into the caller's buffer: black background, fitted frame centered
        let (tw, th) = (self.w, self.h);
        let cw = (tw + 1) / 2;
        let ch = (th + 1) / 2;
        let y_size = tw * th;
        let c_size = cw * ch;
        if dst.len() < y_size + 2 * c_size {
            bail!(
                "destination holds {} bytes, a {tw} x {th} I420 frame needs {}.",
                dst.len(),
                y_size + 2 * c_size,
            );
        }
        // Even offsets keep luma and chroma alignment consistent
        let x0 = ((tw - fw as usize) / 2) & !1;
        let y0 = ((th - fh as usize) / 2) & !1;

        if fw as usize != tw || fh as usize != th {
            fill_bars(dst, tw, th, x0, y0, fw as usize, fh as usize);
        }

        let scale_started = std::time::Instant::now();
        let scaler = state.scaler.as_ref().unwrap();
        unsafe {
            let base = dst.as_mut_ptr();
            let planes: [*mut u8; 4] = [
                base.add(y0 * tw + x0),
                base.add(y_size + (y0 / 2) * cw + x0 / 2),
                base.add(y_size + c_size + (y0 / 2) * cw + x0 / 2),
                ptr::null_mut(),
            ];
            let strides: [c_int; 4] = [tw as c_int, cw as c_int, cw as c_int, 0];
            scaler.scale(src, planes, strides)?;
        }
        state.stats.scale_nanos += scale_started.elapsed().as_nanos();
        state.stats.scale_geometry = Some((sw, sh, fw, fh, scaler.bands()));
        Ok(())
    }
}

/// Rebuilds an encoded packet from cached payload plus normalized timestamps. When the packet is
/// fed back into a live session's decoder, `stream_start_time` re-applies the origin offset that
/// [capture_packet_into_ring] subtracted, so the decoded frames normalize back to the same PTS.
fn packet_from_cached(
    cached: &CachedPacket,
    time_base: ffmpeg::Rational,
    stream_start_time: Option<i64>,
) -> ffmpeg::Packet {
    let start = stream_start_time.unwrap_or(0);
    let mut packet = ffmpeg::Packet::copy(&cached.data);
    packet.set_pts(nanos_to_ticks(cached.pts_nanos, time_base).map(|t| t + start));
    packet.set_dts(nanos_to_ticks(cached.dts_nanos, time_base).map(|t| t + start));
    packet.set_position(-1);
    if cached.keyframe {
        packet.set_flags(ffmpeg::codec::packet::Flags::KEY);
    }
    packet
}

/// Converts normalized nanosecond PTS / DTS back into stream time-base ticks.
fn nanos_to_ticks(nanos: i64, time_base: ffmpeg::Rational) -> Option<i64> {
    if nanos == NO_PTS_NANOS {
        return None;
    }
    let num = i128::from(time_base.numerator());
    let den = i128::from(time_base.denominator());
    if num == 0 || den == 0 {
        return None;
    }
    let ticks = i128::from(nanos)
        .checked_mul(den)?
        .checked_div(num.checked_mul(1_000_000_000)?)?;
    if ticks < i128::from(i64::MIN) || ticks > i128::from(i64::MAX) {
        None
    } else {
        Some(ticks as i64)
    }
}

fn capture_packet_into_ring(
    time_base: ffmpeg::Rational,
    stream_start_time: Option<i64>,
    ring: &mut PacketRing,
    packet: &ffmpeg::Packet,
) {
    let Some(data) = packet.data() else {
        return;
    };
    ring.push(CachedPacket {
        data: data.to_vec(),
        pts_nanos: packet_ts_nanos(packet.pts(), time_base, stream_start_time),
        dts_nanos: packet_ts_nanos(packet.dts(), time_base, stream_start_time),
        keyframe: packet.is_key(),
    });
}

/// Reconstructs the subset of `AVCodecParameters` needed to open a software replay decoder.
unsafe fn parameters_from_codec_params(params: &CodecParams) -> Result<codec::Parameters> {
    if params.codec_id <= 0 || params.width <= 0 || params.height <= 0 {
        bail!(
            "incomplete codec parameters (codec_id {}, {}x{}).",
            params.codec_id,
            params.width,
            params.height,
        );
    }
    let mut parameters = codec::Parameters::new();
    let p = unsafe {
        // Safety: parameters is newly allocated and exclusively owned here
        parameters.as_mut_ptr()
    };
    if p.is_null() {
        bail!("avcodec_parameters_alloc returned null.");
    }
    unsafe {
        // Safety: p is the valid mutable AVCodecParameters pointer owned by parameters
        (*p).codec_type = ffi::AVMediaType::AVMEDIA_TYPE_VIDEO;
        (*p).codec_id = mem::transmute::<u32, ffi::AVCodecID>(params.codec_id as u32);
        (*p).width = params.width;
        (*p).height = params.height;
        if !params.extradata.is_empty() {
            let len = params.extradata.len();
            let padded = len
                .checked_add(ffi::AV_INPUT_BUFFER_PADDING_SIZE as usize)
                .with_context(|| format!("extradata length {len} overflows with padding."))?;
            let dst = ffi::av_mallocz(padded).cast::<u8>();
            if dst.is_null() {
                bail!("av_mallocz({padded}) returned null for extradata");
            }
            ptr::copy_nonoverlapping(params.extradata.as_ptr(), dst, len);
            (*p).extradata = dst;
            (*p).extradata_size = len as i32;
        }
    }
    Ok(parameters)
}

/// Normalizes an optional packet timestamp (stream ticks) to nanoseconds, or `NO_PTS_NANOS`.
fn packet_ts_nanos(
    ts: Option<i64>,
    time_base: ffmpeg::Rational,
    stream_start_time: Option<i64>,
) -> i64 {
    let Some(raw) = ts else {
        return NO_PTS_NANOS;
    };
    let normalized = stream_start_time
        .map(|start| raw.saturating_sub(start))
        .unwrap_or(raw);
    rational_pts_to_nanos(normalized, time_base).unwrap_or(NO_PTS_NANOS)
}

/// Reads the [`CodecParams`] needed to rebuild a decoder for replay from `parameters`.
///
/// Safety: `parameters` must wrap a valid `AVCodecParameters`.
unsafe fn codec_params_from(
    parameters: &codec::Parameters,
    time_base: ffmpeg::Rational,
) -> CodecParams {
    let p = unsafe {
        // Safety: parameters wraps an AVCodecParameters owned by ffmpeg-next
        parameters.as_ptr()
    };
    if p.is_null() {
        return CodecParams::default();
    }
    let cp = unsafe {
        // Safety: p is non-null and valid for the lifetime of parameters
        &*p
    };
    let extradata = if !cp.extradata.is_null() && cp.extradata_size > 0 {
        unsafe {
            // Safety: libav owns extradata with extradata_size bytes while parameters lives
            std::slice::from_raw_parts(cp.extradata, cp.extradata_size as usize).to_vec()
        }
    } else {
        Vec::new()
    };
    CodecParams {
        codec_id: cp.codec_id as i32,
        width: cp.width,
        height: cp.height,
        time_base_num: time_base.numerator(),
        time_base_den: time_base.denominator(),
        extradata,
    }
}

fn frame_pts_nanos(
    frame: &VideoFrame,
    time_base: ffmpeg::Rational,
    stream_start_time: Option<i64>,
) -> i64 {
    let Some(raw_pts) = frame.timestamp().or_else(|| frame.pts()) else {
        return NO_PTS_NANOS;
    };
    let pts = stream_start_time
        .map(|start| raw_pts.saturating_sub(start))
        .unwrap_or(raw_pts);
    rational_pts_to_nanos(pts, time_base).unwrap_or(NO_PTS_NANOS)
}

fn rational_pts_to_nanos(pts: i64, time_base: ffmpeg::Rational) -> Option<i64> {
    let den = i128::from(time_base.denominator());
    if den == 0 {
        return None;
    }
    let ns = i128::from(pts)
        .checked_mul(i128::from(time_base.numerator()))?
        .checked_mul(1_000_000_000)?
        .checked_div(den)?;
    if ns < i128::from(i64::MIN) || ns > i128::from(i64::MAX) {
        None
    } else {
        Some(ns as i64)
    }
}

fn is_hardware_frame(format: Pixel) -> bool {
    matches!(
        format,
        Pixel::VIDEOTOOLBOX
            | Pixel::D3D11
            | Pixel::D3D11VA_VLD
            | Pixel::DXVA2_VLD
            | Pixel::VAAPI
            | Pixel::CUDA
    )
}


fn open_video_decoder(
    parameters: &codec::Parameters,
    packet_time_base: ffmpeg::Rational,
    request: HwAccelRequest,
) -> Result<(codec::decoder::Video, Option<Box<HwSelection>>)> {
    let codec = codec::decoder::find(parameters.id())
        .with_context(|| format!("no decoder available for codec {:?}", parameters.id()))?;

    if request != HwAccelRequest::None {
        for backend in request.candidates() {
            if let Some(mut selection) = create_hw_selection(unsafe { codec.as_ptr() }, *backend) {
                let mut context = new_decoder_context(parameters)?;
                unsafe {
                    (*context.as_mut_ptr()).opaque =
                        (&mut *selection as *mut HwSelection).cast::<c_void>();
                    (*context.as_mut_ptr()).get_format = Some(prefer_selected_hw_format);
                    let ctx_device = ffi::av_buffer_ref(selection.device_ctx);
                    if !ctx_device.is_null() {
                        (*context.as_mut_ptr()).hw_device_ctx = ctx_device;
                        let mut decoder = context.decoder();
                        decoder.set_packet_time_base(packet_time_base);
                        match decoder.open_as(codec).and_then(|opened| opened.video()) {
                            Ok(decoder) => {
                                info!(
                                    "LAV decoder opened with {} hardware acceleration.",
                                    backend.name
                                );
                                return Ok((decoder, Some(selection)));
                            }
                            Err(e) => debug!(
                                "{} decoder open failed, trying the next backend: {e}.",
                                backend.name
                            ),
                        }
                    }
                }
            } else {
                debug!("{} device is unavailable for this codec.", backend.name);
            }
        }
        if !request.candidates().is_empty() {
            warn!("No {request:?} hardware backend engaged; falling back to software decode.");
        }
    }

    let context = new_decoder_context(parameters)?;
    let mut decoder = context.decoder();
    decoder.set_packet_time_base(packet_time_base);
    decoder
        .open_as(codec)
        .and_then(|opened| opened.video())
        .map(|decoder| (decoder, None))
        .context("open software decoder")
}

fn new_decoder_context(
    parameters: &codec::Parameters,
) -> Result<codec::context::Context, ffmpeg::Error> {
    let mut context = codec::context::Context::from_parameters(parameters.clone())?;
    unsafe {
        // Auto thread count; the default AVCodecContext is single-threaded
        (*context.as_mut_ptr()).thread_count = 0;
        // The source picks the frame size, and a player can point a display at any file: a header
        // claiming 32768 x 32768 would have libav allocate gigabytes per frame before anything of
        // ours got a say. Well above 8K, far below what hurts.
        (*context.as_mut_ptr()).max_pixels = MAX_DECODED_PIXELS;
    }
    Ok(context)
}

fn create_hw_selection(codec: *const ffi::AVCodec, backend: HwBackend) -> Option<Box<HwSelection>> {
    unsafe {
        let pix_fmt = codec_hw_pixel_format(codec, backend)?;
        let mut device: *mut ffi::AVBufferRef = ptr::null_mut();
        let rc = ffi::av_hwdevice_ctx_create(
            &mut device,
            backend.device_type,
            ptr::null(),
            ptr::null_mut(),
            0,
        );
        if rc >= 0 && !device.is_null() {
            return Some(Box::new(HwSelection {
                pix_fmt,
                device_ctx: device,
            }));
        }
    }
    None
}

unsafe fn codec_hw_pixel_format(
    codec: *const ffi::AVCodec,
    backend: HwBackend,
) -> Option<ffi::AVPixelFormat> {
    const HW_DEVICE_CTX: i32 = ffi::AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX as i32;
    let mut i = 0;
    loop {
        let cfg = unsafe {
            // Safety: codec is provided by libav and valid while probing its static HW configs
            ffi::avcodec_get_hw_config(codec, i)
        };
        if cfg.is_null() {
            return None;
        }
        let cfg = unsafe {
            // Safety: libav returned a non-null AVCodecHWConfig pointer for this index
            &*cfg
        };
        if cfg.device_type == backend.device_type
            && (cfg.methods & HW_DEVICE_CTX) != 0
            && backend.pix_fmts.contains(&cfg.pix_fmt)
        {
            return Some(cfg.pix_fmt);
        }
        i += 1;
    }
}

/// `get_format` callback: picks the hardware pixel format selected for this decoder,
/// otherwise falls back to the first software format offered by libavcodec.
unsafe extern "C" fn prefer_selected_hw_format(
    ctx: *mut ffi::AVCodecContext,
    formats: *const ffi::AVPixelFormat,
) -> ffi::AVPixelFormat {
    let selection = if ctx.is_null() {
        ptr::null()
    } else {
        unsafe {
            // Safety: libav passes the AVCodecContext currently invoking this callback
            (*ctx).opaque.cast::<HwSelection>()
        }
    };
    let desired = if selection.is_null() {
        ffi::AVPixelFormat::AV_PIX_FMT_NONE
    } else {
        unsafe {
            // Safety: selection was stored in AVCodecContext.opaque before decoder open and
            // remains alive while libav invokes this callback.
            (*selection).pix_fmt
        }
    };
    let mut p = formats;
    unsafe {
        // Safety: libav supplies a non-null, AV_PIX_FMT_NONE-terminated array of formats.
        while *p != ffi::AVPixelFormat::AV_PIX_FMT_NONE {
            if *p == desired {
                return *p;
            }
            p = p.add(1);
        }
        *formats
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read_skipping_preview(
        sessions: &LavSessions,
        handle: i64,
        dst: &mut [u8],
        target_nanos: i64,
        pts: &mut i64,
    ) -> i32 {
        let rc = sessions.read_frame_with_pts(handle, dst, pts);
        if rc != READ_PREVIEW {
            return rc;
        }
        assert!(
            *pts != NO_PTS_NANOS && *pts < target_nanos,
            "Preview PTS must precede the seek target, got {} for {target_nanos}.",
            *pts,
        );
        let rc = sessions.read_frame_with_pts(handle, dst, pts);
        assert_ne!(rc, READ_PREVIEW, "At most one preview per seek.");
        rc
    }

    #[test]
    fn local_file_end_to_end() {
        let ffmpeg_bin = std::env::var("DD_TEST_FFMPEG").unwrap_or_else(|_| "ffmpeg".into());
        let dir = std::env::temp_dir().join("dd-lav-test");
        std::fs::create_dir_all(&dir).unwrap();
        let clip = dir.join("clip.mp4");
        let status = std::process::Command::new(&ffmpeg_bin)
            .args([
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=30:duration=1",
                "-pix_fmt",
                "yuv420p",
                clip.to_str().unwrap(),
            ])
            .status();
        let Ok(status) = status else { return };
        if !status.success() {
            return;
        }

        let sessions = LavSessions::new();
        let handle = sessions.open(clip.to_str().unwrap(), 640, 360, 0, 0);
        assert_ne!(handle, 0, "Open failed.");

        let mut dst = vec![0u8; 640 * 360 * 3 / 2];
        let mut frames = 0;
        let mut last_pts = None;
        loop {
            let mut pts_nanos = NO_PTS_NANOS;
            match sessions.read_frame_with_pts(handle, &mut dst, &mut pts_nanos) {
                READ_OK => {
                    assert_ne!(pts_nanos, NO_PTS_NANOS, "Test clip should expose frame PTS");
                    if frames == 0 {
                        assert!(pts_nanos >= 0, "First PTS should be normalized");
                    }
                    if let Some(prev) = last_pts {
                        assert!(pts_nanos >= prev, "Frame PTS should be monotonic");
                    }
                    last_pts = Some(pts_nanos);
                    frames += 1;
                }
                READ_EOF => break,
                e => panic!("Read error {e}."),
            }
        }
        assert_eq!(frames, 30, "Expected 30 frames.");
        sessions.close(handle);
    }

    #[test]
    fn only_platform_hosts_get_a_referer() {
        assert_eq!(
            referer_for("https://rr3---sn-oxu.googlevideo.com/videoplayback?x=1"),
            Some("https://www.youtube.com/"),
        );
        assert_eq!(
            referer_for("https://upos-sz.bilivideo.com/x.m4s"),
            Some("https://www.bilibili.com/"),
        );
        assert_eq!(referer_for("https://example.com/clip.mp4"), None);
        assert_eq!(referer_for("https://youtube.com.evil.tld/clip.mp4"), None);
        assert_eq!(referer_for("https://notyoutube.com/clip.mp4"), None);
        assert_eq!(referer_for("https://evil.tld:8443/clip.mp4"), None);
        assert_eq!(
            referer_for("https://user@www.youtube.com:443/watch"),
            Some("https://www.youtube.com/"),
        );
    }

    #[test]
    fn stats_report_attributes_each_stage() {
        let mut stats = ReadStats {
            window: Some(
                std::time::Instant::now()
                    - std::time::Duration::from_secs(STATS_WINDOW_SECS + 1),
            ),
            ..Default::default()
        };
        stats.record_read(10_000_000, 1024 * 1024); // Went to the wire, 1 MiB
        stats.record_read(100_000, 0); // Served from the AVIO buffer
        stats.send_nanos = 1_000_000;
        stats.decode_nanos = 2_000_000;
        stats.transfer_nanos = 3_000_000;
        stats.scale_nanos = 4_000_000;
        let report = stats.record_frame(5_000_000).expect("the window has elapsed");

        for expected in [
            "network wait 10.00 ms",
            "demux CPU 0.10 ms",
            "send 1.00 ms",
            "decoder 2.00 ms",
            "hw transfer 3.00 ms",
            "swscale 4.00 ms",
            "convert total 5.00 ms",
            "2 reads, 1 of them on the wire",
        ] {
            assert!(report.contains(expected), "{expected:?} missing from: {report}");
        }
    }

    #[test]
    fn padded_targets_keep_bars_and_center_the_picture() {
        let ffmpeg_bin = std::env::var("DD_TEST_FFMPEG").unwrap_or_else(|_| "ffmpeg".into());
        let dir = std::env::temp_dir().join("dd-lav-padding-test");
        std::fs::create_dir_all(&dir).unwrap();
        let clip = dir.join("clip.mp4");
        let status = std::process::Command::new(&ffmpeg_bin)
            .args([
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=30:duration=1",
                "-pix_fmt",
                "yuv420p",
                clip.to_str().unwrap(),
            ])
            .status();
        let Ok(status) = status else { return };
        if !status.success() {
            return;
        }

        for (tw, th, x0, y0, fw, fh) in [(640usize, 480usize, 0usize, 60usize, 640usize, 360usize),
                                         (1280, 360, 320, 0, 640, 360)] {
            let sessions = LavSessions::new();
            let handle = sessions.open(clip.to_str().unwrap(), tw, th, 0, 0);
            assert_ne!(handle, 0, "Open failed for {tw} x {th}.");

            let mut dst = vec![0u8; tw * th * 3 / 2];
            assert_eq!(
                sessions.read_frame(handle, &mut dst),
                READ_OK,
                "Read failed for {tw} x {th}.",
            );
            let luma = &dst[..tw * th];
            for row in 0..th {
                let line = &luma[row * tw..(row + 1) * tw];
                let in_picture = row >= y0 && row < y0 + fh;
                if !in_picture {
                    assert!(
                        line.iter().all(|&b| b == BLACK_Y),
                        "{tw} x {th}: row {row} is a bar and must be black.",
                    );
                    continue;
                }
                assert!(
                    line[..x0].iter().all(|&b| b == BLACK_Y)
                        && line[x0 + fw..].iter().all(|&b| b == BLACK_Y),
                    "{tw} x {th}: row {row} has a non-black side bar.",
                );
            }
            assert!(
                luma[(y0 + fh / 2) * tw + x0..(y0 + fh / 2) * tw + x0 + fw]
                    .iter()
                    .any(|&b| b != BLACK_Y),
                "{tw} x {th}: the middle of the picture decoded as flat black.",
            );
            sessions.close(handle);
        }
    }

    #[test]
    fn cache_capture_and_snapshot() {
        let ffmpeg_bin = std::env::var("DD_TEST_FFMPEG").unwrap_or_else(|_| "ffmpeg".into());
        let dir = std::env::temp_dir().join("dd-lav-cache-test");
        std::fs::create_dir_all(&dir).unwrap();
        let clip = dir.join("clip.mp4");
        let status = std::process::Command::new(&ffmpeg_bin)
            .args([
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=30:duration=2",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "15",
                clip.to_str().unwrap(),
            ])
            .status();
        let Ok(status) = status else { return };
        if !status.success() {
            return;
        }

        let sessions = LavSessions::new();
        let handle = sessions.open(clip.to_str().unwrap(), 640, 360, 0, 0);
        assert_ne!(handle, 0, "open failed.");
        assert_eq!(
            sessions.enable_cache(handle, i64::MAX / 4, 64 * 1024 * 1024),
            READ_OK
        );

        let mut dst = vec![0u8; 640 * 360 * 3 / 2];
        loop {
            match sessions.read_frame(handle, &mut dst) {
                READ_OK => {}
                READ_EOF => break,
                e => panic!("read error {e}."),
            }
        }

        let len = sessions.snapshot(handle, &mut []);
        assert!(len > 0, "Snapshot should be non-empty after capture.");
        let mut blob = vec![0u8; len as usize];
        assert_eq!(
            sessions.snapshot(handle, &mut blob),
            len,
            "Second call should write the whole blob."
        );

        let (params, packets) =
            crate::cache::deserialize_snapshot(&blob).expect("Snapshot must be a valid blob.");
        assert!(!packets.is_empty(), "Should have captured packets.");
        assert!(packets[0].keyframe, "Cache must start at a keyframe.");
        assert!(
            params.width > 0 && params.height > 0,
            "Codec params dimensions."
        );
        assert_ne!(params.codec_id, 0, "Codec id should be captured.");
        assert!(
            !params.extradata.is_empty(),
            "H.264 in MP4 carries extradata."
        );
        let first_pts = packets.iter().find_map(|p| {
            if p.pts_nanos != crate::cache::NO_PTS {
                Some(p.pts_nanos)
            } else {
                None
            }
        });
        if let Some(first) = first_pts {
            assert!(first >= 0, "Normalized PTS should start at / after zero.");
        }
        sessions.close(handle);
    }

    #[test]
    fn replay_from_snapshot_mid_stream() {
        let ffmpeg_bin = std::env::var("DD_TEST_FFMPEG").unwrap_or_else(|_| "ffmpeg".into());
        let dir = std::env::temp_dir().join("dd-lav-replay-test");
        std::fs::create_dir_all(&dir).unwrap();
        let clip = dir.join("clip.mp4");
        let status = std::process::Command::new(&ffmpeg_bin)
            .args([
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=30:duration=3",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "15",
                clip.to_str().unwrap(),
            ])
            .status();
        let Ok(status) = status else { return };
        if !status.success() {
            return;
        }

        let sessions = LavSessions::new();
        let live = sessions.open(clip.to_str().unwrap(), 640, 360, 0, 0);
        assert_ne!(live, 0, "Live open failed.");
        assert_eq!(
            sessions.enable_cache(live, i64::MAX / 4, 64 * 1024 * 1024),
            READ_OK
        );

        let mut dst = vec![0u8; 640 * 360 * 3 / 2];
        while sessions.read_frame(live, &mut dst) == READ_OK {}

        let len = sessions.snapshot(live, &mut []);
        assert!(len > 0, "Snapshot should be non-empty.");
        let mut blob = vec![0u8; len as usize];
        assert_eq!(sessions.snapshot(live, &mut blob), len);
        sessions.close(live);

        let resume_nanos = 900_000_000;
        let replay = sessions.open_replay(&blob, 640, 360, resume_nanos);
        assert_ne!(replay, 0, "Replay open failed.");

        let mut frames = 0;
        let mut last_pts = None;
        loop {
            let mut pts_nanos = NO_PTS_NANOS;
            match sessions.read_frame_with_pts(replay, &mut dst, &mut pts_nanos) {
                READ_OK => {
                    assert_ne!(pts_nanos, NO_PTS_NANOS, "Replay frames should expose PTS.");
                    assert!(pts_nanos >= resume_nanos, "Pre-roll should be discarded.");
                    if let Some(prev) = last_pts {
                        assert!(pts_nanos >= prev, "Replay PTS should be monotonic.");
                    }
                    last_pts = Some(pts_nanos);
                    frames += 1;
                }
                READ_EOF => break,
                e => panic!("Replay read error {e}."),
            }
        }
        assert!(frames > 0, "Replay should produce frames after resume.");
        sessions.close(replay);
    }

    #[test]
    fn live_seek_discards_preroll_and_continues() {
        let ffmpeg_bin = std::env::var("DD_TEST_FFMPEG").unwrap_or_else(|_| "ffmpeg".into());
        let dir = std::env::temp_dir().join("dd-lav-seek-test");
        std::fs::create_dir_all(&dir).unwrap();
        let clip = dir.join("clip.mp4");
        let status = std::process::Command::new(&ffmpeg_bin)
            .args([
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=30:duration=4",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "30",
                clip.to_str().unwrap(),
            ])
            .status();
        let Ok(status) = status else { return };
        if !status.success() {
            return;
        }

        let sessions = LavSessions::new();
        let handle = sessions.open(clip.to_str().unwrap(), 640, 360, 0, 0);
        assert_ne!(handle, 0, "Open failed.");

        let mut dst = vec![0u8; 640 * 360 * 3 / 2];
        for _ in 0..10 {
            assert_eq!(sessions.read_frame(handle, &mut dst), READ_OK);
        }

        let target_nanos = 2_100_000_000_i64;
        assert_eq!(sessions.seek(handle, target_nanos / 1_000), READ_OK);

        let mut first_pts = NO_PTS_NANOS;
        assert_eq!(
            read_skipping_preview(&sessions, handle, &mut dst, target_nanos, &mut first_pts),
            READ_OK,
            "First read after seek should produce a frame.",
        );
        assert_ne!(
            first_pts, NO_PTS_NANOS,
            "Seek test clip should expose frame PTS."
        );
        assert!(
            first_pts + SEEK_PREROLL_TOLERANCE_NANOS >= target_nanos,
            "First post-seek frame should be near target, got {first_pts} for target {target_nanos}.",
        );

        let mut frames = 1;
        while frames < 8 {
            let mut pts = NO_PTS_NANOS;
            match sessions.read_frame_with_pts(handle, &mut dst, &mut pts) {
                READ_OK => {
                    assert!(pts >= first_pts, "Post-seek PTS should be monotonic.");
                    frames += 1;
                }
                READ_EOF => break,
                e => panic!("Read after seek failed with {e}."),
            }
        }
        assert!(
            frames >= 4,
            "Seek should resume decode, got only {frames} frames."
        );

        assert_eq!(
            sessions.seek(handle, 0),
            READ_OK,
            "Seek to the very start should succeed.",
        );
        let mut start_pts = NO_PTS_NANOS;
        assert_eq!(
            read_skipping_preview(&sessions, handle, &mut dst, 0, &mut start_pts),
            READ_OK,
            "First read after seeking to start should produce a frame.",
        );
        assert!(
            start_pts != NO_PTS_NANOS && start_pts < target_nanos,
            "Post-start-seek frame should be near the beginning, got {start_pts}.",
        );

        sessions.close(handle);
    }

    #[test]
    fn seek_within_cache_window_replays_from_ring() {
        let Some(clip) = generate_clip("dd-lav-cache-seek-test", &[]) else {
            return;
        };
        let sessions = LavSessions::new();
        let handle = sessions.open(clip.to_str().unwrap(), 640, 360, 0, 0);
        assert_ne!(handle, 0, "Open failed.");
        assert_eq!(sessions.enable_cache(handle, 60_000_000_000, 64 << 20), READ_OK);

        let mut dst = vec![0u8; 640 * 360 * 3 / 2];
        let mut pts = NO_PTS_NANOS;
        while pts < 3_000_000_000 {
            assert_eq!(
                sessions.read_frame_with_pts(handle, &mut dst, &mut pts),
                READ_OK,
                "Playback before the cache seek should not end.",
            );
        }

        let target_nanos = 1_500_000_000_i64;
        assert_eq!(sessions.seek(handle, target_nanos / 1_000), READ_OK);
        let mut first_pts = NO_PTS_NANOS;
        assert_eq!(
            read_skipping_preview(&sessions, handle, &mut dst, target_nanos, &mut first_pts),
            READ_OK,
            "First read after the cache seek should produce a frame.",
        );
        assert!(
            first_pts != NO_PTS_NANOS
                && first_pts + SEEK_PREROLL_TOLERANCE_NANOS >= target_nanos
                && first_pts <= target_nanos + 500_000_000,
            "Cache-served seek should land near the target, got {first_pts} for {target_nanos}.",
        );

        let mut probe = [0u8; 0];
        assert!(
            sessions.snapshot(handle, &mut probe) > 0,
            "Ring should survive a cache-served seek.",
        );

        for _ in 0..30 {
            let mut p = NO_PTS_NANOS;
            assert_eq!(sessions.read_frame_with_pts(handle, &mut dst, &mut p), READ_OK);
            assert!(p >= first_pts, "Post-seek PTS should be monotonic.");
        }
        sessions.close(handle);
    }

    fn generate_clip(dir_name: &str, extra_args: &[&str]) -> Option<std::path::PathBuf> {
        let ffmpeg_bin = std::env::var("DD_TEST_FFMPEG").unwrap_or_else(|_| "ffmpeg".into());
        let dir = std::env::temp_dir().join(dir_name);
        std::fs::create_dir_all(&dir).unwrap();
        let clip = dir.join("clip.mp4");
        let mut args = vec![
            "-y", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30:duration=4",
            "-pix_fmt", "yuv420p", "-g", "30",
        ];
        args.extend_from_slice(extra_args);
        let clip_str = clip.to_str().unwrap().to_owned();
        args.push(&clip_str);
        let status = std::process::Command::new(&ffmpeg_bin).args(&args).status().ok()?;
        status.success().then_some(clip)
    }

    #[test]
    fn seek_to_start_with_shifted_timestamps() {
        let Some(clip) = generate_clip(
            "dd-lav-shifted-ts-test",
            &["-output_ts_offset", "10", "-movflags", "frag_keyframe+empty_moov"],
        ) else {
            return;
        };

        let sessions = LavSessions::new();
        let handle = sessions.open(clip.to_str().unwrap(), 640, 360, 0, 0);
        assert_ne!(handle, 0, "Open failed.");

        let mut dst = vec![0u8; 640 * 360 * 3 / 2];
        for _ in 0..5 {
            assert_eq!(sessions.read_frame(handle, &mut dst), READ_OK);
        }

        assert_eq!(
            sessions.seek(handle, 0),
            READ_OK,
            "Seek to 0 on a shifted-timestamp fragmented stream must succeed.",
        );
        assert_eq!(
            sessions.read_frame(handle, &mut dst),
            READ_OK,
            "Decode should resume after seeking to the start.",
        );

        sessions.close(handle);
    }

    #[test]
    fn interrupted_read_is_distinct_from_eof() {
        let Some(clip) = generate_clip("dd-lav-interrupt-test", &[]) else {
            return;
        };

        let sessions = LavSessions::new();
        let handle = sessions.open(clip.to_str().unwrap(), 640, 360, 0, 0);
        assert_ne!(handle, 0, "Open failed.");

        let mut dst = vec![0u8; 640 * 360 * 3 / 2];
        assert_eq!(sessions.read_frame(handle, &mut dst), READ_OK);

        sessions.kill(handle);
        assert_eq!(
            sessions.read_frame(handle, &mut dst),
            READ_INTERRUPTED,
            "A killed read must be INTERRUPTED, not EOF.",
        );

        assert_eq!(sessions.seek(handle, 0), READ_OK);
        assert_eq!(
            sessions.read_frame(handle, &mut dst),
            READ_OK,
            "Decode should resume after the interrupt is cleared by a seek.",
        );

        sessions.close(handle);
    }
}
