//! End-to-end smoke test against a real `FFmpeg` binary.

use dreamdisplays_native::convert;
use dreamdisplays_native::session::{READ_EOF, READ_OK, Sessions};

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

fn test_args(ffmpeg: &str, w: u32, h: u32, rate: u32) -> Vec<String> {
    [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        &format!("testsrc=duration=1:size={w}x{h}:rate={rate}"),
        "-vf",
        "format=nv12",
        "-f",
        "rawvideo",
        "-",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect()
}

/// Reads every frame of a 1 s test source through `read`, checking each with `check`.
fn run_pipe(frame_bytes: usize, read: fn(&Sessions, i64, &mut [u8]) -> i32, check: fn(&[u8])) {
    let Some(ffmpeg) = find_ffmpeg() else {
        eprintln!("Skipping: no FFmpeg binary found.");
        return;
    };
    let (w, h, frames) = (64u32, 48u32, 10u32);
    let sessions = Sessions::new();
    let handle = sessions.open(&test_args(&ffmpeg, w, h, frames), w, h);
    assert_ne!(handle, 0, "Ffmpeg failed to spawn.");

    let mut dst = vec![0u8; frame_bytes];
    let mut got = 0u32;
    loop {
        match read(&sessions, handle, &mut dst) {
            READ_OK => {
                got += 1;
                check(&dst);
            }
            READ_EOF => break,
            rc => panic!(
                "read failed: {rc}, stderr: {}",
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
fn nv12_pipe_rgba_end_to_end() {
    run_pipe(
        64 * 48 * 4,
        |s, h, dst| s.read_frame_rgba(h, dst, 1000),
        |dst| assert!(dst.chunks_exact(4).all(|px| px[3] == 0xff)),
    );
}

#[test]
fn nv12_pipe_i420_end_to_end() {
    run_pipe(
        convert::nv12_frame_size(64, 48),
        |s, h, dst| s.read_frame_i420(h, dst),
        |_| {},
    );
}

#[test]
fn kill_unblocks_reader() {
    let Some(ffmpeg) = find_ffmpeg() else {
        eprintln!("Skipping: no FFmpeg binary found.");
        return;
    };
    // Infinite source: the reader would block forever unless kill() unblocks it
    let (w, h) = (64u32, 48u32);
    let args: Vec<String> = test_args(&ffmpeg, w, h, 5)
        .into_iter()
        .map(|a| {
            if a.starts_with("testsrc=") {
                format!("testsrc=size={w}x{h}:rate=5")
            } else {
                a
            }
        })
        .collect();

    let sessions = std::sync::Arc::new(Sessions::new());
    let handle = sessions.open(&args, w, h);
    assert_ne!(handle, 0);

    let s2 = std::sync::Arc::clone(&sessions);
    let reader = std::thread::spawn(move || {
        let mut dst = vec![0u8; (w * h * 4) as usize];
        loop {
            if s2.read_frame_rgba(handle, &mut dst, 1000) != READ_OK {
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
