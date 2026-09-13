//! Parallel swscale for the I420 output path.

use anyhow::{Result, bail};
use ffmpeg::ffi;
use ffmpeg::format::Pixel;
use ffmpeg::util::frame::video::Video as VideoFrame;
use ffmpeg_next as ffmpeg;
use rayon::{ThreadPool, ThreadPoolBuilder, prelude::*};
use std::ffi::c_int;
use std::ptr;
use std::sync::OnceLock;

/// Below this many output pixels a rescale is cheap enough that thread hand-off would cost more.
const PARALLEL_PIXEL_THRESHOLD: usize = 256 * 256;

/// Never cut a band thinner than this; short bands pay more in overhead than they win in cores.
const MIN_BAND_ROWS: usize = 64;

/// Cap on bands (so cores stay free for the game's render thread); past four the curve flattens.
const MAX_BANDS: usize = 4;

/// Bilinear beats fast-bilinear here on both counts: it is measurably quicker on this path and it
/// widens its filter when downscaling instead of point-sampling every other pixel.
const SCALE_FLAGS: c_int = ffi::SwsFlags::SWS_BILINEAR as c_int;

/// Dedicated pool, sized to leave the game room, mirroring the process pipeline's convert pool.
fn scale_pool() -> Option<&'static ThreadPool> {
    static POOL: OnceLock<Option<ThreadPool>> = OnceLock::new();
    POOL.get_or_init(|| {
        let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
        if cores < 4 {
            return None;
        }
        ThreadPoolBuilder::new()
            .num_threads((cores - 2).clamp(2, MAX_BANDS))
            .thread_name(|i| format!("dd-lav-scale-{i}"))
            .build()
            .ok()
    })
    .as_ref()
}

/// Plane pointers passed to the band threads. Each band addresses only the rows it owns, so the
/// same picture is safe to hand to all of them at once.
#[derive(Clone, Copy)]
struct Planes<T, const N: usize>([T; N]);

unsafe impl<T, const N: usize> Send for Planes<T, N> {}
unsafe impl<T, const N: usize> Sync for Planes<T, N> {}

/// One horizontal band: its own scaler context plus where the band starts in source and target.
struct Band {
    ctx: *mut ffi::SwsContext,
    src_y: usize,
    src_h: c_int,
    dst_y: usize,
    #[allow(dead_code, reason = "band geometry is asserted in tests; the scale call derives it")]
    dst_h: usize,
}

/// Contexts are only ever touched by the one band that owns them, never shared across threads.
unsafe impl Send for Band {}
unsafe impl Sync for Band {}

/// A source geometry pinned to one target geometry, scaled band-parallel.
pub struct BandedScaler {
    pub src_format: Pixel,
    pub src_w: u32,
    pub src_h: u32,
    pub dst_w: u32,
    pub dst_h: u32,
    planes: usize,
    log2_chroma_h: u32,
    bands: Vec<Band>,
}

impl Drop for BandedScaler {
    fn drop(&mut self) {
        for band in &self.bands {
            unsafe { ffi::sws_freeContext(band.ctx) };
        }
    }
}

impl BandedScaler {
    /// Builds the per-band contexts for `src_format` `sw` x `sh` -> YUV420P `dw` x `dh`.
    pub fn new(src_format: Pixel, sw: u32, sh: u32, dw: u32, dh: u32) -> Result<BandedScaler> {
        let (planes, log2_chroma_h) = unsafe {
            let raw: ffi::AVPixelFormat = src_format.into();
            let desc = ffi::av_pix_fmt_desc_get(raw);
            if desc.is_null() {
                bail!("unknown source pixel format {src_format:?}.");
            }
            (ffi::av_pix_fmt_count_planes(raw).max(1) as usize, (*desc).log2_chroma_h as u32)
        };

        let mut scaler = BandedScaler {
            src_format,
            src_w: sw,
            src_h: sh,
            dst_w: dw,
            dst_h: dh,
            planes,
            log2_chroma_h,
            bands: Vec::new(),
        };
        for (src_y, src_h, dst_y, band_h) in scaler.plan(sw, sh, dh) {
            let ctx = unsafe {
                ffi::sws_getContext(
                    sw as c_int,
                    src_h as c_int,
                    src_format.into(),
                    dw as c_int,
                    band_h as c_int,
                    ffi::AVPixelFormat::AV_PIX_FMT_YUV420P,
                    SCALE_FLAGS,
                    ptr::null_mut(),
                    ptr::null_mut(),
                    ptr::null(),
                )
            };
            if ctx.is_null() {
                bail!("create swscale context {src_format:?} {sw} x {src_h} -> {dw} x {band_h}.");
            }
            scaler.bands.push(Band { ctx, src_y, src_h: src_h as c_int, dst_y, dst_h: band_h });
        }
        Ok(scaler)
    }

    /// How many bands this scaler runs; 1 means it stayed single-threaded.
    pub fn bands(&self) -> usize {
        self.bands.len()
    }

    /// Cuts the picture into `(src_y, src_h, dst_y, dst_h)` bands, or one whole-picture band when
    /// splitting would not pay: no actual rescale, a small picture, odd chroma, or no pool.
    fn plan(&self, sw: u32, sh: u32, dh: u32) -> Vec<(usize, usize, usize, usize)> {
        let whole = vec![(0usize, sh as usize, 0usize, dh as usize)];
        if sw == self.dst_w && sh == dh {
            return whole;
        }
        if (self.dst_w as usize) * (dh as usize) < PARALLEL_PIXEL_THRESHOLD {
            return whole;
        }

        if self.log2_chroma_h > 1 {
            return whole;
        }
        let Some(pool) = scale_pool() else {
            return whole;
        };
        let n = pool
            .current_num_threads()
            .min(MAX_BANDS)
            .min((dh as usize / MIN_BAND_ROWS).max(1));
        if n < 2 {
            return whole;
        }

        let (sh, dh) = (sh as usize, dh as usize);
        let mut plan = Vec::with_capacity(n);
        let (mut dst_y, mut src_y) = (0usize, 0usize);
        for i in 1..=n {
            let dst_end = if i == n { dh } else { (dh * i / n) & !1 };
            let src_end = if i == n { sh } else { ((dst_end * sh + dh / 2) / dh) & !1 };
            if dst_end <= dst_y || src_end <= src_y {
                continue;
            }
            plan.push((src_y, src_end - src_y, dst_y, dst_end - dst_y));
            dst_y = dst_end;
            src_y = src_end;
        }
        // On an extreme stretch several cuts can round onto the same row and get dropped, the last
        // one included; stretch the final band over whatever is left so every row still gets written.
        if dst_y < dh || src_y < sh {
            match plan.last_mut() {
                Some(last) => (last.1, last.3) = (sh - last.0, dh - last.2),
                None => plan.push((0, sh, 0, dh)),
            }
        }
        if plan.len() < 2 { whole } else { plan }
    }

    /// Scales `src` into a YUV420P target whose planes start at `dst` with strides `dst_strides`.
    pub unsafe fn scale(&self, src: &VideoFrame, dst: [*mut u8; 4], dst_strides: [c_int; 4]) -> Result<()> {
        let (src_planes, src_strides) = unsafe {
            let raw = *src.as_ptr();
            (Planes(raw.data), raw.linesize)
        };
        let dst = Planes(dst);
        let run = |band: &Band| unsafe {
            let (src_planes, dst) = (src_planes, dst);
            let mut planes: [*const u8; 4] = [ptr::null(); 4];
            for i in 0..self.planes.min(4) {
                let shift = if i == 1 || i == 2 { self.log2_chroma_h } else { 0 };
                let row = band.src_y >> shift;
                planes[i] = src_planes.0[i].add(row * src_strides[i].unsigned_abs() as usize).cast_const();
            }
            let mut out = dst.0;
            out[0] = out[0].add(band.dst_y * dst_strides[0] as usize);
            out[1] = out[1].add((band.dst_y / 2) * dst_strides[1] as usize);
            out[2] = out[2].add((band.dst_y / 2) * dst_strides[2] as usize);
            ffi::sws_scale(
                band.ctx,
                planes.as_ptr(),
                src_strides.as_ptr(),
                0,
                band.src_h,
                out.as_ptr(),
                dst_strides.as_ptr(),
            )
        };

        let failed = match (self.bands.len(), scale_pool()) {
            (0, _) => 0,
            (1, _) => i32::from(run(&self.bands[0]) < 0),
            (_, Some(pool)) => pool.install(|| self.bands.par_iter().map(|b| i32::from(run(b) < 0)).sum()),
            (_, None) => self.bands.iter().map(|b| i32::from(run(b) < 0)).sum(),
        };
        if failed > 0 {
            bail!("scale frame ({failed} of {} bands failed).", self.bands.len());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn striped_source(w: u32, h: u32) -> VideoFrame {
        let mut frame = VideoFrame::new(Pixel::YUV420P, w, h);
        let (w, h) = (w as usize, h as usize);
        for row in 0..h {
            let stride = frame.stride(0);
            let value = if (row / 3) % 2 == 0 { 235 } else { 16 };
            frame.data_mut(0)[row * stride..row * stride + w].fill(value);
        }
        for plane in 1..3 {
            let stride = frame.stride(plane);
            for row in 0..h / 2 {
                for col in 0..w / 2 {
                    frame.data_mut(plane)[row * stride + col] = ((row * 3 + col * 5) % 256) as u8;
                }
            }
        }
        frame
    }

    fn scale_single(src: &VideoFrame, sw: u32, sh: u32, dw: u32, dh: u32) -> Vec<u8> {
        let mut out = vec![0u8; (dw * dh) as usize * 3 / 2];
        unsafe {
            let ctx = ffi::sws_getContext(
                sw as c_int,
                sh as c_int,
                Pixel::YUV420P.into(),
                dw as c_int,
                dh as c_int,
                ffi::AVPixelFormat::AV_PIX_FMT_YUV420P,
                SCALE_FLAGS,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null(),
            );
            let (y_size, c_size) = ((dw * dh) as usize, (dw * dh) as usize / 4);
            let base = out.as_mut_ptr();
            let planes = [base, base.add(y_size), base.add(y_size + c_size), ptr::null_mut()];
            let strides = [dw as c_int, (dw / 2) as c_int, (dw / 2) as c_int, 0];
            ffi::sws_scale(
                ctx,
                (*src.as_ptr()).data.as_ptr().cast::<*const u8>(),
                (*src.as_ptr()).linesize.as_ptr(),
                0,
                sh as c_int,
                planes.as_ptr(),
                strides.as_ptr(),
            );
            ffi::sws_freeContext(ctx);
        }
        out
    }

    #[test]
    fn banded_output_matches_a_single_context() {
        for (sw, sh, dw, dh) in [(1280u32, 720u32, 640u32, 360u32), (640, 360, 1280, 720)] {
            let src = striped_source(sw, sh);
            let scaler = BandedScaler::new(Pixel::YUV420P, sw, sh, dw, dh).unwrap();
            if scaler.bands() < 2 {
                continue;
            }

            let mut banded = vec![0u8; (dw * dh) as usize * 3 / 2];
            let (y_size, c_size) = ((dw * dh) as usize, (dw * dh) as usize / 4);
            unsafe {
                let base = banded.as_mut_ptr();
                let planes = [base, base.add(y_size), base.add(y_size + c_size), ptr::null_mut()];
                let strides = [dw as c_int, (dw / 2) as c_int, (dw / 2) as c_int, 0];
                scaler.scale(&src, planes, strides).unwrap();
            }
            let reference = scale_single(&src, sw, sh, dw, dh);

            let differing = banded
                .iter()
                .zip(&reference)
                .filter(|(a, b)| a.abs_diff(**b) > 1)
                .count();
            let ratio = differing as f64 / banded.len() as f64;
            assert!(
                ratio < 0.05,
                "{sw} x {sh} -> {dw} x {dh} on {} bands: {:.1}% of samples differ from a single context.",
                scaler.bands(),
                ratio * 100.0,
            );
        }
    }

    #[test]
    fn same_size_conversion_stays_on_one_band() {
        let scaler = BandedScaler::new(Pixel::YUV420P, 1920, 1080, 1920, 1080).unwrap();
        assert_eq!(scaler.bands(), 1);
    }

    #[test]
    fn bands_tile_the_picture_without_gaps() {
        for (sw, sh, dw, dh) in [(3840u32, 2160u32, 1920u32, 1080u32), (854, 480, 1918, 1078), (16, 2, 1920, 1080)] {
            let scaler = BandedScaler::new(Pixel::YUV420P, sw, sh, dw, dh).unwrap();
            let (mut src_next, mut dst_next) = (0usize, 0usize);
            for band in &scaler.bands {
                assert_eq!(band.src_y, src_next, "{sw}x{sh}: source rows are not contiguous.");
                assert_eq!(band.dst_y, dst_next, "{sw}x{sh}: target rows are not contiguous.");
                assert_eq!(band.src_y % 2, 0, "{sw}x{sh}: source cut splits a chroma row.");
                assert_eq!(band.dst_y % 2, 0, "{sw}x{sh}: target cut splits a chroma row.");
                src_next += band.src_h as usize;
                dst_next += band.dst_h;
            }
            assert_eq!(src_next, sh as usize, "{sw}x{sh}: bands do not cover every source row.");
            assert_eq!(dst_next, dh as usize, "{sw}x{sh}: bands do not cover every target row.");
        }
    }
}
