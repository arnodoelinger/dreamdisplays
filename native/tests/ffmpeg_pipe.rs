//! End-to-end smoke test against a real `FFmpeg` binary: spawns `testsrc`, reads frames
//! through the session machinery, and checks EOF + exit-code handling.
//!
//! Skips (passes trivially) when no FFmpeg binary is available. Point `DD_TEST_FFMPEG`
//! at a binary explicitly, otherwise a few well-known locations are probed.

use dreamdisplays_native::session::{PixFmt, Sessions, READ_EOF, READ_OK};

fn find_ffmpeg() -> Option<String> {
    if let Ok(p) = std::env::var("DD_TEST_FFMPEG") {
        if std::path::Path::new(&p).is_file() {
            return Some(p);
        }
    }
    let candidates = [
        "../platform/client/fabric/run/dreamdisplays/ffmpeg/macos-aarch64/ffmpeg",
        "/opt/homebrew/bin/ffmpeg",
        "/usr/local/bin/ffmpeg",
        "/usr/bin/ffmpeg",
    ];
    candidates
        .iter()
        .find(|p| std::path::Path::new(p).is_file())
        .map(|p| p.to_string())
}

fn run_pipe(pix: PixFmt, vf: &str, frames: u32) {
    let Some(ffmpeg) = find_ffmpeg() else {
        eprintln!("Skipping: no FFmpeg binary found.");
        return;
    };
    let (w, h) = (64u32, 48u32);
    let args: Vec<String> = [
        &ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        &format!("testsrc=duration=1:size={w}x{h}:rate={frames}"),
        "-vf",
        vf,
        "-f",
        "rawvideo",
        "-",
    ]
        .iter()
        .map(|s| s.to_string())
        .collect();

    let sessions = Sessions::new();
    let handle = sessions.open(&args, w, h, pix);
    assert_ne!(handle, 0, "Ffmpeg failed to spawn.");

    let mut dst = vec![0u8; (w * h * 3) as usize];
    let mut got = 0u32;
    loop {
        match sessions.read_frame(handle, &mut dst, 1000) {
            READ_OK => got += 1,
            READ_EOF => break,
            rc => panic!(
                "read_frame failed: {rc}, stderr: {}",
                stderr_of(&sessions, handle)
            ),
        }
    }
    assert_eq!(
        got,
        frames,
        "frame count mismatch; stderr: {}",
        stderr_of(&sessions, handle)
    );
    assert_eq!(sessions.exit_code(handle, 2000), 0);
    sessions.close(handle);
}

fn run_pipe_rgba(pix: PixFmt, vf: &str, frames: u32) {
    let Some(ffmpeg) = find_ffmpeg() else {
        eprintln!("Skipping: no FFmpeg binary found.");
        return;
    };
    let (w, h) = (64u32, 48u32);
    let args: Vec<String> = [
        &ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        &format!("testsrc=duration=1:size={w}x{h}:rate={frames}"),
        "-vf",
        vf,
        "-f",
        "rawvideo",
        "-",
    ]
        .iter()
        .map(|s| s.to_string())
        .collect();

    let sessions = Sessions::new();
    let handle = sessions.open(&args, w, h, pix);
    assert_ne!(handle, 0, "Ffmpeg failed to spawn.");

    let mut dst = vec![0u8; (w * h * 4) as usize];
    let mut got = 0u32;
    loop {
        match sessions.read_frame_rgba(handle, &mut dst, 1000) {
            READ_OK => {
                got += 1;
                assert!(dst.chunks_exact(4).all(|px| px[3] == 0xff));
            }
            READ_EOF => break,
            rc => panic!(
                "read_frame_rgba failed: {rc}, stderr: {}",
                stderr_of(&sessions, handle)
            ),
        }
    }
    assert_eq!(
        got,
        frames,
        "frame count mismatch; stderr: {}",
        stderr_of(&sessions, handle)
    );
    assert_eq!(sessions.exit_code(handle, 2000), 0);
    sessions.close(handle);
}

fn stderr_of(sessions: &Sessions, handle: i64) -> String {
    let mut buf = vec![0u8; 4096];
    let n = sessions.stderr(handle, &mut buf).max(0) as usize;
    String::from_utf8_lossy(&buf[..n]).into_owned()
}

#[test]
fn nv12_pipe_end_to_end() {
    run_pipe(PixFmt::Nv12, "format=nv12", 10);
}

#[test]
fn rgb24_pipe_end_to_end() {
    run_pipe(PixFmt::Rgb24, "format=rgb24", 10);
}

#[test]
fn nv12_pipe_rgba_end_to_end() {
    run_pipe_rgba(PixFmt::Nv12, "format=nv12", 10);
}

#[test]
fn rgb24_pipe_rgba_end_to_end() {
    run_pipe_rgba(PixFmt::Rgb24, "format=rgb24", 10);
}

#[test]
fn kill_unblocks_reader() {
    let Some(ffmpeg) = find_ffmpeg() else {
        eprintln!("Skipping: no FFmpeg binary found.");
        return;
    };
    // Infinite source: the reader would block forever unless kill() unblocks it
    let (w, h) = (64u32, 48u32);
    let args: Vec<String> = [
        &ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        &format!("testsrc=size={w}x{h}:rate=5"),
        "-vf",
        "format=nv12",
        "-f",
        "rawvideo",
        "-",
    ]
        .iter()
        .map(|s| s.to_string())
        .collect();

    let sessions = std::sync::Arc::new(Sessions::new());
    let handle = sessions.open(&args, w, h, PixFmt::Nv12);
    assert_ne!(handle, 0);

    let s2 = std::sync::Arc::clone(&sessions);
    let reader = std::thread::spawn(move || {
        let mut dst = vec![0u8; (w * h * 3) as usize];
        loop {
            if s2.read_frame(handle, &mut dst, 1000) != READ_OK {
                break;
            }
        }
    });

    std::thread::sleep(std::time::Duration::from_millis(300));
    sessions.kill(handle);
    reader
        .join()
        .expect("Reader thread must terminate after kill().");
    sessions.close(handle);
}
