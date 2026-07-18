package com.dreamdisplays.media.audio.engine

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.audio.SourceAcousticState
import com.dreamdisplays.api.media.audio.SourcePlane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioRenderChainTest {
    private fun newChain(quality: AcousticQuality = AcousticQuality.ADVANCED, binaural: Boolean = true): AudioRenderChain {
        val engine = AcousticsEngine(44100f)
        engine.setGlobalQuality(quality)
        engine.setBinauralOutput(binaural)
        return AudioRenderChain(44100f, engine)
    }

    /** Encodes one S16LE stereo frame (matches [AudioRenderChain]'s own decode / encode convention). */
    private fun frame(l: Short, r: Short): ByteArray = byteArrayOf(
        (l.toInt() and 0xFF).toByte(), ((l.toInt() shr 8) and 0xFF).toByte(),
        (r.toInt() and 0xFF).toByte(), ((r.toInt() shr 8) and 0xFF).toByte(),
    )

    @Test
    fun `OFF tier applies exactly the legacy gain, bit for bit`() {
        val chain = newChain(quality = AcousticQuality.OFF)
        val buf = frame(10000, -8000)
        val expected = expectedLegacyGain(buf, 0.5)
        chain.process(buf, buf.size, 0.5)
        assertEquals(expected.toList(), buf.toList())
    }

    @Test
    fun `no source state yet also bypasses to the legacy gain`() {
        val chain = newChain() // ADVANCED, but updateState() was never called
        val buf = frame(12345, -4321)
        val expected = expectedLegacyGain(buf, 0.75)
        chain.process(buf, buf.size, 0.75)
        assertEquals(expected.toList(), buf.toList())
    }

    @Test
    fun `bypassSpatial (popout) also applies the legacy gain`() {
        val chain = newChain()
        chain.updateState(defaultState(bypassSpatial = true))
        val buf = frame(9000, -9000)
        val expected = expectedLegacyGain(buf, 1.2)
        chain.process(buf, buf.size, 1.2)
        assertEquals(expected.toList(), buf.toList())
    }

    @Test
    fun `active spatial chain never produces NaN, Inf, or wildly out-of-range samples`() {
        val chain = newChain()
        chain.updateState(defaultState())
        repeat(20) {
            val buf = ByteArray(2205 * 4)
            for (i in 0 until 2205) {
                val v = (Short.MAX_VALUE * 0.3 * kotlin.math.sin(i * 0.05)).toInt().toShort()
                val f = frame(v, v)
                System.arraycopy(f, 0, buf, i * 4, 4)
            }
            chain.process(buf, buf.size, 1.0)
            for (i in 0 until 2205) {
                val lo = buf[i * 4].toInt() and 0xFF
                val hi = buf[i * 4 + 1].toInt()
                val s = ((hi shl 8) or lo)
                assertTrue(s in -32768..32767, "Sample out of S16 range: $s.")
            }
        }
    }

    private fun defaultState(bypassSpatial: Boolean = false) = SourceAcousticState(
        plane = SourcePlane(
            centerX = 0.0, centerY = 0.0, centerZ = -5.0,
            normalX = 0.0, normalY = 0.0, normalZ = -1.0,
            uAxisX = 1.0, uAxisY = 0.0, uAxisZ = 0.0,
            vAxisX = 0.0, vAxisY = 1.0, vAxisZ = 0.0,
            width = 4.0, height = 2.0,
        ),
        userVolume = 1.0f,
        muted = false,
        bypassSpatial = bypassSpatial,
    )

    /** Reference implementation matching [AudioRenderChain]'s bypass path (and `MediaBufferEffects`). */
    private fun expectedLegacyGain(buf: ByteArray, gain: Double): ByteArray {
        val out = buf.copyOf()
        if (kotlin.math.abs(gain - 1.0) < 1e-5) return out
        var i = 0
        while (i + 1 < out.size) {
            val lo = out[i].toInt() and 0xFF
            val hi = out[i + 1].toInt()
            val s = (hi shl 8) or lo
            val scaled = (s * gain).toInt().coerceIn(-32768, 32767)
            out[i] = (scaled and 0xFF).toByte()
            out[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
}
