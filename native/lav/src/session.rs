//! In-process decode sessions: avformat network input, avcodec decode (VideoToolbox,
//! D3D11VA/DXVA2, VAAPI, or CUDA when requested and available; software otherwise),
//! swscale aspect-fit, I420 output.
//!
//! One mutex-guarded reader per session, mirroring the process-based sessions in the main
//! native library. The output contract matches `dd_video_read_frame_i420`: Y plane, then
//! deinterleaved U and V quarter planes, aspect-fitted into the target size on black.

use anyhow::{Context as AnyhowContext, Result, anyhow, bail};
use log::{debug, error, info, warn};
use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, Once};
use std::{mem, ptr};

use ffmpeg::ffi;
use ffmpeg::format::Pixel;
use ffmpeg::format::context::Input;
use ffmpeg::media::Type;
use ffmpeg::software::scaling;
use ffmpeg::util::frame::video::Video as VideoFrame;
use ffmpeg::util::log::Level;
use ffmpeg::{Dictionary, codec};
use ffmpeg_next as ffmpeg;

use crate::cache::{CachedPacket, CodecParams, PacketRing, packets_from_position};
use crate::surface::{ERR_UNSUPPORTED, LavSurfaceDesc, LavSurfaceFrame, LavSurfaceTable};

/// Read result codes shared with the JVM bridge (mirror the main library).
pub const READ_OK: i32 = 0;
pub const READ_EOF: i32 = 1;
pub const ERR_BAD_HANDLE: i32 = -1;
pub const ERR_BAD_ARGS: i32 = -2;
pub const ERR_IO: i32 = -3;
pub const NO_PTS_NANOS: i64 = i64::MIN;

const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
                          (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

const SEEK_PREROLL_TOLERANCE_NANOS: i64 = 50_000_000;

/// Limited-range black for the padding borders.
const BLACK_Y: u8 = 16;
const BLACK_C: u8 = 128;

static FFMPEG_LOG_INIT: Once = Once::new();

/// libav I/O interrupt callback: returns non-zero to abort a blocked read. The opaque pointer is the
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

fn init_ffmpeg() -> Result<()> {
    ffmpeg::init().context("initialize libav")?;
    FFMPEG_LOG_INIT.call_once(|| {
        let level = if log::log_enabled!(log::Level::Debug) {
            Level::Warning
        } else {
            Level::Error
        };
        ffmpeg::util::log::set_level(level);
    });
    Ok(())
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
    /// Keeps the selected hardware format/device alive for libavcodec callbacks.
    _hw_selection: Option<Box<HwSelection>>,
    time_base: ffmpeg::Rational,
    /// Cached scaler, rebuilt when the source format or size changes mid-stream.
    scaler: Option<(Pixel, u32, u32, scaling::Context)>,
    /// Scratch frame for hardware -> system memory transfers.
    sw_frame: VideoFrame,
    /// Scaled YUV420P output frame.
    scaled: VideoFrame,
    draining: bool,
    /// Live seek target in normalized nanoseconds. Frames substantially before this target are
    /// decoded only as pre-roll and are not returned to the JVM.
    seek_target_nanos: Option<i64>,
    source: PacketSource,
}

// The libav state holds raw pointers that are not Send by default. Access is serialized by
// the mutex (single reader contract), and sessions are only dropped after the reader thread
// has been joined, so moving the state between threads is safe.
unsafe impl Send for ReadState {}

impl ReadState {
    /// Returns the live stream start timestamp; replay packets are already normalized.
    fn stream_start_time(&self) -> Option<i64> {
        match &self.source {
            PacketSource::Live {
                stream_start_time, ..
            } => *stream_start_time,
            PacketSource::Replay { .. } => None,
        }
    }

    /// True when a replay frame is keyframe pre-roll before the requested resume point.
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
    w: usize,
    h: usize,
    read: Mutex<ReadState>,
    /// Rolling packet cache, owned by the session rather than the decode state so a snapshot can be
    /// taken with a short, contention-free lock that never waits on a blocked network read.
    ring: Mutex<Option<PacketRing>>,
    interrupted: Arc<AtomicBool>,
    error: Mutex<String>,
    /// Decoder parameters captured at open, needed to rebuild a decoder for replay (Phase 3).
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

    /// Opens the stream and registers a session. Returns the new handle, or 0 on failure
    /// (the cause is logged).
    pub fn open(&self, url: &str, w: usize, h: usize, start_micros: i64, hw_accel: u32) -> i64 {
        match LavSession::open(url, w, h, start_micros, hw_accel) {
            Ok(session) => {
                let handle = self.insert(session);
                info!(
                    "Opened LAV session #{handle}: {} ({w}x{h}, start {} ms).",
                    url_for_log(url),
                    start_micros / 1_000,
                );
                handle
            }
            Err(e) => {
                error!("Failed to open LAV session for {}: {e:#}.", url_for_log(url));
                0
            }
        }
    }

    /// Opens a replay session from a serialized packet-ring snapshot.
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

    /// Registers a newly opened session and returns its opaque handle.
    fn insert(&self, session: LavSession) -> i64 {
        let handle = self.next.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut map) = self.map.lock() {
            map.insert(handle, Arc::new(session));
            handle
        } else {
            0
        }
    }

    /// Blocking decode of the next frame into `dst` as I420. See `dd_lav_read_frame_i420`.
    pub fn read_frame(&self, handle: i64, dst: &mut [u8]) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        session.read_frame(dst)
    }

    /// Blocking decode of the next frame into `dst` as I420 and returns normalized frame PTS.
    pub fn read_frame_with_pts(&self, handle: i64, dst: &mut [u8], pts_nanos: &mut i64) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        session.read_frame_with_pts(dst, pts_nanos)
    }

    /// Seeks a live session in place and flushes decoder state.
    pub fn seek(&self, handle: i64, target_micros: i64) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        match session.seek(target_micros.max(0)) {
            Ok(()) => {
                debug!(
                    "LAV session #{handle} sought to {} ms.",
                    target_micros / 1_000
                );
                READ_OK
            }
            Err(e) => {
                warn!(
                    "LAV session #{handle} seek to {} ms failed: {e:#}.",
                    target_micros / 1_000
                );
                if let Ok(mut err) = session.error.lock() {
                    *err = format!("{e:#}.");
                }
                ERR_IO
            }
        }
    }

    /// Blocking decode of the next hardware frame and registers it as a retained GPU-importable surface.
    pub fn read_surface(&self, handle: i64, desc: &mut LavSurfaceDesc) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        match session.read_surface() {
            Ok(Some(surface)) => self.surfaces.insert(surface, desc),
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

    /// Imports one retained surface plane into the OpenGL texture object supplied by the render thread.
    pub fn bind_surface_plane_gl(&self, surface_handle: i64, plane: u32, texture_id: u32) -> i32 {
        self.surfaces
            .bind_plane_gl(surface_handle, plane, texture_id)
    }

    /// Releases a retained hardware surface returned by [`read_surface`].
    pub fn release_surface(&self, surface_handle: i64) {
        self.surfaces.release(surface_handle);
    }

    /// Copies the last error description into `dst`, returning the number of bytes written.
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

    /// Enables / resizes the rolling packet cache on `handle`. See [`LavSession::enable_cache`].
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

    /// Copies the cache snapshot for `handle` into `dst`, returning the total blob length (which
    /// may exceed `dst.len()`, in which case nothing is copied — the caller sizes its buffer and
    /// retries). Returns 0 when no cache is active or it is empty.
    pub fn snapshot(&self, handle: i64, dst: &mut [u8]) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        let blob = session.snapshot();
        if blob.len() <= dst.len() {
            dst[..blob.len()].copy_from_slice(&blob);
        }
        // Length comfortably fits i32 for any sane window; clamp defensively
        blob.len().min(i32::MAX as usize) as i32
    }

    /// Copies a position-aware cache snapshot for `handle`, topping up the live demuxer before
    /// serialization so replay has packets after the resume point.
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

    /// Flags the session as interrupted; the reader loop exits between packets.
    pub fn kill(&self, handle: i64) {
        if let Some(session) = self.get(handle) {
            debug!("Interrupting LAV session #{handle}");
            session.interrupted.store(true, Ordering::Relaxed);
        }
    }

    /// Removes the session from the table, dropping all libav state.
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

        let mut opts = Dictionary::new();
        opts.set("user_agent", USER_AGENT);
        opts.set("headers", "Referer: https://www.youtube.com/\r\n");

        #[cfg(not(test))]
        opts.set("protocol_whitelist", "https,tls,tcp,crypto,data,http");
        #[cfg(test)]
        opts.set("protocol_whitelist", "https,tls,tcp,crypto,data,http,file");

        opts.set("reconnect", "1");
        opts.set("reconnect_streamed", "1");
        opts.set("reconnect_delay_max", "10");
        opts.set("reconnect_on_network_error", "1");
        opts.set("reconnect_on_http_error", "5xx");
        opts.set("rw_timeout", "15000000");

        // Single-video HTTP sources don't need the default ~5 MB / 5 s stream probe; the same
        // tightened window as the external ffmpeg path (`-probesize 1M -analyzeduration 1000000
        // -fflags nobuffer`) shaves most of the blocking open time.
        opts.set("probesize", "1048576");
        opts.set("analyzeduration", "1000000");
        opts.set("fflags", "nobuffer");

        let mut ictx =
            ffmpeg::format::input_with_dictionary(&url, opts).context("open input stream")?;

        // Route blocked network I/O through an interrupt callback so a kill() / teardown aborts the
        // current read promptly instead of waiting out the 15 s rw_timeout.
        let interrupted = Arc::new(AtomicBool::new(false));
        unsafe {
            let p = ictx.as_mut_ptr();
            (*p).interrupt_callback.callback = Some(interrupt_cb);
            (*p).interrupt_callback.opaque = Arc::as_ptr(&interrupted) as *mut c_void;
        }

        if start_micros > 0 {
            // AV_TIME_BASE units; backward flag picks the keyframe at or before the target
            ictx.seek(start_micros, ..start_micros)
                .with_context(|| format!("initial seek to {} ms", start_micros / 1_000))?;
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
        let (decoder, hw_selection) =
            open_video_decoder(&parameters, time_base, HwAccelRequest::from_code(hw_accel))
                .context("open video decoder")?;

        Ok(LavSession {
            w,
            h,
            read: Mutex::new(ReadState {
                decoder,
                _hw_selection: hw_selection,
                time_base,
                scaler: None,
                sw_frame: VideoFrame::empty(),
                scaled: VideoFrame::empty(),
                draining: false,
                seek_target_nanos: start_micros.checked_mul(1_000).filter(|_| start_micros > 0),
                source: PacketSource::Live {
                    ictx,
                    stream_index,
                    stream_start_time,
                },
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
            w,
            h,
            read: Mutex::new(ReadState {
                decoder,
                _hw_selection: hw_selection,
                time_base,
                scaler: None,
                sw_frame: VideoFrame::empty(),
                scaled: VideoFrame::empty(),
                draining: false,
                seek_target_nanos: None,
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

    /// Enables (or resizes) the rolling packet cache: retain up to `window_nanos` of stream, capped
    /// at `max_bytes`. Capture begins with the next demuxed packet. Idempotent.
    fn enable_cache(&self, window_nanos: i64, max_bytes: usize) {
        if let Ok(mut ring) = self.ring.lock() {
            *ring = Some(PacketRing::new(window_nanos, max_bytes));
        }
    }

    /// Serializes the current cache (codec params + retained packets from the first keyframe) into a
    /// blob the JVM can retain across a soft unload, or returns an empty Vec when no cache is active.
    fn snapshot(&self) -> Vec<u8> {
        self.snapshot_at(i64::MIN, false)
    }

    /// Serializes a position-aware snapshot from the rolling packet ring. The ring lives behind its
    /// own short-held mutex (never the blocking decode lock), so this returns promptly even while the
    /// reader thread is parked on a network read — fixing the empty-snapshot / teardown-freeze races.
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

    /// Mirrors a freshly demuxed live packet into the rolling cache, if one is active. Holds the ring
    /// lock only for the push, so it never blocks a concurrent snapshot for more than that.
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
        self.read_frame_with_pts(dst, &mut pts_nanos)
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
            Ok(Some(pts)) => {
                *pts_nanos = pts;
                READ_OK
            }
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

    fn seek(&self, target_micros: i64) -> Result<()> {
        self.interrupted.store(false, Ordering::Relaxed);
        let mut state = self
            .read
            .lock()
            .map_err(|_| anyhow!("LAV reader lock poisoned."))?;
        match &mut state.source {
            PacketSource::Live { ictx, .. } => {
                // AV_TIME_BASE units; backward flag picks the keyframe at or before the target
                ictx.seek(target_micros, ..target_micros)
                    .with_context(|| format!("demuxer seek to {} ms.", target_micros / 1_000))?;
                let _ = ictx.play();
                unsafe {
                    ffi::avcodec_flush_buffers(state.decoder.as_mut_ptr());
                    ffi::av_frame_unref(state.sw_frame.as_mut_ptr());
                    ffi::av_frame_unref(state.scaled.as_mut_ptr());
                }
                state.seek_target_nanos = target_micros.checked_mul(1_000);
                state.draining = false;
                if let Ok(mut ring) = self.ring.lock() {
                    if let Some(ring) = ring.as_mut() {
                        ring.clear();
                    }
                }
                Ok(())
            }
            PacketSource::Replay { .. } => bail!("replay sessions are not seekable."),
        }
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

    /// Pulls packets until one frame is decoded and written to `dst`. Returns Ok(false) on EOF.
    fn next_frame(&self, state: &mut ReadState, dst: &mut [u8]) -> Result<Option<i64>> {
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
                    continue;
                }
                state.seek_target_nanos = None;
            }
            self.write_i420(state, &decoded, dst)?;
            return Ok(Some(pts_nanos));
        }
    }

    /// Pulls packets until one decoded frame is available. Returns Ok(None) on EOF or interruption.
    fn receive_frame(&self, state: &mut ReadState) -> Result<Option<VideoFrame>> {
        let mut decoded = VideoFrame::empty();
        loop {
            if self.interrupted.load(Ordering::Relaxed) {
                return Ok(None);
            }

            if state.decoder.receive_frame(&mut decoded).is_ok() {
                return Ok(Some(decoded));
            }
            if state.draining {
                return Ok(None);
            }

            let time_base = state.time_base;
            match &mut state.source {
                PacketSource::Live {
                    ictx,
                    stream_index,
                    stream_start_time,
                } => {
                    let mut packet = ffmpeg::Packet::empty();
                    match packet.read(ictx) {
                        Ok(()) => {
                            if packet.stream() == *stream_index {
                                self.capture_packet(time_base, *stream_start_time, &packet);
                                state
                                    .decoder
                                    .send_packet(&packet)
                                    .context("send demuxed packet to decoder.")?;
                            }
                        }
                        Err(ffmpeg::Error::Eof) => {
                            state.decoder.send_eof().context("send EOF to decoder.")?;
                            state.draining = true;
                        }
                        Err(ffmpeg::Error::Other { errno })
                            if errno == ffmpeg::util::error::EAGAIN => {}
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
                        let packet = packet_from_cached(&packets[*next_packet], state.time_base);
                        *next_packet += 1;
                        state
                            .decoder
                            .send_packet(&packet)
                            .context("send cached packet to replay decoder.")?;
                    }
                }
            }
        }
    }

    /// Downloads (if hardware), scales to fit, and writes `frame` into `dst` as padded I420.
    fn write_i420(&self, state: &mut ReadState, frame: &VideoFrame, dst: &mut [u8]) -> Result<()> {
        // Hardware frames live outside normal CPU memory; pull them down to the best software
        // format FFmpeg can provide before scaling to the target I420 frame.
        let src: &VideoFrame = if is_hardware_frame(frame.format()) {
            unsafe {
                ffi::av_frame_unref(state.sw_frame.as_mut_ptr());
                let rc =
                    ffi::av_hwframe_transfer_data(state.sw_frame.as_mut_ptr(), frame.as_ptr(), 0);
                if rc < 0 {
                    return Err(ffmpeg::Error::from(rc))
                        .context("transfer hardware frame to system memory.");
                }
            }
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
            Some((f, w0, h0, ctx)) => {
                *f != format
                    || *w0 != sw
                    || *h0 != sh
                    || ctx.output().width != fw
                    || ctx.output().height != fh
            }
            None => true,
        };
        if rebuild {
            let ctx = scaling::Context::get(
                format,
                sw,
                sh,
                Pixel::YUV420P,
                fw,
                fh,
                scaling::Flags::FAST_BILINEAR,
            )
            .with_context(|| format!("create swscale context {format:?} {sw} x {sh} -> {fw} x {fh}."))?;
            state.scaler = Some((format, sw, sh, ctx));
        }
        let scaler = &mut state.scaler.as_mut().unwrap().3;
        scaler.run(src, &mut state.scaled).context("scale frame")?;

        // Compose into the caller's buffer: black background, fitted frame centered
        let (tw, th) = (self.w, self.h);
        let cw = (tw + 1) / 2;
        let ch = (th + 1) / 2;
        let y_size = tw * th;
        let c_size = cw * ch;
        dst[..y_size].fill(BLACK_Y);
        dst[y_size..y_size + 2 * c_size].fill(BLACK_C);

        // Even offsets keep luma and chroma alignment consistent
        let x0 = ((tw - fw as usize) / 2) & !1;
        let y0 = ((th - fh as usize) / 2) & !1;

        copy_plane(
            state.scaled.data(0),
            state.scaled.stride(0),
            fw as usize,
            fh as usize,
            &mut dst[..y_size],
            tw,
            x0,
            y0,
        );
        let (u_dst, v_dst) = dst[y_size..y_size + 2 * c_size].split_at_mut(c_size);
        copy_plane(
            state.scaled.data(1),
            state.scaled.stride(1),
            fw as usize / 2,
            fh as usize / 2,
            u_dst,
            cw,
            x0 / 2,
            y0 / 2,
        );
        copy_plane(
            state.scaled.data(2),
            state.scaled.stride(2),
            fw as usize / 2,
            fh as usize / 2,
            v_dst,
            cw,
            x0 / 2,
            y0 / 2,
        );
        Ok(())
    }
}

/// Rebuilds an encoded packet from cached payload plus normalized timestamps.
fn packet_from_cached(cached: &CachedPacket, time_base: ffmpeg::Rational) -> ffmpeg::Packet {
    let mut packet = ffmpeg::Packet::copy(&cached.data);
    packet.set_pts(nanos_to_ticks(cached.pts_nanos, time_base));
    packet.set_dts(nanos_to_ticks(cached.dts_nanos, time_base));
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

/// Copies a `w` x `h` plane from strided `src` into a tightly packed `dst` plane of width
/// `dst_w`, at offset (`x0`, `y0`).
fn copy_plane(
    src: &[u8],
    stride: usize,
    w: usize,
    h: usize,
    dst: &mut [u8],
    dst_w: usize,
    x0: usize,
    y0: usize,
) {
    for row in 0..h {
        let s = &src[row * stride..row * stride + w];
        let d_start = (y0 + row) * dst_w + x0;
        dst[d_start..d_start + w].copy_from_slice(s);
    }
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

    /// Decodes a locally generated test clip end-to-end and checks frame count and padding.
    /// Requires the FFmpeg CLI to generate the input (skipped when unavailable).
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

    /// Captures packets while decoding a local clip, then snapshots and re-parses the cache blob.
    /// Requires the FFmpeg CLI to generate the input (skipped when unavailable).
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
        // Keep the whole clip so the assertions are deterministic
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

        // Query size, then fill
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
        // PTS should be monotonic and normalized (>= 0 from the start)
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

    /// Replays a captured snapshot from the middle and discards keyframe pre-roll.
    /// Requires the FFmpeg CLI to generate the input (skipped when unavailable).
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

    /// Seeks an already-open live demuxer and verifies that decode resumes near the target instead
    /// of returning the old keyframe pre-roll as the first presentable frame.
    /// Requires the FFmpeg CLI to generate the input (skipped when unavailable).
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
            sessions.read_frame_with_pts(handle, &mut dst, &mut first_pts),
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
        sessions.close(handle);
    }
}
