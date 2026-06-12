//! C ABI surface for the Dream Displays native media pipeline, consumed from Kotlin via
//! the Java FFM API (Project Panama).
//!
//! Design rules:
//! - Every entry point is panic-safe (`catch_unwind`); panics become error codes, never UB.
//! - Handles are opaque `i64` values; 0 is "no handle".
//! - The reader contract mirrors the JVM `VideoFramePipe` loop: `dd_video_read_frame`
//!   blocks until a full frame is read from the FFmpeg pipe, converts it to tightly
//!   packed RGB24 with brightness applied, and writes it into the caller's buffer.
//! - `dd_video_kill` unblocks a stuck reader; `dd_video_close` frees the session and
//!   must only be called after the reader thread has been joined.

pub mod convert;
pub mod session;

use session::{PixFmt, Sessions, ERR_BAD_ARGS, ERR_BAD_HANDLE, ERR_IO};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::OnceLock;

/// Bumped on any breaking change of this ABI. The JVM bridge refuses to use a library
/// whose version does not match what it was compiled against.
pub const ABI_VERSION: u32 = 1;

static SESSIONS: OnceLock<Sessions> = OnceLock::new();

fn sessions() -> &'static Sessions {
    SESSIONS.get_or_init(Sessions::new)
}

/// Returns [`ABI_VERSION`]; the JVM bridge calls this first as a sanity check.
#[no_mangle]
pub extern "C" fn dd_abi_version() -> u32 {
    ABI_VERSION
}

/// Spawns an FFmpeg process and returns a session handle (0 on failure).
///
/// `argv_blob` is `blob_len` bytes of UTF-8: every argument (including the binary path
/// as the first one) terminated by `\0`. `pix_fmt`: 0 = rgb24, 1 = nv12.
///
/// Safety: `argv_blob` must point to `blob_len` readable bytes.
#[no_mangle]
pub unsafe extern "C" fn dd_video_open(
    argv_blob: *const u8,
    blob_len: u64,
    w: u32,
    h: u32,
    pix_fmt: u32,
) -> i64 {
    if argv_blob.is_null() || blob_len == 0 {
        return 0;
    }
    let blob = std::slice::from_raw_parts(argv_blob, blob_len as usize);
    catch_unwind(AssertUnwindSafe(|| {
        let pix = match PixFmt::from_u32(pix_fmt) {
            Some(p) => p,
            None => return 0,
        };
        let args: Vec<String> = blob
            .split(|&b| b == 0)
            .filter(|part| !part.is_empty())
            .map(|part| String::from_utf8_lossy(part).into_owned())
            .collect();
        sessions().open(&args, w, h, pix)
    }))
    .unwrap_or(0)
}

/// Blocking read of the next frame into `dst` as RGB24 with brightness applied
/// (`brightness_milli` = brightness * 1000, 1000 = unchanged).
///
/// Returns 0 on success, 1 on EOF, negative on error (see `session.rs` codes).
///
/// Safety: `dst` must point to `dst_len` writable bytes and remain valid for the duration of
/// the call. Only one thread may read a given handle at a time.
#[no_mangle] pub unsafe extern "C" fn dd_video_read_frame(
    handle: i64,
    dst: *mut u8,
    dst_len: u64,
    brightness_milli: u32,
) -> i32 {
    if dst.is_null() {
        return ERR_BAD_ARGS;
    }
    let dst = std::slice::from_raw_parts_mut(dst, dst_len as usize);
    catch_unwind(AssertUnwindSafe(|| {
        sessions().read_frame(handle, dst, brightness_milli)
    }))
    .unwrap_or(ERR_IO)
}

/// Copies the captured FFmpeg stderr (UTF-8, capped) into `dst`; returns bytes written
/// or a negative error code.
///
/// Safety: `dst` must point to `dst_len` writable bytes.
#[no_mangle] pub unsafe extern "C" fn dd_video_stderr(handle: i64, dst: *mut u8, dst_len: u64) -> i32 {
    if dst.is_null() {
        return ERR_BAD_ARGS;
    }
    let dst = std::slice::from_raw_parts_mut(dst, dst_len as usize);
    catch_unwind(AssertUnwindSafe(|| sessions().stderr(handle, dst))).unwrap_or(ERR_IO)
}

/// Waits up to `wait_millis` for the `FFmpeg` process to exit; returns its exit code or -1
/// (killing it if it is still running after the timeout).
#[no_mangle] pub extern "C" fn dd_video_exit_code(handle: i64, wait_millis: u32) -> i32 {
    catch_unwind(AssertUnwindSafe(|| sessions().exit_code(handle, wait_millis)))
        .unwrap_or(ERR_BAD_HANDLE)
}

/// Kills the `FFmpeg` process, unblocking any reader stuck in [`dd_video_read_frame`].
/// The handle stays valid (for stderr / exit-code queries) until [`dd_video_close`].
#[no_mangle] pub extern "C" fn dd_video_kill(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| sessions().kill(handle)));
}

/// Frees the session. Must not be called while another thread is inside
/// [`dd_video_read_frame`] for the same handle (join the reader thread first).
#[no_mangle] pub extern "C" fn dd_video_close(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| sessions().close(handle)));
}
