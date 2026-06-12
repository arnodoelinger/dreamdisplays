//! Throughput sanity check for the conversion kernel (run with --ignored).

use dreamdisplays_native::convert;

#[test]
#[ignore]
fn bench_nv12_1080p() {
    bench_nv12_case(1280, 720, 1000, false);
    bench_nv12_case(1280, 720, 900, false);
    bench_nv12_case(1920, 1080, 1000, false);
    bench_nv12_case(1920, 1080, 900, false);
    bench_nv12_case(3840, 2160, 1000, false);
    bench_nv12_case(3840, 2160, 900, false);
    bench_nv12_case(1280, 720, 1000, true);
    bench_nv12_case(1280, 720, 900, true);
    bench_nv12_case(1920, 1080, 1000, true);
    bench_nv12_case(1920, 1080, 900, true);
    bench_nv12_case(3840, 2160, 1000, true);
    bench_nv12_case(3840, 2160, 900, true);
}

fn bench_nv12_case(w: usize, h: usize, brightness_milli: u32, rgba: bool) {
    let raw = vec![128u8; convert::nv12_frame_size(w, h)];
    let mut dst = vec![0u8; w * h * if rgba { 4 } else { 3 }];
    let lut = convert::build_lut(brightness_milli);
    for _ in 0..10 {
        if rgba && convert::lut_is_identity(brightness_milli) {
            convert::nv12_to_rgba32_identity(&raw, w, h, &mut dst);
        } else if rgba {
            convert::nv12_to_rgba32(&raw, w, h, &mut dst, &lut);
        } else if convert::lut_is_identity(brightness_milli) {
            convert::nv12_to_rgb24_identity(&raw, w, h, &mut dst);
        } else {
            convert::nv12_to_rgb24(&raw, w, h, &mut dst, &lut);
        }
    }
    let n = 200;
    let t = std::time::Instant::now();
    for _ in 0..n {
        if rgba && convert::lut_is_identity(brightness_milli) {
            convert::nv12_to_rgba32_identity(&raw, w, h, &mut dst);
        } else if rgba {
            convert::nv12_to_rgba32(&raw, w, h, &mut dst, &lut);
        } else if convert::lut_is_identity(brightness_milli) {
            convert::nv12_to_rgb24_identity(&raw, w, h, &mut dst);
        } else {
            convert::nv12_to_rgb24(&raw, w, h, &mut dst, &lut);
        }
    }
    let per = t.elapsed() / n;
    let fmt = if rgba { "RGBA32" } else { "RGB24" };
    println!(
        "NV12 -> {fmt} {w} x {h} brightness={brightness_milli}: {:?} / frame ({:.0} fps kernel).",
        per,
        1.0 / per.as_secs_f64()
    );
}
