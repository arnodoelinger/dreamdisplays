package com.dreamdisplays.platform.client.render

import com.mojang.blaze3d.platform.NativeImage
import java.nio.ByteBuffer

/**
 * Ambient grid.
 */
object AmbientGrid {
    /** Tiny "picture" resolution the source is averaged into before blurring. */
    private const val PIC_W = 24
    private const val PIC_H = 14

    /**
     * Box-blur passes applied to the tiny picture and their radius (cells). Deliberately huge relative
     * to [PIC_W] / [PIC_H] — the goal is to destroy essentially all detail, leaving only a few soft,
     * smoothly-blended color regions (repeated box blur converges to a Gaussian blur).
     */
    private const val PIC_BLUR_PASSES = 5
    private const val PIC_BLUR_RADIUS = 6

    /** Final baked-in resolution — the already-blurred picture, bilinearly upsampled. */
    const val GRID_W = 96
    const val GRID_H = 54

    /** Light polish pass on the upsampled result, mostly to hide any residual bilinear seams. */
    private const val FINAL_BLUR_PASSES = 1
    private const val FINAL_BLUR_RADIUS = 2

    /** A blurred [GRID_W] x [GRID_H] grid of RGB channels (each 0..255), row-major (`y * GRID_W + x`). */
    class Grid(val r: IntArray, val g: IntArray, val b: IntArray)

    /** Downsamples [pixels] (a `w`x`h` ARGB array, e.g. from `BufferedImage.getRGB`) into a smooth [Grid]. */
    fun fromArgbPixels(pixels: IntArray, w: Int, h: Int): Grid {
        val rSums = LongArray(PIC_W * PIC_H)
        val gSums = LongArray(PIC_W * PIC_H)
        val bSums = LongArray(PIC_W * PIC_H)
        val counts = IntArray(PIC_W * PIC_H)
        for (i in pixels.indices) {
            val argb = pixels[i]
            accumulate(
                rSums, gSums, bSums, counts, i % w, i / w, w, h,
                (argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF,
            )
        }
        return build(rSums, gSums, bSums, counts)
    }

    /**
     * Downsamples a raw RGB24 / RGBA32 frame in [buf] (from its current position, [bytesPerPixel] per
     * texel) into a smooth [Grid]. Sparse-samples on a coarse stride rather than visiting every
     * pixel — plenty accurate for a handful of picture cells, and far cheaper for large (e.g.
     * 1920x1080) frames sampled periodically during playback.
     */
    fun fromFrameBuffer(buf: ByteBuffer, w: Int, h: Int, bytesPerPixel: Int): Grid {
        val rSums = LongArray(PIC_W * PIC_H)
        val gSums = LongArray(PIC_W * PIC_H)
        val bSums = LongArray(PIC_W * PIC_H)
        val counts = IntArray(PIC_W * PIC_H)
        val base = buf.position()
        val limit = buf.limit()
        val strideX = (w / 192).coerceAtLeast(1)
        val strideY = (h / 108).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val idx = base + (y * w + x) * bytesPerPixel
                if (idx + 2 < limit) {
                    val r = buf.get(idx).toInt() and 0xFF
                    val g = buf.get(idx + 1).toInt() and 0xFF
                    val b = buf.get(idx + 2).toInt() and 0xFF
                    accumulate(rSums, gSums, bSums, counts, x, y, w, h, r, g, b)
                }
                x += strideX
            }
            y += strideY
        }
        return build(rSums, gSums, bSums, counts)
    }

    /** Builds a one-shot [NativeImage] from [grid] — for textures that are created once and never re-uploaded. */
    fun toNativeImage(grid: Grid): NativeImage {
        val image = NativeImage(NativeImage.Format.RGBA, GRID_W, GRID_H, false)
        for (cy in 0 until GRID_H) {
            for (cx in 0 until GRID_W) {
                val idx = cy * GRID_W + cx
                val cellArgb = (0xFF shl 24) or (grid.r[idx] shl 16) or (grid.g[idx] shl 8) or grid.b[idx]
                //? if >=1.21.11 {
                image.setPixelABGR(cx, cy, argbToAbgr(cellArgb))
                //?} else
                /*image.setPixelRGBA(cx, cy, argbToAbgr(cellArgb))*/
            }
        }
        return image
    }

    /** Converts a Java `0xAARRGGBB` int to the `0xAABBGGRR` layout [NativeImage] pixel setters expect. */
    fun argbToAbgr(argb: Int): Int =
        (argb and 0xFF00FF00.toInt()) or ((argb shl 16) and 0x00FF0000) or ((argb shr 16) and 0xFF)

    private fun accumulate(
        rSums: LongArray, gSums: LongArray, bSums: LongArray, counts: IntArray,
        x: Int, y: Int, w: Int, h: Int, r: Int, g: Int, b: Int,
    ) {
        val cx = (x * PIC_W / w).coerceIn(0, PIC_W - 1)
        val cy = (y * PIC_H / h).coerceIn(0, PIC_H - 1)
        val idx = cy * PIC_W + cx
        rSums[idx] += r; gSums[idx] += g; bSums[idx] += b; counts[idx]++
    }

    private fun build(rSums: LongArray, gSums: LongArray, bSums: LongArray, counts: IntArray): Grid {
        var picR = IntArray(PIC_W * PIC_H)
        var picG = IntArray(PIC_W * PIC_H)
        var picB = IntArray(PIC_W * PIC_H)
        for (idx in picR.indices) {
            val n = counts[idx].coerceAtLeast(1)
            picR[idx] = (rSums[idx] / n).toInt()
            picG[idx] = (gSums[idx] / n).toInt()
            picB[idx] = (bSums[idx] / n).toInt()
        }

        // Blur the tiny picture itself first — this is what actually erases detail into soft blobs
        repeat(PIC_BLUR_PASSES) {
            picR = boxBlur(picR, PIC_W, PIC_H, PIC_BLUR_RADIUS)
            picG = boxBlur(picG, PIC_W, PIC_H, PIC_BLUR_RADIUS)
            picB = boxBlur(picB, PIC_W, PIC_H, PIC_BLUR_RADIUS)
        }

        // Only now enlarge it — the source is already smooth, so there's nothing left to alias
        var r = bilinearUpsample(picR)
        var g = bilinearUpsample(picG)
        var b = bilinearUpsample(picB)
        repeat(FINAL_BLUR_PASSES) {
            r = boxBlur(r, GRID_W, GRID_H, FINAL_BLUR_RADIUS)
            g = boxBlur(g, GRID_W, GRID_H, FINAL_BLUR_RADIUS)
            b = boxBlur(b, GRID_W, GRID_H, FINAL_BLUR_RADIUS)
        }
        return Grid(r, g, b)
    }

    /** Bilinearly upsamples a [PIC_W] x [PIC_H] grid into [GRID_W] x [GRID_H]. */
    private fun bilinearUpsample(src: IntArray): IntArray {
        val dst = IntArray(GRID_W * GRID_H)
        for (oy in 0 until GRID_H) {
            val fy = (((oy + 0.5f) / GRID_H) * PIC_H - 0.5f).coerceIn(0f, (PIC_H - 1).toFloat())
            val y0 = fy.toInt().coerceIn(0, PIC_H - 1)
            val y1 = (y0 + 1).coerceIn(0, PIC_H - 1)
            val ty = fy - y0
            for (ox in 0 until GRID_W) {
                val fx = (((ox + 0.5f) / GRID_W) * PIC_W - 0.5f).coerceIn(0f, (PIC_W - 1).toFloat())
                val x0 = fx.toInt().coerceIn(0, PIC_W - 1)
                val x1 = (x0 + 1).coerceIn(0, PIC_W - 1)
                val tx = fx - x0

                val c00 = src[y0 * PIC_W + x0]
                val c10 = src[y0 * PIC_W + x1]
                val c01 = src[y1 * PIC_W + x0]
                val c11 = src[y1 * PIC_W + x1]
                val top = c00 + (c10 - c00) * tx
                val bottom = c01 + (c11 - c01) * tx
                dst[oy * GRID_W + ox] = (top + (bottom - top) * ty).toInt()
            }
        }
        return dst
    }

    /** Separable box blur of a [gw] x [gh] single-channel grid, edge-clamped so borders don't darken. */
    private fun boxBlur(src: IntArray, gw: Int, gh: Int, radius: Int): IntArray {
        val tmp = IntArray(gw * gh)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                var sum = 0
                for (dx in -radius..radius) sum += src[y * gw + (x + dx).coerceIn(0, gw - 1)]
                tmp[y * gw + x] = sum / (radius * 2 + 1)
            }
        }
        val dst = IntArray(gw * gh)
        for (x in 0 until gw) {
            for (y in 0 until gh) {
                var sum = 0
                for (dy in -radius..radius) sum += tmp[(y + dy).coerceIn(0, gh - 1) * gw + x]
                dst[y * gw + x] = sum / (radius * 2 + 1)
            }
        }
        return dst
    }
}
