//! In-process libav decode backend, shipped as its own cdylib so the main
//! `dreamdisplays_native` library stays free of libav link dependencies (this one fails to
//! load on machines without the `FFmpeg` shared libraries, and the JVM treats that as
//! "feature unavailable" instead of losing the whole native pipeline).
//!
//! One session replaces the video `FFmpeg` process: libavformat reads the network stream,
//! libavcodec decodes (VideoToolbox / D3D11VA / VAAPI / CUDA where available, software otherwise),
//! libswscale aspect-fits into the target size, and the frame lands in the caller's direct
//! buffer as tightly packed I420 — the same wire format `dd_video_read_frame_i420` produces,
//! so the JVM render path is identical from there on.
//!
//! The additive surface ABI keeps decoder hardware frames alive and lets the render thread import
//! their planes into platform GL textures. macOS VideoToolbox is implemented through
//! CVPixelBuffer/IOSurface/CGLTexImageIOSurface2D; unsupported platforms/formats cleanly fall back
//! to the I420 path above.
//!
//! ABI mirrors the main library's conventions: panic-safe entry points, opaque `i64`
//! handles, blocking reads unblocked by `dd_lav_kill`.

pub mod acoustics;
pub mod audio;
pub mod cache;
pub mod session;
pub mod surface;

use audio::{AUDIO_ABI_VERSION, AudioSessions};
use session::{ERR_BAD_ARGS, ERR_IO, LavSessions, NO_PTS_NANOS};
use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::OnceLock;
use surface::{LavSurfaceDesc, SURFACE_ABI_VERSION};

/// Bumped on any breaking change of this ABI.
pub const LAV_ABI_VERSION: u32 = 5;

/// Global state, one per process.
static SESSIONS: OnceLock<LavSessions> = OnceLock::new();

/// Global audio-session state, separate from [`SESSIONS`] since every audio track is its own
/// independent session (own demuxer, own decode, own output-device stream) — see `audio` module.
static AUDIO_SESSIONS: OnceLock<AudioSessions> = OnceLock::new();

/// Returns the global state.
fn sessions() -> &'static LavSessions {
    dreamdisplays_logging::init();
    SESSIONS.get_or_init(LavSessions::new)
}

/// Returns the global audio-session state.
fn audio_sessions() -> &'static AudioSessions {
    dreamdisplays_logging::init();
    AUDIO_SESSIONS.get_or_init(AudioSessions::new)
}

/// Fallback for a caught panic in an ABI entry point: logs the panic message (panics must never
/// cross the C boundary silently) and substitutes `code` as the return value.
fn on_panic<T: Copy>(entry: &'static str, code: T) -> impl FnOnce(Box<dyn Any + Send>) -> T {
    move |payload| {
        log::error!(
            "{entry} panicked: {}.",
            dreamdisplays_logging::panic_message(&*payload)
        );
        code
    }
}

/// Returns [`LAV_ABI_VERSION`]; the JVM bridge calls this first as a sanity check.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_abi_version() -> u32 {
    dreamdisplays_logging::init();
    LAV_ABI_VERSION
}

/// Returns the optional hardware-surface ABI version. This ABI is additive to the I420 path:
/// callers can probe it and fall back to [`dd_lav_read_frame_i420`] without reopening.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_surface_abi_version() -> u32 {
    SURFACE_ABI_VERSION
}

/// Opens an in-process decode session for the UTF-8 `url` and returns a handle (0 on failure).
///
/// `w`/`h` are the target I420 dimensions (frames are aspect-fitted and padded with black),
/// `start_micros` is the initial seek position. `hw_accel` is a stable backend code:
/// 0 = software only, 1 = auto, 2 = VideoToolbox, 3 = D3D11VA, 4 = VAAPI, 5 = CUDA.
/// Hardware setup is best-effort; every backend falls back to software decode.
///
/// Safety: `url` must point to `url_len` readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_open(
    url: *const u8,
    url_len: u64,
    w: u32,
    h: u32,
    start_micros: i64,
    hw_accel: u32,
) -> i64 {
    if url.is_null() || url_len == 0 || w == 0 || h == 0 {
        return 0;
    }
    let bytes = unsafe {
        // Safety: the caller guarantees url points to url_len readable bytes; null and
        // zero-length inputs are rejected above.
        std::slice::from_raw_parts(url, url_len as usize)
    };
    catch_unwind(AssertUnwindSafe(|| {
        let url = String::from_utf8_lossy(bytes).into_owned();
        sessions().open(&url, w as usize, h as usize, start_micros, hw_accel)
    }))
    .unwrap_or_else(on_panic("dd_lav_open", 0))
}

/// Opens a replay decode session from a serialized packet-ring snapshot.
///
/// `resume_nanos` is the normalized playback timestamp to resume at; decoding starts from the
/// nearest cached keyframe at or before it, and pre-roll frames older than it are discarded before
/// they reach the caller. The returned handle is read with [`dd_lav_read_frame_i420_pts`] and closed
/// with [`dd_lav_close`], same as a live session.
///
/// Safety: `blob` must point to `blob_len` readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_open_replay(
    blob: *const u8,
    blob_len: u64,
    w: u32,
    h: u32,
    resume_nanos: i64,
) -> i64 {
    if blob.is_null() || blob_len == 0 || w == 0 || h == 0 {
        return 0;
    }
    let bytes = unsafe {
        // Safety: the caller guarantees blob points to blob_len readable bytes; null and
        // zero-length inputs are rejected above.
        std::slice::from_raw_parts(blob, blob_len as usize)
    };
    catch_unwind(AssertUnwindSafe(|| {
        sessions().open_replay(bytes, w as usize, h as usize, resume_nanos)
    }))
    .unwrap_or_else(on_panic("dd_lav_open_replay", 0))
}

/// Blocking decode of the next frame into `dst` as tightly packed I420 (Y, then U, then V),
/// aspect-fitted into the session's target size with black padding. No color conversion or
/// brightness is applied; both happen in the display's fragment shader.
///
/// Returns 0 on success, 1 on EOF, negative on error.
///
/// Safety: `dst` must point to `dst_len` writable bytes for the duration of the call.
/// Only one thread may read a given handle at a time.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_read_frame_i420(handle: i64, dst: *mut u8, dst_len: u64) -> i32 {
    if dst.is_null() {
        return ERR_BAD_ARGS;
    }
    let dst = unsafe {
        // Safety: the caller guarantees dst points to dst_len writable bytes for this call
        std::slice::from_raw_parts_mut(dst, dst_len as usize)
    };
    catch_unwind(AssertUnwindSafe(|| sessions().read_frame(handle, dst)))
        .unwrap_or_else(on_panic("dd_lav_read_frame_i420", ERR_IO))
}

/// Blocking decode of the next frame into `dst` as I420 and writes the frame's normalized
/// playback timestamp in nanoseconds to `pts_nanos`. When libav cannot provide a timestamp,
/// `pts_nanos` is set to `i64::MIN` and the caller should keep its synthetic FPS clock.
///
/// This is an additive ABI entry point; [`dd_lav_read_frame_i420`] stays available for older
/// JVM bridges and simple callers.
///
/// Safety: `dst` must point to `dst_len` writable bytes for the duration of the call. If
/// `pts_nanos` is non-null, it must point to one writable `i64`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_read_frame_i420_pts(
    handle: i64,
    dst: *mut u8,
    dst_len: u64,
    pts_nanos: *mut i64,
) -> i32 {
    if dst.is_null() {
        return ERR_BAD_ARGS;
    }
    if !pts_nanos.is_null() {
        unsafe {
            // Safety: non-null pts_nanos is guaranteed by the caller to point to one writable
            // i64 for the duration of this call.
            *pts_nanos = NO_PTS_NANOS;
        }
    }
    let dst = unsafe {
        // Safety: the caller guarantees dst points to dst_len writable bytes for this call
        std::slice::from_raw_parts_mut(dst, dst_len as usize)
    };
    catch_unwind(AssertUnwindSafe(|| {
        let mut pts = NO_PTS_NANOS;
        let rc = sessions().read_frame_with_pts(handle, dst, &mut pts);
        if !pts_nanos.is_null() {
            unsafe {
                // Safety: non-null pts_nanos is guaranteed by the caller to point to one
                // writable i64 for the duration of this call.
                *pts_nanos = pts;
            }
        }
        rc
    }))
    .unwrap_or_else(on_panic("dd_lav_read_frame_i420_pts", ERR_IO))
}

/// Seeks a live in-process decode session to `target_micros` (AV_TIME_BASE / microseconds),
/// flushes decoder buffers, and resumes future reads from the requested position. Returns 0 on
/// success or a negative error code. Replay sessions are not seekable.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_seek(handle: i64, target_micros: i64) -> i32 {
    catch_unwind(AssertUnwindSafe(|| sessions().seek(handle, target_micros)))
        .unwrap_or_else(on_panic("dd_lav_seek", ERR_IO))
}

/// Blocking decode of the next hardware frame as a retained GPU-importable surface.
///
/// On success, `desc->handle` is a surface handle owned by the caller. Release it with
/// [`dd_lav_release_surface`] after the render thread has imported/bound the planes it needs.
/// Returns 0 on success, 1 on EOF, negative on error or unsupported platform/format.
///
/// Safety: `desc` must point to writable memory for one [`LavSurfaceDesc`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_read_surface(handle: i64, desc: *mut LavSurfaceDesc) -> i32 {
    if desc.is_null() {
        return ERR_BAD_ARGS;
    }
    let desc = unsafe {
        // Safety: the caller guarantees desc points to writable memory for one
        // LavSurfaceDesc; null is rejected above.
        &mut *desc
    };
    catch_unwind(AssertUnwindSafe(|| sessions().read_surface(handle, desc)))
        .unwrap_or_else(on_panic("dd_lav_read_surface", ERR_IO))
}

/// Imports one retained surface plane into an existing OpenGL texture object.
///
/// The call must run on the render thread with the destination OpenGL context current. The
/// texture object must match the descriptor's `texture_target` (currently GL_TEXTURE_RECTANGLE
/// for macOS IOSurface).
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_bind_surface_plane_gl(
    surface_handle: i64,
    plane: u32,
    texture_id: u32,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        sessions().bind_surface_plane_gl(surface_handle, plane, texture_id)
    }))
    .unwrap_or_else(on_panic("dd_lav_bind_surface_plane_gl", ERR_IO))
}

/// Releases a surface returned by [`dd_lav_read_surface`]. Safe to call with 0 or stale handles.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_release_surface(surface_handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        sessions().release_surface(surface_handle)
    }));
}

/// Copies the session's last error description (UTF-8) into `dst`; returns bytes written
/// or a negative error code.
///
/// Safety: `dst` must point to `dst_len` writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_error(handle: i64, dst: *mut u8, dst_len: u64) -> i32 {
    if dst.is_null() {
        return ERR_BAD_ARGS;
    }
    let dst = unsafe {
        // Safety: the caller guarantees dst points to dst_len writable bytes for this call
        std::slice::from_raw_parts_mut(dst, dst_len as usize)
    };
    catch_unwind(AssertUnwindSafe(|| sessions().error(handle, dst)))
        .unwrap_or_else(on_panic("dd_lav_error", ERR_IO))
}

/// Enables the rolling encoded-packet cache on `handle`: retains roughly the most recent
/// `window_ms` of stream (capped at `max_bytes`) for an instant, network-free resume on display
/// reappearance. Capture starts with the next demuxed packet.
///
/// `window_ms = 0` or `max_bytes = 0` is a no-op. Returns 0 on success, negative on a bad handle.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_enable_cache(handle: i64, window_ms: u32, max_bytes: u64) -> i32 {
    if window_ms == 0 || max_bytes == 0 {
        return ERR_BAD_ARGS;
    }
    let window_nanos = (window_ms as i64).saturating_mul(1_000_000);
    let max_bytes = max_bytes.min(usize::MAX as u64) as usize;
    catch_unwind(AssertUnwindSafe(|| {
        sessions().enable_cache(handle, window_nanos, max_bytes)
    }))
    .unwrap_or_else(on_panic("dd_lav_enable_cache", ERR_IO))
}

/// Copies the cache snapshot for `handle` into `dst` and returns the total blob length. When the
/// return value exceeds `dst_len` nothing was copied: size a buffer to the returned length and call
/// again (pass `dst_len = 0` first to query the size). Returns 0 when no cache/data is present.
///
/// The blob is self-contained (codec params + keyframe-aligned packets); the JVM retains it across a
/// soft unload and later hands it to a replay session.
///
/// Safety: `dst` must point to `dst_len` writable bytes (or be null when `dst_len == 0`).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_ring_snapshot(handle: i64, dst: *mut u8, dst_len: u64) -> i32 {
    let dst_slice: &mut [u8] = if dst.is_null() || dst_len == 0 {
        &mut []
    } else {
        unsafe {
            // Safety: non-null dst is guaranteed by the caller to point to dst_len writable
            // bytes for this call.
            std::slice::from_raw_parts_mut(dst, dst_len as usize)
        }
    };
    catch_unwind(AssertUnwindSafe(|| sessions().snapshot(handle, dst_slice)))
        .unwrap_or_else(on_panic("dd_lav_ring_snapshot", ERR_IO))
}

/// Like [`dd_lav_ring_snapshot`], but the native side first tops the packet ring up toward
/// `position_nanos + cache_window` by demuxing ahead. This is intended for display unload, where
/// mutating the live demuxer is safe because the session is about to be closed.
///
/// Safety: `dst` must point to `dst_len` writable bytes (or be null when `dst_len == 0`).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_ring_snapshot_at(
    handle: i64,
    position_nanos: i64,
    dst: *mut u8,
    dst_len: u64,
) -> i32 {
    let dst_slice: &mut [u8] = if dst.is_null() || dst_len == 0 {
        &mut []
    } else {
        unsafe {
            // Safety: non-null dst is guaranteed by the caller to point to dst_len writable
            // bytes for this call.
            std::slice::from_raw_parts_mut(dst, dst_len as usize)
        }
    };
    let top_up = dst_len == 0;
    catch_unwind(AssertUnwindSafe(|| {
        sessions().snapshot_at(handle, position_nanos, dst_slice, top_up)
    }))
    .unwrap_or_else(on_panic("dd_lav_ring_snapshot_at", ERR_IO))
}

/// Interrupts the session's network/decode loop, unblocking any reader stuck in
/// [`dd_lav_read_frame_i420`]. The handle stays valid until [`dd_lav_close`].
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_kill(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| sessions().kill(handle)));
}

/// Frees the session. Must not be called while another thread is inside
/// [`dd_lav_read_frame_i420`] for the same handle (join the reader thread first).
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_close(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| sessions().close(handle)));
}

/// Returns [`AUDIO_ABI_VERSION`]; callers probe this before using any `dd_lav_audio_*` function.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_abi_version() -> u32 {
    AUDIO_ABI_VERSION
}

/// Opens a direct-URL audio session — decode and cpal playback both start immediately — and
/// returns a handle (0 on failure). `start_micros` is the initial seek position
/// (`AV_TIME_BASE`/microseconds), or 0 to start from the beginning.
///
/// Safety: `url` must point to `url_len` readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_audio_open(url: *const u8, url_len: u64, start_micros: i64) -> i64 {
    if url.is_null() || url_len == 0 {
        return 0;
    }
    let bytes = unsafe {
        // Safety: the caller guarantees url points to url_len readable bytes; null / zero-length
        // inputs are rejected above.
        std::slice::from_raw_parts(url, url_len as usize)
    };
    catch_unwind(AssertUnwindSafe(|| {
        let url = String::from_utf8_lossy(bytes).into_owned();
        audio_sessions().open(&url, start_micros)
    }))
    .unwrap_or_else(on_panic("dd_lav_audio_open", 0))
}

/// Opens an HLS (VOD or live, fMP4-safe) audio session, decoded segment-by-segment. `start_nanos`
/// is the initial position in nanoseconds into the playlist (0 = live edge for a live playlist,
/// or the start for VOD).
///
/// Safety: `playlist_url` must point to `playlist_url_len` readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_audio_open_hls(
    playlist_url: *const u8,
    playlist_url_len: u64,
    start_nanos: i64,
) -> i64 {
    if playlist_url.is_null() || playlist_url_len == 0 {
        return 0;
    }
    let bytes = unsafe {
        // Safety: the caller guarantees playlist_url points to playlist_url_len readable bytes;
        // null / zero-length inputs are rejected above.
        std::slice::from_raw_parts(playlist_url, playlist_url_len as usize)
    };
    catch_unwind(AssertUnwindSafe(|| {
        let url = String::from_utf8_lossy(bytes).into_owned();
        audio_sessions().open_hls(&url, start_nanos)
    }))
    .unwrap_or_else(on_panic("dd_lav_audio_open_hls", 0))
}

/// Opens a reappearance bridge: plays `prelude` (raw interleaved f32 PCM at `prelude_sample_rate`/
/// `prelude_channels`, typically from a prior [`dd_lav_audio_snapshot_pcm`] call) immediately, then
/// blocks waiting for [`dd_lav_audio_provide_live`] to supply a URL and continues on it —
/// sample-continuous, same session, same acoustics chain state. Returns a handle, or 0 on failure.
///
/// Safety: `prelude` must point to `prelude_len` readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_audio_open_bridge(
    prelude: *const u8,
    prelude_len: u64,
    prelude_sample_rate: u32,
    prelude_channels: u32,
    live_edge_nanos: i64,
) -> i64 {
    let bytes: Vec<u8> = if prelude.is_null() || prelude_len == 0 {
        Vec::new()
    } else {
        let slice = unsafe {
            // Safety: non-null prelude is guaranteed by the caller to point to prelude_len
            // readable bytes.
            std::slice::from_raw_parts(prelude, prelude_len as usize)
        };
        slice.to_vec()
    };
    catch_unwind(AssertUnwindSafe(|| {
        audio_sessions().open_bridge(bytes, prelude_sample_rate.max(1), prelude_channels.max(1), live_edge_nanos)
    }))
    .unwrap_or_else(on_panic("dd_lav_audio_open_bridge", 0))
}

/// Supplies a bridge session's live URL once it is known, unblocking [`dd_lav_audio_open_bridge`]'s
/// wait. Returns 0 on success, negative on a bad handle.
///
/// Safety: `url` must point to `url_len` readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_audio_provide_live(handle: i64, url: *const u8, url_len: u64) -> i32 {
    if url.is_null() || url_len == 0 {
        return ERR_BAD_ARGS;
    }
    let bytes = unsafe {
        // Safety: the caller guarantees url points to url_len readable bytes; null / zero-length
        // inputs are rejected above.
        std::slice::from_raw_parts(url, url_len as usize)
    };
    catch_unwind(AssertUnwindSafe(|| {
        let url = String::from_utf8_lossy(bytes).into_owned();
        audio_sessions().provide_live(handle, &url)
    }))
    .unwrap_or_else(on_panic("dd_lav_audio_provide_live", ERR_IO))
}

/// Copies up to the most recent `dst_len / 4` samples of `handle`'s raw (pre-DSP, pre-volume)
/// interleaved PCM cache into `dst` as packed f32 LE, and writes the sample rate / channel count
/// they were captured at into `sample_rate_out` / `channels_out` (needed to correctly hand this
/// blob to a future [`dd_lav_audio_open_bridge`] call, since the default output device — and so
/// the capture format — is not guaranteed to stay the same across a reappearance gap). Returns the
/// number of bytes written.
///
/// Safety: `dst` must point to `dst_len` writable bytes; `sample_rate_out` / `channels_out`, when
/// non-null, must each point to one writable `u32`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_audio_snapshot_pcm(
    handle: i64,
    dst: *mut u8,
    dst_len: u64,
    sample_rate_out: *mut u32,
    channels_out: *mut u32,
) -> i32 {
    if dst.is_null() {
        return ERR_BAD_ARGS;
    }
    let dst = unsafe {
        // Safety: the caller guarantees dst points to dst_len writable bytes for this call
        std::slice::from_raw_parts_mut(dst, dst_len as usize)
    };
    let max_samples = dst.len() / 4;
    let (samples, sample_rate, channels) = catch_unwind(AssertUnwindSafe(|| {
        let sessions = audio_sessions();
        let samples = sessions.snapshot_pcm(handle, max_samples);
        let (sample_rate, channels) = sessions.device_format(handle);
        (samples, sample_rate, channels)
    }))
    .unwrap_or_else(|payload| {
        log::error!(
            "dd_lav_audio_snapshot_pcm panicked: {}.",
            dreamdisplays_logging::panic_message(&*payload)
        );
        (Vec::new(), 0, 0)
    });
    if !sample_rate_out.is_null() {
        unsafe {
            // Safety: non-null sample_rate_out is guaranteed by the caller to point to one
            // writable u32.
            *sample_rate_out = sample_rate;
        }
    }
    if !channels_out.is_null() {
        unsafe {
            // Safety: non-null channels_out is guaranteed by the caller to point to one writable
            // u32.
            *channels_out = channels;
        }
    }
    let bytes = unsafe {
        // Safety: any &[f32] is validly reinterpreted as &[u8] of 4x the length
        std::slice::from_raw_parts(samples.as_ptr().cast::<u8>(), samples.len() * 4)
    };
    let n = bytes.len().min(dst.len());
    dst[..n].copy_from_slice(&bytes[..n]);
    n as i32
}

/// Repositions `handle`. For a direct-URL session this is `AV_TIME_BASE` / microseconds handed to
/// the demuxer; for an HLS session this is nanoseconds into the playlist, used to pick a
/// different starting segment (never asks a demuxer to seek, so this is fMP4-safe). Returns 0 on
/// success (the seek itself is asynchronous — it lands on the decode thread's next loop
/// iteration), negative on a bad handle.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_seek(handle: i64, target: i64) -> i32 {
    catch_unwind(AssertUnwindSafe(|| audio_sessions().seek(handle, target)))
        .unwrap_or_else(on_panic("dd_lav_audio_seek", ERR_IO))
}

/// Sets linear playback gain (0.0 = silent, 1.0 = unity); applied per-sample in the real-time
/// output callback, so changes take effect within one device buffer, not one Kotlin-side chunk.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_set_volume(handle: i64, volume: f32) -> i32 {
    catch_unwind(AssertUnwindSafe(|| audio_sessions().set_volume(handle, volume)))
        .unwrap_or_else(on_panic("dd_lav_audio_set_volume", ERR_IO))
}

/// Pauses the output stream without closing it or losing decode progress.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_pause(handle: i64) -> i32 {
    catch_unwind(AssertUnwindSafe(|| audio_sessions().pause(handle)))
        .unwrap_or_else(on_panic("dd_lav_audio_pause", ERR_IO))
}

/// Resumes a session paused via [`dd_lav_audio_pause`].
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_resume(handle: i64) -> i32 {
    catch_unwind(AssertUnwindSafe(|| audio_sessions().resume(handle)))
        .unwrap_or_else(on_panic("dd_lav_audio_resume", ERR_IO))
}

/// Playback position in nanoseconds, derived from frames actually handed to the output device
/// (silence included) — the clock the JVM master clock reads to pace video.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_position_nanos(handle: i64) -> i64 {
    catch_unwind(AssertUnwindSafe(|| audio_sessions().position_nanos(handle))).unwrap_or(0)
}

/// Copies the session's last error description (UTF-8) into `dst`; returns bytes written or a
/// negative error code.
///
/// Safety: `dst` must point to `dst_len` writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_audio_error(handle: i64, dst: *mut u8, dst_len: u64) -> i32 {
    if dst.is_null() {
        return ERR_BAD_ARGS;
    }
    let dst = unsafe {
        // Safety: the caller guarantees dst points to dst_len writable bytes for this call
        std::slice::from_raw_parts_mut(dst, dst_len as usize)
    };
    catch_unwind(AssertUnwindSafe(|| audio_sessions().error(handle, dst)))
        .unwrap_or_else(on_panic("dd_lav_audio_error", ERR_IO))
}

/// Interrupts the session's decode loop, unblocking a reader stuck on a network read. The handle
/// stays valid until [`dd_lav_audio_close`].
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_kill(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| audio_sessions().kill(handle)));
}

/// Frees the audio session: stops the output stream, signals the decode thread to exit, and
/// joins it before returning. Blocking; do not call from the cpal callback's own thread.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_close(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| audio_sessions().close(handle)));
}

/// Publishes the listener's current world pose, shared by every audio session's acoustics chain.
///
/// Safety: `pose` must point to one readable [`acoustics::ListenerPoseFfi`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_audio_set_listener(pose: *const acoustics::ListenerPoseFfi) {
    if pose.is_null() {
        return;
    }
    let pose = unsafe {
        // Safety: the caller guarantees pose points to one readable ListenerPoseFfi; null is
        // rejected above.
        &*pose
    };
    let _ = catch_unwind(AssertUnwindSafe(|| acoustics::globals().set_listener(pose.into())));
}

/// Sets the global acoustics quality ceiling: 0 = off, 1 = basic, 2 = advanced, 3 = ultra.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_set_quality(tier: i32) {
    let _ = catch_unwind(AssertUnwindSafe(|| acoustics::globals().set_quality(tier)));
}

/// Selects binaural (headphone) rendering vs. constant-power stereo pan for every acoustics-active
/// audio session.
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_audio_set_binaural(enabled: i32) {
    let _ = catch_unwind(AssertUnwindSafe(|| acoustics::globals().set_binaural(enabled != 0)));
}

/// Publishes the latest geometry / mix state for `handle`'s acoustics chain, or clears it back to
/// the legacy-gain bypass when `state` is null. Returns 0 on success, negative on a bad handle.
///
/// Safety: when non-null, `state` must point to one readable [`acoustics::AcousticStateFfi`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dd_lav_audio_set_acoustics(handle: i64, state: *const acoustics::AcousticStateFfi) -> i32 {
    let owned = if state.is_null() {
        None
    } else {
        let state = unsafe {
            // Safety: non-null state is guaranteed by the caller to point to one readable
            // AcousticStateFfi.
            &*state
        };
        Some(state.into())
    };
    catch_unwind(AssertUnwindSafe(|| audio_sessions().set_acoustics(handle, owned)))
        .unwrap_or_else(on_panic("dd_lav_audio_set_acoustics", ERR_IO))
}
