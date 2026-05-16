package com.dreamdisplays.player.util

import kotlin.math.abs

/**
 * Per-sample / pixel adjustments applied to raw RGBA video frames and S16LE
 * audio before they reach the GPU / audio line.
 *
 * Both operate on heap `ByteArray`s using 8.8 fixed-point integer math –
 * orders of magnitude faster than per-byte `ByteBuffer.get`/`put` calls,
 * especially in the hot 1080p+ video path.
 */
object MediaBufferEffects {

    /**
     * Apply brightness in place to an interleaved RGBA buffer (alpha untouched).
     * `len` is the number of valid bytes (must be a multiple of 4).
     */
    fun applyBrightness(buf: ByteArray, len: Int, brightness: Double) {
        if (abs(brightness - 1.0) < 1e-5) return
        val factor = (brightness * 256.0 + 0.5).toInt().coerceIn(0, 512)
        var i = 0
        while (i + 3 < len) {
            val r = ((buf[i].toInt() and 0xFF) * factor) shr 8
            val g = ((buf[i + 1].toInt() and 0xFF) * factor) shr 8
            val b = ((buf[i + 2].toInt() and 0xFF) * factor) shr 8
            buf[i] = (if (r > 255) 255 else r).toByte()
            buf[i + 1] = (if (g > 255) 255 else g).toByte()
            buf[i + 2] = (if (b > 255) 255 else b).toByte()
            // buf[i+3] = alpha
            i += 4
        }
    }

    /**
     * Apply volume in place to an interleaved S16LE buffer. `len` is the number of valid bytes (must be a multiple of 2).
     */
    fun applyVolumeS16LE(buf: ByteArray, len: Int, volume: Double) {
        if (abs(volume - 1.0) < 1e-5) return
        var i = 0
        while (i + 1 < len) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val s = (hi shl 8) or lo
            val scaled = (s * volume).toInt().coerceIn(-32768, 32767)
            buf[i] = (scaled and 0xFF).toByte()
            buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}
