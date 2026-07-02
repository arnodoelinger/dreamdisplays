//! FFmpeg child-process sessions: spawn, blocking frame reads, stderr capture, shutdown.
//!
//! The JVM side builds the full argv (it already knows how to construct FFmpeg command
//! lines); this module owns the process so the pipe stays entirely on the native side —
//! the JVM never touches a file descriptor.

use std::collections::HashMap;
use std::io::{ErrorKind, Read};
use std::process::{Child, ChildStdout, Command, Stdio};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::convert;
use anyhow::{Context, Result, anyhow, bail};
use kanal::{Receiver, Sender};
use log::{debug, error, info, warn};

/// Max bytes of `FFmpeg` stderr retained per session (the JVM parses it for retry decisions).
const STDERR_CAP: usize = 128 * 1024;

/// Read result codes shared with the JVM bridge.
pub const READ_OK: i32 = 0;
pub const READ_EOF: i32 = 1;
pub const ERR_BAD_HANDLE: i32 = -1;
pub const ERR_BAD_ARGS: i32 = -2;
pub const ERR_IO: i32 = -3;

/// Wire pixel format coming out of the FFmpeg pipe.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum PixFmt {
    Rgb24,
    Nv12,
}

impl PixFmt {
    pub fn from_u32(v: u32) -> Option<PixFmt> {
        match v {
            0 => Some(PixFmt::Rgb24),
            1 => Some(PixFmt::Nv12),
            _ => None,
        }
    }

    fn frame_size(self, w: usize, h: usize) -> usize {
        match self {
            PixFmt::Rgb24 => w * h * 3,
            PixFmt::Nv12 => convert::nv12_frame_size(w, h),
        }
    }

    /// Human-readable name for log lines.
    fn name(self) -> &'static str {
        match self {
            PixFmt::Rgb24 => "rgb24",
            PixFmt::Nv12 => "nv12",
        }
    }
}

/// Mutable read-side state; locked only by the (single) reader thread.
struct ReadState {
    stdout: Option<ChildStdout>,
    prefetch: Option<Nv12Prefetch>,
    /// Scratch buffer holding one wire-format frame read from the pipe.
    raw: Vec<u8>,
    lut: [u8; 256],
    lut_milli: u32,
}

enum PrefetchedFrame {
    Frame(Vec<u8>),
    Eof,
    Error,
}

struct Nv12Prefetch {
    ready: Receiver<PrefetchedFrame>,
    recycle: Sender<Vec<u8>>,
}

impl Nv12Prefetch {
    fn spawn(mut stdout: ChildStdout, frame_size: usize) -> Option<Nv12Prefetch> {
        let (ready_tx, ready) = kanal::bounded(2);
        let (recycle, recycle_rx) = kanal::bounded(2);
        recycle.send(vec![0u8; frame_size]).ok()?;
        recycle.send(vec![0u8; frame_size]).ok()?;

        std::thread::Builder::new()
            .name("dd-nv12-prefetch".into())
            .spawn(move || {
                while let Ok(mut raw) = recycle_rx.recv() {
                    match read_exact_eof(&mut stdout, &mut raw) {
                        ReadOutcome::Frame => {
                            if ready_tx.send(PrefetchedFrame::Frame(raw)).is_err() {
                                break;
                            }
                        }
                        ReadOutcome::Eof => {
                            let _ = ready_tx.send(PrefetchedFrame::Eof);
                            break;
                        }
                        ReadOutcome::Error => {
                            let _ = ready_tx.send(PrefetchedFrame::Error);
                            break;
                        }
                    }
                }
            })
            .ok()?;

        Some(Nv12Prefetch { ready, recycle })
    }
}

pub struct Session {
    w: usize,
    h: usize,
    pix: PixFmt,
    read: Mutex<ReadState>,
    child: Mutex<Child>,
    stderr: Arc<Mutex<Vec<u8>>>,
}

impl Drop for Session {
    fn drop(&mut self) {
        if let Ok(mut child) = self.child.lock() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

/// Global handle table. Handles are opaque non-zero i64 values given to the JVM.
pub struct Sessions {
    map: Mutex<HashMap<i64, Arc<Session>>>,
    next: AtomicI64,
}

impl Sessions {
    pub fn new() -> Sessions {
        Sessions {
            map: Mutex::new(HashMap::new()),
            next: AtomicI64::new(1),
        }
    }

    fn get(&self, handle: i64) -> Option<Arc<Session>> {
        self.map.lock().ok()?.get(&handle).cloned()
    }

    /// Spawns FFmpeg with `args` (args[0] is the binary path) and registers a session.
    /// Returns the new handle, or 0 if the spawn failed (the cause is logged).
    pub fn open(&self, args: &[String], w: u32, h: u32, pix: PixFmt) -> i64 {
        match self.try_open(args, w, h, pix) {
            Ok(handle) => {
                info!(
                    "Opened FFmpeg session #{handle}: {} ({w} x {h}, {}).",
                    args.first().map(String::as_str).unwrap_or("?"),
                    pix.name(),
                );
                handle
            }
            Err(e) => {
                error!("Failed to open FFmpeg session: {e:#}.");
                0
            }
        }
    }

    /// Fallible body of [`Sessions::open`]; every failure carries context for the log.
    fn try_open(&self, args: &[String], w: u32, h: u32, pix: PixFmt) -> Result<i64> {
        if args.is_empty() {
            bail!("empty argv.");
        }
        if w == 0 || h == 0 {
            bail!("bad target size {w} x {h}.");
        }
        let (w, h) = (w as usize, h as usize);

        let mut cmd = Command::new(&args[0]);
        cmd.args(&args[1..])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        let mut child = cmd
            .spawn()
            .with_context(|| format!("spawn FFmpeg process `{}`.", args[0]))?;
        let Some(stdout) = child.stdout.take() else {
            let _ = child.kill();
            bail!("FFmpeg child has no stdout pipe.");
        };

        let stderr_buf = Arc::new(Mutex::new(Vec::new()));
        if let Some(mut stderr) = child.stderr.take() {
            let buf = Arc::clone(&stderr_buf);
            std::thread::Builder::new()
                .name("dd-ffmpeg-stderr".into())
                .spawn(move || {
                    let mut chunk = [0u8; 4096];
                    loop {
                        match stderr.read(&mut chunk) {
                            Ok(0) | Err(_) => break,
                            Ok(n) => {
                                if let Ok(mut b) = buf.lock() {
                                    let room = STDERR_CAP.saturating_sub(b.len());
                                    b.extend_from_slice(&chunk[..n.min(room)]);
                                }
                            }
                        }
                    }
                })
                .ok();
        }

        let frame_size = pix.frame_size(w, h);
        let (stdout, prefetch) = if pix == PixFmt::Nv12 {
            (None, Nv12Prefetch::spawn(stdout, frame_size))
        } else {
            (Some(stdout), None)
        };
        if pix == PixFmt::Nv12 && prefetch.is_none() {
            let _ = child.kill();
            bail!("failed to start the NV12 prefetch thread.");
        }
        let raw = Vec::new();

        let session = Arc::new(Session {
            w,
            h,
            pix,
            read: Mutex::new(ReadState {
                stdout,
                prefetch,
                raw,
                lut: convert::build_lut(1000),
                lut_milli: 1000,
            }),
            child: Mutex::new(child),
            stderr: stderr_buf,
        });

        let handle = self.next.fetch_add(1, Ordering::Relaxed);
        let mut map = self
            .map
            .lock()
            .map_err(|_| anyhow!("session table lock poisoned."))?;
        map.insert(handle, session);
        Ok(handle)
    }

    /// Blocking read of the next frame, converted to RGB24 with brightness applied.
    /// `dst` must hold at least `w * h * 3` bytes.
    pub fn read_frame(&self, handle: i64, dst: &mut [u8], brightness_milli: u32) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        if dst.len() < session.w * session.h * 3 {
            return ERR_BAD_ARGS;
        }
        let Ok(mut guard) = session.read.lock() else {
            return ERR_IO;
        };
        let state = &mut *guard;

        if state.lut_milli != brightness_milli {
            state.lut = convert::build_lut(brightness_milli);
            state.lut_milli = brightness_milli;
        }

        match session.pix {
            PixFmt::Nv12 => {
                if let Some(prefetch) = &state.prefetch {
                    let raw = match prefetch.ready.recv() {
                        Ok(PrefetchedFrame::Frame(raw)) => raw,
                        Ok(PrefetchedFrame::Eof) => return READ_EOF,
                        Ok(PrefetchedFrame::Error) | Err(_) => return ERR_IO,
                    };
                    if convert::lut_is_identity(brightness_milli) {
                        convert::nv12_to_rgb24_identity(&raw, session.w, session.h, dst);
                    } else {
                        convert::nv12_to_rgb24(&raw, session.w, session.h, dst, &state.lut);
                    }
                    let _ = prefetch.recycle.send(raw);
                } else {
                    let stdout = match state.stdout.as_mut() {
                        Some(stdout) => stdout,
                        None => return ERR_IO,
                    };
                    match read_exact_eof(stdout, &mut state.raw) {
                        ReadOutcome::Frame => {}
                        ReadOutcome::Eof => return READ_EOF,
                        ReadOutcome::Error => return ERR_IO,
                    }
                    if convert::lut_is_identity(brightness_milli) {
                        convert::nv12_to_rgb24_identity(&state.raw, session.w, session.h, dst);
                    } else {
                        convert::nv12_to_rgb24(&state.raw, session.w, session.h, dst, &state.lut);
                    }
                }
            }
            PixFmt::Rgb24 => {
                let n = session.w * session.h * 3;
                let Some(stdout) = state.stdout.as_mut() else {
                    return ERR_IO;
                };
                match read_exact_eof(stdout, &mut dst[..n]) {
                    ReadOutcome::Frame => {}
                    ReadOutcome::Eof => return READ_EOF,
                    ReadOutcome::Error => return ERR_IO,
                }
                if !convert::lut_is_identity(brightness_milli) {
                    convert::rgb24_apply_lut_in_place(&mut dst[..n], &state.lut);
                }
            }
        }
        READ_OK
    }

    /// Blocking read of the next frame as raw I420 planes (Y, then U, then V), with no
    /// color conversion or brightness applied — both happen in the fragment shader.
    /// Only valid for NV12 sessions. `dst` must hold at least [`convert::nv12_frame_size`] bytes.
    pub fn read_frame_i420(&self, handle: i64, dst: &mut [u8]) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        if session.pix != PixFmt::Nv12 || dst.len() < convert::nv12_frame_size(session.w, session.h)
        {
            return ERR_BAD_ARGS;
        }
        let Ok(mut guard) = session.read.lock() else {
            return ERR_IO;
        };
        let state = &mut *guard;

        if let Some(prefetch) = &state.prefetch {
            let raw = match prefetch.ready.recv() {
                Ok(PrefetchedFrame::Frame(raw)) => raw,
                Ok(PrefetchedFrame::Eof) => return READ_EOF,
                Ok(PrefetchedFrame::Error) | Err(_) => return ERR_IO,
            };
            convert::nv12_to_i420(&raw, session.w, session.h, dst);
            let _ = prefetch.recycle.send(raw);
        } else {
            let stdout = match state.stdout.as_mut() {
                Some(stdout) => stdout,
                None => return ERR_IO,
            };
            match read_exact_eof(stdout, &mut state.raw) {
                ReadOutcome::Frame => {}
                ReadOutcome::Eof => return READ_EOF,
                ReadOutcome::Error => return ERR_IO,
            }
            convert::nv12_to_i420(&state.raw, session.w, session.h, dst);
        }
        READ_OK
    }

    /// Blocking read of the next frame, converted to RGBA32 with brightness applied.
    /// `dst` must hold at least `w * h * 4` bytes.
    pub fn read_frame_rgba(&self, handle: i64, dst: &mut [u8], brightness_milli: u32) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        let rgb_len = session.w * session.h * 3;
        let rgba_len = session.w * session.h * 4;
        if dst.len() < rgba_len {
            return ERR_BAD_ARGS;
        }
        let Ok(mut guard) = session.read.lock() else {
            return ERR_IO;
        };
        let state = &mut *guard;

        if state.lut_milli != brightness_milli {
            state.lut = convert::build_lut(brightness_milli);
            state.lut_milli = brightness_milli;
        }

        match session.pix {
            PixFmt::Nv12 => {
                if let Some(prefetch) = &state.prefetch {
                    let raw = match prefetch.ready.recv() {
                        Ok(PrefetchedFrame::Frame(raw)) => raw,
                        Ok(PrefetchedFrame::Eof) => return READ_EOF,
                        Ok(PrefetchedFrame::Error) | Err(_) => return ERR_IO,
                    };
                    if convert::lut_is_identity(brightness_milli) {
                        convert::nv12_to_rgba32_identity(&raw, session.w, session.h, dst);
                    } else {
                        convert::nv12_to_rgba32(&raw, session.w, session.h, dst, &state.lut);
                    }
                    let _ = prefetch.recycle.send(raw);
                } else {
                    let stdout = match state.stdout.as_mut() {
                        Some(stdout) => stdout,
                        None => return ERR_IO,
                    };
                    match read_exact_eof(stdout, &mut state.raw) {
                        ReadOutcome::Frame => {}
                        ReadOutcome::Eof => return READ_EOF,
                        ReadOutcome::Error => return ERR_IO,
                    }
                    if convert::lut_is_identity(brightness_milli) {
                        convert::nv12_to_rgba32_identity(&state.raw, session.w, session.h, dst);
                    } else {
                        convert::nv12_to_rgba32(&state.raw, session.w, session.h, dst, &state.lut);
                    }
                }
            }
            PixFmt::Rgb24 => {
                if state.raw.len() < rgb_len {
                    state.raw.resize(rgb_len, 0);
                }
                let Some(stdout) = state.stdout.as_mut() else {
                    return ERR_IO;
                };
                match read_exact_eof(stdout, &mut state.raw[..rgb_len]) {
                    ReadOutcome::Frame => {}
                    ReadOutcome::Eof => return READ_EOF,
                    ReadOutcome::Error => return ERR_IO,
                }
                if convert::lut_is_identity(brightness_milli) {
                    convert::rgb24_to_rgba32_identity(&state.raw[..rgb_len], &mut dst[..rgba_len]);
                } else {
                    convert::rgb24_to_rgba32(
                        &state.raw[..rgb_len],
                        &mut dst[..rgba_len],
                        &state.lut,
                    );
                }
            }
        }
        READ_OK
    }

    /// Copies captured stderr into `dst`, returning the number of bytes written.
    pub fn stderr(&self, handle: i64, dst: &mut [u8]) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        let Ok(buf) = session.stderr.lock() else {
            return ERR_IO;
        };
        let n = buf.len().min(dst.len());
        dst[..n].copy_from_slice(&buf[..n]);
        n as i32
    }

    /// Waits up to `wait_millis` for the child to exit. Returns the exit code, or -1 if
    /// it had to be killed / the code is unavailable. Mirrors the JVM-side
    /// `waitFor(500ms) -> exitValue / destroyForcibly` sequence.
    pub fn exit_code(&self, handle: i64, wait_millis: u32) -> i32 {
        let Some(session) = self.get(handle) else {
            return ERR_BAD_HANDLE;
        };
        let Ok(mut child) = session.child.lock() else {
            return -1;
        };
        let deadline = Instant::now() + Duration::from_millis(wait_millis as u64);
        loop {
            match child.try_wait() {
                Ok(Some(status)) => {
                    let code = status.code().unwrap_or(-1);
                    debug!("FFmpeg session #{handle} exited with code {code}.");
                    return code;
                }
                Ok(None) => {
                    if Instant::now() >= deadline {
                        warn!(
                            "FFmpeg session #{handle} still running after {wait_millis} ms; killing it."
                        );
                        let _ = child.kill();
                        return -1;
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
                Err(e) => {
                    warn!("Failed to poll FFmpeg session #{handle} exit status: {e}.");
                    return -1;
                }
            }
        }
    }

    /// Kills the child process, unblocking any reader stuck in [`Sessions::read_frame`].
    /// The session stays registered until [`Sessions::close`].
    pub fn kill(&self, handle: i64) {
        if let Some(session) = self.get(handle) {
            debug!("Killing FFmpeg session #{handle}.");
            if let Ok(mut child) = session.child.lock() {
                let _ = child.kill();
            }
        }
    }

    /// Removes the session from the table; the process is killed on drop if still running.
    pub fn close(&self, handle: i64) {
        if let Ok(mut map) = self.map.lock()
            && map.remove(&handle).is_some()
        {
            debug!("Closed FFmpeg session #{handle}.");
        }
    }
}

enum ReadOutcome {
    Frame,
    Eof,
    Error,
}

/// `read_exact` that maps both clean EOF and mid-frame EOF to [`ReadOutcome::Eof`]
/// (the JVM side distinguishes normal/abnormal end via the process exit code).
fn read_exact_eof(stdout: &mut ChildStdout, buf: &mut [u8]) -> ReadOutcome {
    match stdout.read_exact(buf) {
        Ok(()) => ReadOutcome::Frame,
        Err(e) if e.kind() == ErrorKind::UnexpectedEof => ReadOutcome::Eof,
        Err(e) => {
            warn!("FFmpeg frame pipe read failed: {e}.");
            ReadOutcome::Error
        }
    }
}
