//! In-process libav decode backend.

pub mod cache;
pub mod chunked;
pub mod scale;
pub mod session;

use session::{ERR_BAD_ARGS, ERR_IO, LavSessions, NO_PTS_NANOS};
use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::OnceLock;

/// Bumped on any breaking change of this ABI.
pub const LAV_ABI_VERSION: u32 = 6;

/// Global state, one per process.
static SESSIONS: OnceLock<LavSessions> = OnceLock::new();

/// Returns the global state.
fn sessions() -> &'static LavSessions {
    dreamdisplays_logging::init();
    SESSIONS.get_or_init(LavSessions::new)
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
/// aspect-fitted into the session's target size with black padding, and writes the frame's
/// normalized playback timestamp in nanoseconds to `pts_nanos`. When libav cannot provide a
/// timestamp, `pts_nanos` is set to `i64::MIN` and the caller should keep its synthetic FPS clock.
///
/// Returns 0 on success, 1 on EOF, negative on error. Only one thread may read a handle at a time.
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
/// again (pass `dst_len = 0` first to query the size). Packets start at the keyframe at or before
/// `position_nanos`; returns 0 when no cache data is present.
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
    catch_unwind(AssertUnwindSafe(|| {
        sessions().snapshot_at(handle, position_nanos, dst_slice)
    }))
    .unwrap_or_else(on_panic("dd_lav_ring_snapshot_at", ERR_IO))
}

/// Interrupts the session's network / decode loop, unblocking any reader stuck in
/// [`dd_lav_read_frame_i420_pts`]. The handle stays valid until [`dd_lav_close`].
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_kill(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| sessions().kill(handle)));
}

/// Frees the session. Must not be called while another thread is inside
/// [`dd_lav_read_frame_i420_pts`] for the same handle (join the reader thread first).
#[unsafe(no_mangle)]
pub extern "C" fn dd_lav_close(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| sessions().close(handle)));
}
