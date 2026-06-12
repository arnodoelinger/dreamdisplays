//! Pixel conversion kernels: brightness LUT and NV12 -> RGB24 (BT.709 limited range).
//!
//! All loops are written branch-free over contiguous slices so that rustc/LLVM can
//! auto-vectorize them. Throughput on a single core comfortably exceeds what a
//! 1080p60 stream needs, so no explicit SIMD intrinsics are used (yet).

/// Builds a 256-entry brightness lookup table for `factor = milli / 1000`.
/// `milli` is clamped to `[0, 2000]` (matching the Kotlin-side brightness range 0.0..2.0).
pub fn build_lut(milli: u32) -> [u8; 256] {
    let factor = milli.min(2000) as f32 / 1000.0;
    let mut lut = [0u8; 256];
    for (i, slot) in lut.iter_mut().enumerate() {
        *slot = (i as f32 * factor).round().clamp(0.0, 255.0) as u8;
    }
    lut
}

/// True when `lut` is the identity mapping (brightness == 1.0), letting callers skip the pass.
pub fn lut_is_identity(milli: u32) -> bool {
    milli == 1000
}

/// Copies `src` (RGB24) into `dst` applying the brightness LUT per byte.
/// `dst` must be at least as long as `src`.
pub fn rgb24_with_lut(src: &[u8], dst: &mut [u8], lut: &[u8; 256]) {
    for (d, s) in dst.iter_mut().zip(src.iter()) {
        *d = lut[*s as usize];
    }
}

/// Number of bytes in one NV12 frame of `w` x `h` (chroma plane rounds odd dimensions up).
pub fn nv12_frame_size(w: usize, h: usize) -> usize {
    let cw = w.div_ceil(2);
    let ch = h.div_ceil(2);
    w * h + 2 * cw * ch
}

/// Converts one NV12 frame (`raw`, laid out as Y plane then interleaved UV plane) into
/// tightly packed RGB24 in `dst`, applying the brightness LUT on the way out.
///
/// Uses BT.709 limited-range coefficients; the FFmpeg filter chain pins the stream to
/// BT.709 with `out_color_matrix=bt709`, so this constant matrix is correct by construction.
///
/// `raw.len()` must be >= [`nv12_frame_size`], `dst.len()` must be >= `w * h * 3`.
pub fn nv12_to_rgb24(raw: &[u8], w: usize, h: usize, dst: &mut [u8], lut: &[u8; 256]) {
    let cw = w.div_ceil(2);
    let y_plane = &raw[..w * h];
    let uv_plane = &raw[w * h..];
    let uv_stride = 2 * cw;

    for row in 0..h {
        let y_row = &y_plane[row * w..row * w + w];
        let uv_row = &uv_plane[(row / 2) * uv_stride..(row / 2) * uv_stride + uv_stride];
        let dst_row = &mut dst[row * w * 3..(row + 1) * w * 3];

        for x in 0..w {
            // BT.709 limited range, fixed point with 8 fractional bits:
            // R = 1.1644*C + 1.7927*E; G = 1.1644*C - 0.2132*D - 0.5329*E; B = 1.1644*C + 2.1124*D
            let c = 298 * (y_row[x] as i32 - 16);
            let d = uv_row[2 * (x / 2)] as i32 - 128;
            let e = uv_row[2 * (x / 2) + 1] as i32 - 128;

            // RGB range is [0, 255], so we need to round to [0, 254] and clamp.
            let r = (c + 459 * e + 128) >> 8;
            let g = (c - 55 * d - 136 * e + 128) >> 8;
            let b = (c + 541 * d + 128) >> 8;

            dst_row[3 * x] = lut[r.clamp(0, 255) as usize];
            dst_row[3 * x + 1] = lut[g.clamp(0, 255) as usize];
            dst_row[3 * x + 2] = lut[b.clamp(0, 255) as usize];
        }
    }
}

#[cfg(test)] mod tests {
    use super::*;

    const IDENTITY_MILLI: u32 = 1000;

    fn nv12_frame(w: usize, h: usize, y: u8, u: u8, v: u8) -> Vec<u8> {
        let cw = w.div_ceil(2);
        let ch = h.div_ceil(2);
        let mut raw = vec![y; w * h];
        for _ in 0..cw * ch {
            raw.push(u);
            raw.push(v);
        }
        raw
    }

    #[test] fn lut_identity_is_passthrough() {
        let lut = build_lut(IDENTITY_MILLI);
        for i in 0..=255usize {
            assert_eq!(lut[i], i as u8);
        }
        assert!(lut_is_identity(IDENTITY_MILLI));
    }

    #[test] fn lut_half_brightness_scales_down() {
        let lut = build_lut(500);
        assert_eq!(lut[0], 0);
        assert_eq!(lut[200], 100);
        assert_eq!(lut[255], 128);
    }

    #[test] fn lut_overbright_clamps() {
        let lut = build_lut(2000);
        assert_eq!(lut[200], 255);
    }

    #[test] fn nv12_black_white_gray() {
        let lut = build_lut(IDENTITY_MILLI);
        let mut dst = vec![0u8; 2 * 2 * 3];

        // Limited-range black: Y=16, neutral chroma.
        nv12_to_rgb24(&nv12_frame(2, 2, 16, 128, 128), 2, 2, &mut dst, &lut);
        assert!(dst.iter().all(|&b| b == 0), "black: {dst:?}");

        // Limited-range white: Y=235.
        nv12_to_rgb24(&nv12_frame(2, 2, 235, 128, 128), 2, 2, &mut dst, &lut);
        assert!(dst.iter().all(|&b| b == 255), "white: {dst:?}");

        // Mid gray: Y=126 -> (298*110+128)>>8 = 128.
        nv12_to_rgb24(&nv12_frame(2, 2, 126, 128, 128), 2, 2, &mut dst, &lut);
        assert!(dst.iter().all(|&b| b == 128), "gray: {dst:?}");
    }

    #[test] fn nv12_bt709_red() {
        // Pure red RGB(255,0,0) in BT.709 limited range is approx Y=63, U=102, V=240.
        let lut = build_lut(IDENTITY_MILLI);
        let mut dst = vec![0u8; 2 * 2 * 3];
        nv12_to_rgb24(&nv12_frame(2, 2, 63, 102, 240), 2, 2, &mut dst, &lut);
        let (r, g, b) = (dst[0] as i32, dst[1] as i32, dst[2] as i32);
        assert!((r - 255).abs() <= 3, "r={r}");
        assert!(g.abs() <= 3, "g={g}");
        assert!(b.abs() <= 3, "b={b}");
    }

    #[test] fn nv12_odd_dimensions() {
        // 3x3: chroma plane is 2x2 blocks of UV pairs (stride 4). Must not panic and
        // must produce a fully written 3*3*3 output.
        let lut = build_lut(IDENTITY_MILLI);
        let raw = nv12_frame(3, 3, 126, 128, 128);
        assert_eq!(raw.len(), nv12_frame_size(3, 3));
        let mut dst = vec![0u8; 3 * 3 * 3];
        nv12_to_rgb24(&raw, 3, 3, &mut dst, &lut);
        assert!(dst.iter().all(|&b| b == 128), "{dst:?}");
    }

    #[test] fn rgb24_lut_applies() {
        let lut = build_lut(500);
        let src = [10u8, 100, 200, 255];
        let mut dst = [0u8; 4];
        rgb24_with_lut(&src, &mut dst, &lut);
        assert_eq!(dst, [5, 50, 100, 128]);
    }
}
