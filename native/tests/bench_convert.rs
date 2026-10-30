//! Throughput sanity check for the conversion kernel (run with --ignored).

use dreamdisplays_native::convert;

#[test]
#[ignore]
fn bench_nv12_to_rgba() {
    for (w, h) in [(1280, 720), (1920, 1080), (3840, 2160)] {
        bench_nv12_case(w, h, 1000);
        bench_nv12_case(w, h, 900);
    }
}

fn bench_nv12_case(w: usize, h: usize, brightness_milli: u32) {
    let raw = vec![128u8; convert::nv12_frame_size(w, h)];
    let mut dst = vec![0u8; w * h * 4];
    let lut = convert::build_lut(brightness_milli);
    let mut run = || {
        if convert::lut_is_identity(brightness_milli) {
            convert::nv12_to_rgba32_identity(&raw, w, h, &mut dst);
        } else {
            convert::nv12_to_rgba32(&raw, w, h, &mut dst, &lut);
        }
    };
    (0..10).for_each(|_| run());
    let n = 200;
    let t = std::time::Instant::now();
    (0..n).for_each(|_| run());
    let per = t.elapsed() / n;
    println!(
        "NV12 -> RGBA32 {w} x {h} brightness={brightness_milli}: {:?} / frame ({:.0} fps kernel).",
        per,
        1.0 / per.as_secs_f64()
    );
}
