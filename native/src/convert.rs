//! Pixel conversion kernels: brightness LUT and NV12 -> RGB24 (BT.709 limited range).
//!
//! The NV12 hot path walks 2x2 luma blocks because each UV pair is shared by four
//! pixels. That keeps chroma loads, index math, and bounds checks out of the per-pixel
//! path as much as possible while preserving exact BT.709 limited-range output.

use rayon::prelude::*;
use rayon::{ThreadPool, ThreadPoolBuilder};
use std::sync::OnceLock;

const PARALLEL_PIXEL_THRESHOLD: usize = 1280 * 720;
const MAX_CONVERT_THREADS: usize = 8;

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

/// Applies the brightness LUT to an RGB24 buffer in place.
pub fn rgb24_apply_lut_in_place(buf: &mut [u8], lut: &[u8; 256]) {
    for b in buf {
        *b = lut[*b as usize];
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
    if should_parallel(w, h)
        && let Some(pool) = convert_pool()
    {
        pool.install(|| nv12_to_rgb24_lut_parallel(raw, w, h, dst, lut));
        return;
    }
    nv12_to_rgb24_lut_sequential(raw, w, h, dst, lut)
}

/// Converts NV12 to RGB24 without brightness adjustment.
pub fn nv12_to_rgb24_identity(raw: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    if should_parallel(w, h)
        && let Some(pool) = convert_pool()
    {
        pool.install(|| nv12_to_rgb24_identity_parallel(raw, w, h, dst));
        return;
    }
    nv12_to_rgb24_identity_sequential(raw, w, h, dst)
}

/// Converts one NV12 frame into tightly packed RGBA32 with alpha fixed at 255.
pub fn nv12_to_rgba32(raw: &[u8], w: usize, h: usize, dst: &mut [u8], lut: &[u8; 256]) {
    if should_parallel(w, h)
        && let Some(pool) = convert_pool()
    {
        pool.install(|| nv12_to_rgba32_lut_parallel(raw, w, h, dst, lut));
        return;
    }
    nv12_to_rgba32_lut_sequential(raw, w, h, dst, lut)
}

/// Converts NV12 to RGBA32 without brightness adjustment.
pub fn nv12_to_rgba32_identity(raw: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    if should_parallel(w, h)
        && let Some(pool) = convert_pool()
    {
        pool.install(|| nv12_to_rgba32_identity_parallel(raw, w, h, dst));
        return;
    }
    nv12_to_rgba32_identity_sequential(raw, w, h, dst)
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
            write_rgba_identity(y_plane[row * w + col], d, e, dst, 4 * (row * w + col));
        }
    }
}

/// Expands RGB24 into RGBA32 with brightness applied and alpha fixed at 255.
pub fn rgb24_to_rgba32(src: &[u8], dst: &mut [u8], lut: &[u8; 256]) {
    let pixels = src.len().min(dst.len() / 4 * 3) / 3;
    if pixels >= PARALLEL_PIXEL_THRESHOLD {
        src[..pixels * 3]
            .par_chunks_exact(3)
            .zip(dst[..pixels * 4].par_chunks_exact_mut(4))
            .for_each(|(rgb, rgba)| {
                rgba[0] = lut[rgb[0] as usize];
                rgba[1] = lut[rgb[1] as usize];
                rgba[2] = lut[rgb[2] as usize];
                rgba[3] = 0xff;
            });
        return;
    }
    for (rgb, rgba) in src.chunks_exact(3).zip(dst.chunks_exact_mut(4)) {
        rgba[0] = lut[rgb[0] as usize];
        rgba[1] = lut[rgb[1] as usize];
        rgba[2] = lut[rgb[2] as usize];
        rgba[3] = 0xff;
    }
}

/// Expands RGB24 into RGBA32 without brightness adjustment.
pub fn rgb24_to_rgba32_identity(src: &[u8], dst: &mut [u8]) {
    let pixels = src.len().min(dst.len() / 4 * 3) / 3;
    if pixels >= PARALLEL_PIXEL_THRESHOLD {
        src[..pixels * 3]
            .par_chunks_exact(3)
            .zip(dst[..pixels * 4].par_chunks_exact_mut(4))
            .for_each(|(rgb, rgba)| {
                rgba[0] = rgb[0];
                rgba[1] = rgb[1];
                rgba[2] = rgb[2];
                rgba[3] = 0xff;
            });
        return;
    }
    for (rgb, rgba) in src.chunks_exact(3).zip(dst.chunks_exact_mut(4)) {
        rgba[0] = rgb[0];
        rgba[1] = rgb[1];
        rgba[2] = rgb[2];
        rgba[3] = 0xff;
    }
}

fn nv12_to_rgb24_identity_sequential(raw: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    let cw = w.div_ceil(2);
    let y_plane = &raw[..w * h];
    let uv_plane = &raw[w * h..];
    let uv_stride = 2 * cw;
    let row_stride = w * 3;
    let even_h = h & !1;

    for row in (0..even_h).step_by(2) {
        let y0 = &y_plane[row * w..row * w + w];
        let y1 = &y_plane[(row + 1) * w..(row + 1) * w + w];
        let uv = &uv_plane[(row / 2) * uv_stride..(row / 2) * uv_stride + uv_stride];
        let rows = &mut dst[row * row_stride..(row + 2) * row_stride];
        let (dst0, dst1) = rows.split_at_mut(row_stride);
        convert_two_rows_identity(y0, y1, uv, dst0, dst1, w);
    }

    if even_h != h {
        let y = &y_plane[even_h * w..even_h * w + w];
        let uv = &uv_plane[(even_h / 2) * uv_stride..(even_h / 2) * uv_stride + uv_stride];
        let dst_row = &mut dst[even_h * row_stride..(even_h + 1) * row_stride];
        convert_one_row_identity(y, uv, dst_row, w);
    }
}

fn nv12_to_rgb24_lut_sequential(raw: &[u8], w: usize, h: usize, dst: &mut [u8], lut: &[u8; 256]) {
    let cw = w.div_ceil(2);
    let y_plane = &raw[..w * h];
    let uv_plane = &raw[w * h..];
    let uv_stride = 2 * cw;
    let row_stride = w * 3;
    let even_h = h & !1;

    for row in (0..even_h).step_by(2) {
        let y0 = &y_plane[row * w..row * w + w];
        let y1 = &y_plane[(row + 1) * w..(row + 1) * w + w];
        let uv = &uv_plane[(row / 2) * uv_stride..(row / 2) * uv_stride + uv_stride];
        let rows = &mut dst[row * row_stride..(row + 2) * row_stride];
        let (dst0, dst1) = rows.split_at_mut(row_stride);
        convert_two_rows_lut(y0, y1, uv, dst0, dst1, w, lut);
    }

    if even_h != h {
        let y = &y_plane[even_h * w..even_h * w + w];
        let uv = &uv_plane[(even_h / 2) * uv_stride..(even_h / 2) * uv_stride + uv_stride];
        let dst_row = &mut dst[even_h * row_stride..(even_h + 1) * row_stride];
        convert_one_row_lut(y, uv, dst_row, w, lut);
    }
}

fn nv12_to_rgba32_identity_sequential(raw: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    let cw = w.div_ceil(2);
    let y_plane = &raw[..w * h];
    let uv_plane = &raw[w * h..];
    let uv_stride = 2 * cw;
    let row_stride = w * 4;
    let even_h = h & !1;

    for row in (0..even_h).step_by(2) {
        let y0 = &y_plane[row * w..row * w + w];
        let y1 = &y_plane[(row + 1) * w..(row + 1) * w + w];
        let uv = &uv_plane[(row / 2) * uv_stride..(row / 2) * uv_stride + uv_stride];
        let rows = &mut dst[row * row_stride..(row + 2) * row_stride];
        let (dst0, dst1) = rows.split_at_mut(row_stride);
        convert_two_rows_rgba_identity(y0, y1, uv, dst0, dst1, w);
    }

    if even_h != h {
        let y = &y_plane[even_h * w..even_h * w + w];
        let uv = &uv_plane[(even_h / 2) * uv_stride..(even_h / 2) * uv_stride + uv_stride];
        let dst_row = &mut dst[even_h * row_stride..(even_h + 1) * row_stride];
        convert_one_row_rgba_identity(y, uv, dst_row, w);
    }
}

fn nv12_to_rgba32_lut_sequential(raw: &[u8], w: usize, h: usize, dst: &mut [u8], lut: &[u8; 256]) {
    let cw = w.div_ceil(2);
    let y_plane = &raw[..w * h];
    let uv_plane = &raw[w * h..];
    let uv_stride = 2 * cw;
    let row_stride = w * 4;
    let even_h = h & !1;

    for row in (0..even_h).step_by(2) {
        let y0 = &y_plane[row * w..row * w + w];
        let y1 = &y_plane[(row + 1) * w..(row + 1) * w + w];
        let uv = &uv_plane[(row / 2) * uv_stride..(row / 2) * uv_stride + uv_stride];
        let rows = &mut dst[row * row_stride..(row + 2) * row_stride];
        let (dst0, dst1) = rows.split_at_mut(row_stride);
        convert_two_rows_rgba_lut(y0, y1, uv, dst0, dst1, w, lut);
    }

    if even_h != h {
        let y = &y_plane[even_h * w..even_h * w + w];
        let uv = &uv_plane[(even_h / 2) * uv_stride..(even_h / 2) * uv_stride + uv_stride];
        let dst_row = &mut dst[even_h * row_stride..(even_h + 1) * row_stride];
        convert_one_row_rgba_lut(y, uv, dst_row, w, lut);
    }
}

fn nv12_to_rgb24_identity_parallel(raw: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    let cw = w.div_ceil(2);
    let y_plane = &raw[..w * h];
    let uv_plane = &raw[w * h..];
    let uv_stride = 2 * cw;
    let row_stride = w * 3;
    let even_h = h & !1;
    let even_dst_len = even_h * row_stride;

    dst[..even_dst_len]
        .par_chunks_mut(row_stride * 2)
        .enumerate()
        .for_each(|(pair, rows)| {
            let row = pair * 2;
            let y0 = &y_plane[row * w..row * w + w];
            let y1 = &y_plane[(row + 1) * w..(row + 1) * w + w];
            let uv = &uv_plane[pair * uv_stride..pair * uv_stride + uv_stride];
            let (dst0, dst1) = rows.split_at_mut(row_stride);
            convert_two_rows_identity(y0, y1, uv, dst0, dst1, w);
        });

    if even_h != h {
        let y = &y_plane[even_h * w..even_h * w + w];
        let uv = &uv_plane[(even_h / 2) * uv_stride..(even_h / 2) * uv_stride + uv_stride];
        let dst_row = &mut dst[even_h * row_stride..(even_h + 1) * row_stride];
        convert_one_row_identity(y, uv, dst_row, w);
    }
}

fn nv12_to_rgb24_lut_parallel(raw: &[u8], w: usize, h: usize, dst: &mut [u8], lut: &[u8; 256]) {
    let cw = w.div_ceil(2);
    let y_plane = &raw[..w * h];
    let uv_plane = &raw[w * h..];
    let uv_stride = 2 * cw;
    let row_stride = w * 3;
    let even_h = h & !1;
    let even_dst_len = even_h * row_stride;

    dst[..even_dst_len]
        .par_chunks_mut(row_stride * 2)
        .enumerate()
        .for_each(|(pair, rows)| {
            let row = pair * 2;
            let y0 = &y_plane[row * w..row * w + w];
            let y1 = &y_plane[(row + 1) * w..(row + 1) * w + w];
            let uv = &uv_plane[pair * uv_stride..pair * uv_stride + uv_stride];
            let (dst0, dst1) = rows.split_at_mut(row_stride);
            convert_two_rows_lut(y0, y1, uv, dst0, dst1, w, lut);
        });

    if even_h != h {
        let y = &y_plane[even_h * w..even_h * w + w];
        let uv = &uv_plane[(even_h / 2) * uv_stride..(even_h / 2) * uv_stride + uv_stride];
        let dst_row = &mut dst[even_h * row_stride..(even_h + 1) * row_stride];
        convert_one_row_lut(y, uv, dst_row, w, lut);
    }
}

fn nv12_to_rgba32_identity_parallel(raw: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    let cw = w.div_ceil(2);
    let y_plane = &raw[..w * h];
    let uv_plane = &raw[w * h..];
    let uv_stride = 2 * cw;
    let row_stride = w * 4;
    let even_h = h & !1;
    let even_dst_len = even_h * row_stride;

    dst[..even_dst_len]
        .par_chunks_mut(row_stride * 2)
        .enumerate()
        .for_each(|(pair, rows)| {
            let row = pair * 2;
            let y0 = &y_plane[row * w..row * w + w];
            let y1 = &y_plane[(row + 1) * w..(row + 1) * w + w];
            let uv = &uv_plane[pair * uv_stride..pair * uv_stride + uv_stride];
            let (dst0, dst1) = rows.split_at_mut(row_stride);
            convert_two_rows_rgba_identity(y0, y1, uv, dst0, dst1, w);
        });

    if even_h != h {
        let y = &y_plane[even_h * w..even_h * w + w];
        let uv = &uv_plane[(even_h / 2) * uv_stride..(even_h / 2) * uv_stride + uv_stride];
        let dst_row = &mut dst[even_h * row_stride..(even_h + 1) * row_stride];
        convert_one_row_rgba_identity(y, uv, dst_row, w);
    }
}

fn nv12_to_rgba32_lut_parallel(raw: &[u8], w: usize, h: usize, dst: &mut [u8], lut: &[u8; 256]) {
    let cw = w.div_ceil(2);
    let y_plane = &raw[..w * h];
    let uv_plane = &raw[w * h..];
    let uv_stride = 2 * cw;
    let row_stride = w * 4;
    let even_h = h & !1;
    let even_dst_len = even_h * row_stride;

    dst[..even_dst_len]
        .par_chunks_mut(row_stride * 2)
        .enumerate()
        .for_each(|(pair, rows)| {
            let row = pair * 2;
            let y0 = &y_plane[row * w..row * w + w];
            let y1 = &y_plane[(row + 1) * w..(row + 1) * w + w];
            let uv = &uv_plane[pair * uv_stride..pair * uv_stride + uv_stride];
            let (dst0, dst1) = rows.split_at_mut(row_stride);
            convert_two_rows_rgba_lut(y0, y1, uv, dst0, dst1, w, lut);
        });

    if even_h != h {
        let y = &y_plane[even_h * w..even_h * w + w];
        let uv = &uv_plane[(even_h / 2) * uv_stride..(even_h / 2) * uv_stride + uv_stride];
        let dst_row = &mut dst[even_h * row_stride..(even_h + 1) * row_stride];
        convert_one_row_rgba_lut(y, uv, dst_row, w, lut);
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
fn convert_two_rows_identity(
    y0: &[u8],
    y1: &[u8],
    uv: &[u8],
    dst0: &mut [u8],
    dst1: &mut [u8],
    w: usize,
) {
    let mut x = 0usize;
    while x + 1 < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgb_identity(y0[x], d, e, dst0, 3 * x);
        write_rgb_identity(y0[x + 1], d, e, dst0, 3 * (x + 1));
        write_rgb_identity(y1[x], d, e, dst1, 3 * x);
        write_rgb_identity(y1[x + 1], d, e, dst1, 3 * (x + 1));
        x += 2;
    }
    if x < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgb_identity(y0[x], d, e, dst0, 3 * x);
        write_rgb_identity(y1[x], d, e, dst1, 3 * x);
    }
}

#[inline(always)]
fn convert_two_rows_lut(
    y0: &[u8],
    y1: &[u8],
    uv: &[u8],
    dst0: &mut [u8],
    dst1: &mut [u8],
    w: usize,
    lut: &[u8; 256],
) {
    let mut x = 0usize;
    while x + 1 < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgb_lut(y0[x], d, e, dst0, 3 * x, lut);
        write_rgb_lut(y0[x + 1], d, e, dst0, 3 * (x + 1), lut);
        write_rgb_lut(y1[x], d, e, dst1, 3 * x, lut);
        write_rgb_lut(y1[x + 1], d, e, dst1, 3 * (x + 1), lut);
        x += 2;
    }
    if x < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgb_lut(y0[x], d, e, dst0, 3 * x, lut);
        write_rgb_lut(y1[x], d, e, dst1, 3 * x, lut);
    }
}

#[inline(always)]
fn convert_one_row_identity(y: &[u8], uv: &[u8], dst: &mut [u8], w: usize) {
    let mut x = 0usize;
    while x + 1 < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgb_identity(y[x], d, e, dst, 3 * x);
        write_rgb_identity(y[x + 1], d, e, dst, 3 * (x + 1));
        x += 2;
    }
    if x < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgb_identity(y[x], d, e, dst, 3 * x);
    }
}

#[inline(always)]
fn convert_one_row_lut(y: &[u8], uv: &[u8], dst: &mut [u8], w: usize, lut: &[u8; 256]) {
    let mut x = 0usize;
    while x + 1 < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgb_lut(y[x], d, e, dst, 3 * x, lut);
        write_rgb_lut(y[x + 1], d, e, dst, 3 * (x + 1), lut);
        x += 2;
    }
    if x < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgb_lut(y[x], d, e, dst, 3 * x, lut);
    }
}

#[inline(always)]
fn convert_two_rows_rgba_identity(
    y0: &[u8],
    y1: &[u8],
    uv: &[u8],
    dst0: &mut [u8],
    dst1: &mut [u8],
    w: usize,
) {
    let mut x = 0usize;
    while x + 1 < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgba_identity(y0[x], d, e, dst0, 4 * x);
        write_rgba_identity(y0[x + 1], d, e, dst0, 4 * (x + 1));
        write_rgba_identity(y1[x], d, e, dst1, 4 * x);
        write_rgba_identity(y1[x + 1], d, e, dst1, 4 * (x + 1));
        x += 2;
    }
    if x < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgba_identity(y0[x], d, e, dst0, 4 * x);
        write_rgba_identity(y1[x], d, e, dst1, 4 * x);
    }
}

#[inline(always)]
fn convert_two_rows_rgba_lut(
    y0: &[u8],
    y1: &[u8],
    uv: &[u8],
    dst0: &mut [u8],
    dst1: &mut [u8],
    w: usize,
    lut: &[u8; 256],
) {
    let mut x = 0usize;
    while x + 1 < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgba_lut(y0[x], d, e, dst0, 4 * x, lut);
        write_rgba_lut(y0[x + 1], d, e, dst0, 4 * (x + 1), lut);
        write_rgba_lut(y1[x], d, e, dst1, 4 * x, lut);
        write_rgba_lut(y1[x + 1], d, e, dst1, 4 * (x + 1), lut);
        x += 2;
    }
    if x < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgba_lut(y0[x], d, e, dst0, 4 * x, lut);
        write_rgba_lut(y1[x], d, e, dst1, 4 * x, lut);
    }
}

#[inline(always)]
fn convert_one_row_rgba_identity(y: &[u8], uv: &[u8], dst: &mut [u8], w: usize) {
    let mut x = 0usize;
    while x + 1 < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgba_identity(y[x], d, e, dst, 4 * x);
        write_rgba_identity(y[x + 1], d, e, dst, 4 * (x + 1));
        x += 2;
    }
    if x < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgba_identity(y[x], d, e, dst, 4 * x);
    }
}

#[inline(always)]
fn convert_one_row_rgba_lut(y: &[u8], uv: &[u8], dst: &mut [u8], w: usize, lut: &[u8; 256]) {
    let mut x = 0usize;
    while x + 1 < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgba_lut(y[x], d, e, dst, 4 * x, lut);
        write_rgba_lut(y[x + 1], d, e, dst, 4 * (x + 1), lut);
        x += 2;
    }
    if x < w {
        let d = uv[x] as i32 - 128;
        let e = uv[x + 1] as i32 - 128;
        write_rgba_lut(y[x], d, e, dst, 4 * x, lut);
    }
}

#[inline(always)]
fn write_rgb_identity(y: u8, d: i32, e: i32, dst: &mut [u8], i: usize) {
    let (r, g, b) = yuv_to_rgb(y, d, e);
    dst[i] = r;
    dst[i + 1] = g;
    dst[i + 2] = b;
}

#[inline(always)]
fn write_rgb_lut(y: u8, d: i32, e: i32, dst: &mut [u8], i: usize, lut: &[u8; 256]) {
    let (r, g, b) = yuv_to_rgb(y, d, e);
    dst[i] = lut[r as usize];
    dst[i + 1] = lut[g as usize];
    dst[i + 2] = lut[b as usize];
}

#[inline(always)]
fn write_rgba_identity(y: u8, d: i32, e: i32, dst: &mut [u8], i: usize) {
    let (r, g, b) = yuv_to_rgb(y, d, e);
    dst[i] = r;
    dst[i + 1] = g;
    dst[i + 2] = b;
    dst[i + 3] = 0xff;
}

#[inline(always)]
fn write_rgba_lut(y: u8, d: i32, e: i32, dst: &mut [u8], i: usize, lut: &[u8; 256]) {
    let (r, g, b) = yuv_to_rgb(y, d, e);
    dst[i] = lut[r as usize];
    dst[i + 1] = lut[g as usize];
    dst[i + 2] = lut[b as usize];
    dst[i + 3] = 0xff;
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
    if v < 0 {
        0
    } else if v > 255 {
        255
    } else {
        v as u8
    }
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

    #[test]
    fn nv12_black_white_gray() {
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

    #[test]
    fn nv12_bt709_red() {
        // Pure red RGB(255,0,0) in BT.709 limited range is approx Y=63, U=102, V=240.
        let lut = build_lut(IDENTITY_MILLI);
        let mut dst = vec![0u8; 2 * 2 * 3];
        nv12_to_rgb24(&nv12_frame(2, 2, 63, 102, 240), 2, 2, &mut dst, &lut);
        let (r, g, b) = (dst[0] as i32, dst[1] as i32, dst[2] as i32);
        assert!((r - 255).abs() <= 3, "r={r}");
        assert!(g.abs() <= 3, "g={g}");
        assert!(b.abs() <= 3, "b={b}");
    }

    #[test]
    fn nv12_odd_dimensions() {
        // 3x3: chroma plane is 2x2 blocks of UV pairs (stride 4). Must not panic and
        // must produce a fully written 3*3*3 output.
        let lut = build_lut(IDENTITY_MILLI);
        let raw = nv12_frame(3, 3, 126, 128, 128);
        assert_eq!(raw.len(), nv12_frame_size(3, 3));
        let mut dst = vec![0u8; 3 * 3 * 3];
        nv12_to_rgb24(&raw, 3, 3, &mut dst, &lut);
        assert!(dst.iter().all(|&b| b == 128), "{dst:?}");
    }

    #[test]
    fn nv12_parallel_matches_sequential() {
        let (w, h) = (1280usize, 720usize);
        let mut raw = vec![0u8; nv12_frame_size(w, h)];
        for (i, b) in raw.iter_mut().enumerate() {
            *b = ((i * 37 + i / 17) & 0xff) as u8;
        }

        let lut = build_lut(900);
        let mut sequential = vec![0u8; w * h * 3];
        let mut parallel = vec![0u8; w * h * 3];
        nv12_to_rgb24_lut_sequential(&raw, w, h, &mut sequential, &lut);
        nv12_to_rgb24(&raw, w, h, &mut parallel, &lut);
        assert_eq!(parallel, sequential);

        nv12_to_rgb24_identity_sequential(&raw, w, h, &mut sequential);
        nv12_to_rgb24_identity(&raw, w, h, &mut parallel);
        assert_eq!(parallel, sequential);

        let mut sequential_rgba = vec![0u8; w * h * 4];
        let mut parallel_rgba = vec![0u8; w * h * 4];
        nv12_to_rgba32_lut_sequential(&raw, w, h, &mut sequential_rgba, &lut);
        nv12_to_rgba32(&raw, w, h, &mut parallel_rgba, &lut);
        assert_eq!(parallel_rgba, sequential_rgba);
        assert!(parallel_rgba.chunks_exact(4).all(|px| px[3] == 0xff));

        nv12_to_rgba32_identity_sequential(&raw, w, h, &mut sequential_rgba);
        nv12_to_rgba32_identity(&raw, w, h, &mut parallel_rgba);
        assert_eq!(parallel_rgba, sequential_rgba);
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

    #[test]
    fn rgb24_lut_applies() {
        let lut = build_lut(500);
        let src = [10u8, 100, 200, 255];
        let mut dst = [0u8; 4];
        rgb24_with_lut(&src, &mut dst, &lut);
        assert_eq!(dst, [5, 50, 100, 128]);

        let mut inplace = src;
        rgb24_apply_lut_in_place(&mut inplace, &lut);
        assert_eq!(inplace, dst);

        let mut rgba = [0u8; 8];
        rgb24_to_rgba32(&[10u8, 100, 200, 255, 20, 40], &mut rgba, &lut);
        assert_eq!(rgba, [5, 50, 100, 255, 128, 10, 20, 255]);
    }
}
