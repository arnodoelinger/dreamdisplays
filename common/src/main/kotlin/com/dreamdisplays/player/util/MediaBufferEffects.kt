package com.dreamdisplays.player.util

import kotlin.math.abs

/** Media buffer effects applied in place to raw audio buffers before uploading to the GPU. */
object MediaBufferEffects {
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
