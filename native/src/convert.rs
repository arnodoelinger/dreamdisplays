//! Pixel conversion kernels: brightness LUT and NV12 -> RGBA32 (BT.709 limited range).

use rayon::prelude::*;
use rayon::{ThreadPool, ThreadPoolBuilder};
use std::sync::OnceLock;

const PARALLEL_PIXEL_THRESHOLD: usize = 1280 * 720;
const MAX_CONVERT_THREADS: usize = 8;

const IDENTITY_LUT: [u8; 256] = {
    let mut lut = [0u8; 256];
    let mut i = 0;
    while i < 256 {
        lut[i] = i as u8;
        i += 1;
    }
    lut
};

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

/// Number of bytes in one NV12 frame of `w` x `h` (chroma plane rounds odd dimensions up).
pub fn nv12_frame_size(w: usize, h: usize) -> usize {
    let cw = w.div_ceil(2);
    let ch = h.div_ceil(2);
    w * h + 2 * cw * ch
}

/// Converts one NV12 frame (`raw`, laid out as Y plane then interleaved UV plane) into
/// tightly packed RGBA32 with alpha fixed at 255, applying the brightness LUT on the way out.
///
/// Uses BT.709 limited-range coefficients; the FFmpeg filter chain pins the stream to
/// BT.709 with `out_color_matrix=bt709`, so this constant matrix is correct by construction.
///
/// `raw.len()` must be >= [`nv12_frame_size`], `dst.len()` must be >= `w * h * 4`.
pub fn nv12_to_rgba32(raw: &[u8], w: usize, h: usize, dst: &mut [u8], lut: &[u8; 256]) {
    nv12_to_rgba::<true>(raw, w, h, dst, lut)
}

/// Converts NV12 to RGBA32 without brightness adjustment.
pub fn nv12_to_rgba32_identity(raw: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    nv12_to_rgba::<false>(raw, w, h, dst, &IDENTITY_LUT)
}

/// Deinterleaves one NV12 frame into tightly packed I420 planes (Y, then U, then V) so
/// each plane can be uploaded to its own single-channel GPU texture. No color conversion
/// or brightness is applied; both happen in the fragment shader on the GPU path.
///
/// `raw.len()` and `dst.len()` must both be >= [`nv12_frame_size`].
pub fn nv12_to_i420(raw: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    let cw = w.div_ceil(2);
    let ch = h.div_ceil(2);
    let y_size = w * h;
    let c_size = cw * ch;

    dst[..y_size].copy_from_slice(&raw[..y_size]);
    let uv = &raw[y_size..y_size + 2 * c_size];
    let (u_plane, rest) = dst[y_size..y_size + 2 * c_size].split_at_mut(c_size);
    let v_plane = rest;
    for (i, pair) in uv.chunks_exact(2).enumerate() {
        u_plane[i] = pair[0];
        v_plane[i] = pair[1];
    }
}

/// Converts one I420 frame (Y, U, V planes as produced by [`nv12_to_i420`]) into RGBA32
/// with alpha fixed at 255 and no brightness adjustment. Used to feed the popout window
/// when the main path keeps frames planar for the GPU.
///
/// `raw.len()` must be >= [`nv12_frame_size`], `dst.len()` must be >= `w * h * 4`.
pub fn i420_to_rgba32_identity(raw: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    let cw = w.div_ceil(2);
    let ch = h.div_ceil(2);
    let y_plane = &raw[..w * h];
    let u_plane = &raw[w * h..w * h + cw * ch];
    let v_plane = &raw[w * h + cw * ch..w * h + 2 * cw * ch];

    for row in 0..h {
        let crow = row / 2;
        for col in 0..w {
            let ccol = col / 2;
            let d = u_plane[crow * cw + ccol] as i32 - 128;
            let e = v_plane[crow * cw + ccol] as i32 - 128;
            let (r, g, b) = yuv_to_rgb(y_plane[row * w + col], d, e);
            dst[4 * (row * w + col)..][..4].copy_from_slice(&[r, g, b, 0xff]);
        }
    }
}

/// Converts row pairs (which share one chroma row) in parallel for large frames.
fn nv12_to_rgba<const LUT: bool>(raw: &[u8], w: usize, h: usize, dst: &mut [u8], lut: &[u8; 256]) {
    let uv_stride = 2 * w.div_ceil(2);
    let row_stride = w * 4;
    let (y_plane, uv_plane) = raw.split_at(w * h);
    let dst = &mut dst[..h * row_stride];
    let convert_pair = |pair: usize, rows: &mut [u8]| {
        let uv = &uv_plane[pair * uv_stride..][..uv_stride];
        for (i, out) in rows.chunks_mut(row_stride).enumerate() {
            let row = pair * 2 + i;
            convert_row::<LUT>(&y_plane[row * w..][..w], uv, out, lut);
        }
    };
    if should_parallel(w, h)
        && let Some(pool) = convert_pool()
    {
        pool.install(|| {
            dst.par_chunks_mut(row_stride * 2)
                .enumerate()
                .for_each(|(pair, rows)| convert_pair(pair, rows))
        });
    } else {
        dst.chunks_mut(row_stride * 2)
            .enumerate()
            .for_each(|(pair, rows)| convert_pair(pair, rows));
    }
}

#[inline(always)]
fn convert_row<const LUT: bool>(y: &[u8], uv: &[u8], dst: &mut [u8], lut: &[u8; 256]) {
    let pairs = dst.chunks_exact_mut(8).zip(y.chunks_exact(2)).zip(uv.chunks_exact(2));
    for ((px, luma), chroma) in pairs {
        let (d, e) = (chroma[0] as i32 - 128, chroma[1] as i32 - 128);
        write_rgba::<LUT>(luma[0], d, e, &mut px[..4], lut);
        write_rgba::<LUT>(luma[1], d, e, &mut px[4..], lut);
    }
    if y.len() % 2 == 1 {
        let x = y.len() - 1;
        let (d, e) = (uv[x] as i32 - 128, uv[x + 1] as i32 - 128);
        write_rgba::<LUT>(y[x], d, e, &mut dst[4 * x..4 * x + 4], lut);
    }
}

#[inline(always)]
fn write_rgba<const LUT: bool>(y: u8, d: i32, e: i32, px: &mut [u8], lut: &[u8; 256]) {
    let (r, g, b) = yuv_to_rgb(y, d, e);
    if LUT {
        px.copy_from_slice(&[lut[r as usize], lut[g as usize], lut[b as usize], 0xff]);
    } else {
        px.copy_from_slice(&[r, g, b, 0xff]);
    }
}

fn should_parallel(w: usize, h: usize) -> bool {
    w.saturating_mul(h) >= PARALLEL_PIXEL_THRESHOLD
}

fn convert_pool() -> Option<&'static ThreadPool> {
    static POOL: OnceLock<Option<ThreadPool>> = OnceLock::new();
    POOL.get_or_init(|| {
        let cores = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(1);
        let threads = match cores {
            0..=2 => return None,
            3..=5 => 2,
            _ => (cores - 2).clamp(4, MAX_CONVERT_THREADS),
        };
        ThreadPoolBuilder::new()
            .num_threads(threads)
            .thread_name(|i| format!("dd-convert-{i}"))
            .build()
            .ok()
    })
    .as_ref()
}

#[inline(always)]
fn yuv_to_rgb(y: u8, d: i32, e: i32) -> (u8, u8, u8) {
    // BT.709 limited range, fixed point with 8 fractional bits:
    // R = 1.1644*C + 1.7927*E; G = 1.1644*C - 0.2132*D - 0.5329*E; B = 1.1644*C + 2.1124*D
    let c = 298 * (y as i32 - 16);
    let r = (c + 459 * e + 128) >> 8;
    let g = (c - 55 * d - 136 * e + 128) >> 8;
    let b = (c + 541 * d + 128) >> 8;
    (clamp_u8(r), clamp_u8(g), clamp_u8(b))
}

#[inline(always)]
fn clamp_u8(v: i32) -> u8 {
    v.clamp(0, 255) as u8
}

#[cfg(test)]
mod tests {
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

    #[test]
    fn lut_identity_is_passthrough() {
        let lut = build_lut(IDENTITY_MILLI);
        for i in 0..=255usize {
            assert_eq!(lut[i], i as u8);
        }
        assert!(lut_is_identity(IDENTITY_MILLI));
    }

    #[test]
    fn lut_half_brightness_scales_down() {
        let lut = build_lut(500);
        assert_eq!(lut[0], 0);
        assert_eq!(lut[200], 100);
        assert_eq!(lut[255], 128);
    }

    #[test]
    fn lut_overbright_clamps() {
        let lut = build_lut(2000);
        assert_eq!(lut[200], 255);
    }

    fn rgb_of(rgba: &[u8]) -> Vec<u8> {
        rgba.chunks_exact(4)
            .flat_map(|px| [px[0], px[1], px[2]])
            .collect()
    }

    #[test]
    fn nv12_black_white_gray() {
        let lut = build_lut(IDENTITY_MILLI);
        let mut dst = vec![0u8; 2 * 2 * 4];

        // Limited-range black: Y=16, neutral chroma.
        nv12_to_rgba32(&nv12_frame(2, 2, 16, 128, 128), 2, 2, &mut dst, &lut);
        assert!(rgb_of(&dst).iter().all(|&b| b == 0), "black: {dst:?}");

        // Limited-range white: Y=235.
        nv12_to_rgba32(&nv12_frame(2, 2, 235, 128, 128), 2, 2, &mut dst, &lut);
        assert!(rgb_of(&dst).iter().all(|&b| b == 255), "white: {dst:?}");

        // Mid gray: Y=126 -> (298*110+128)>>8 = 128.
        nv12_to_rgba32(&nv12_frame(2, 2, 126, 128, 128), 2, 2, &mut dst, &lut);
        assert!(rgb_of(&dst).iter().all(|&b| b == 128), "gray: {dst:?}");
        assert!(dst.chunks_exact(4).all(|px| px[3] == 0xff));
    }

    #[test]
    fn nv12_bt709_red() {
        // Pure red RGB(255,0,0) in BT.709 limited range is approx Y=63, U=102, V=240.
        let mut dst = vec![0u8; 2 * 2 * 4];
        nv12_to_rgba32_identity(&nv12_frame(2, 2, 63, 102, 240), 2, 2, &mut dst);
        let (r, g, b) = (dst[0] as i32, dst[1] as i32, dst[2] as i32);
        assert!((r - 255).abs() <= 3, "r={r}");
        assert!(g.abs() <= 3, "g={g}");
        assert!(b.abs() <= 3, "b={b}");
    }

    #[test]
    fn nv12_odd_dimensions() {
        // 3 x 3: chroma plane is 2x2 blocks of UV pairs (stride 4). Must not panic and
        // must produce a fully written output.
        let raw = nv12_frame(3, 3, 126, 128, 128);
        assert_eq!(raw.len(), nv12_frame_size(3, 3));
        let mut dst = vec![0u8; 3 * 3 * 4];
        nv12_to_rgba32_identity(&raw, 3, 3, &mut dst);
        assert!(rgb_of(&dst).iter().all(|&b| b == 128), "{dst:?}");
    }

    #[test]
    fn nv12_parallel_matches_reference_and_identity_lut() {
        let (w, h) = (1280usize, 720usize);
        let mut raw = vec![0u8; nv12_frame_size(w, h)];
        for (i, b) in raw.iter_mut().enumerate() {
            *b = ((i * 37 + i / 17) & 0xff) as u8;
        }

        let mut parallel = vec![0u8; w * h * 4];
        let mut with_identity_lut = vec![0u8; w * h * 4];
        nv12_to_rgba32_identity(&raw, w, h, &mut parallel);
        nv12_to_rgba32(
            &raw,
            w,
            h,
            &mut with_identity_lut,
            &build_lut(IDENTITY_MILLI),
        );
        assert_eq!(parallel, with_identity_lut);

        // Per-pixel reference: the I420 path converts each pixel independently, no row pairing
        let mut i420 = vec![0u8; nv12_frame_size(w, h)];
        nv12_to_i420(&raw, w, h, &mut i420);
        let mut reference = vec![0u8; w * h * 4];
        i420_to_rgba32_identity(&i420, w, h, &mut reference);
        assert_eq!(parallel, reference);
    }

    #[test]
    fn nv12_to_i420_deinterleaves() {
        // 4x2: Y plane 8 bytes, then 2 UV pairs
        let mut raw = vec![1u8, 2, 3, 4, 5, 6, 7, 8];
        raw.extend_from_slice(&[10, 20, 11, 21]); // U0 V0 U1 V1
        let mut dst = vec![0u8; nv12_frame_size(4, 2)];
        nv12_to_i420(&raw, 4, 2, &mut dst);
        assert_eq!(&dst[..8], &[1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(&dst[8..10], &[10, 11], "U plane");
        assert_eq!(&dst[10..12], &[20, 21], "V plane");
    }

    #[test]
    fn i420_rgba_matches_nv12_rgba() {
        let (w, h) = (6usize, 4usize);
        let mut raw = vec![0u8; nv12_frame_size(w, h)];
        for (i, b) in raw.iter_mut().enumerate() {
            *b = ((i * 53 + 7) & 0xff) as u8;
        }
        let mut expected = vec![0u8; w * h * 4];
        nv12_to_rgba32_identity(&raw, w, h, &mut expected);

        let mut i420 = vec![0u8; nv12_frame_size(w, h)];
        nv12_to_i420(&raw, w, h, &mut i420);
        let mut actual = vec![0u8; w * h * 4];
        i420_to_rgba32_identity(&i420, w, h, &mut actual);
        assert_eq!(actual, expected);
    }
}
