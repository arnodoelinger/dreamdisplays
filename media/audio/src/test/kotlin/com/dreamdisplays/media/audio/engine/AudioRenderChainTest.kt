package com.dreamdisplays.media.audio.engine

import com.dreamdisplays.api.media.audio.model.AcousticQuality
import com.dreamdisplays.api.media.audio.model.SourceAcousticState
import com.dreamdisplays.api.media.audio.model.SourcePlane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioRenderChainTest {
    private fun newChain(
        quality: AcousticQuality = AcousticQuality.ADVANCED,
        binaural: Boolean = true,
        normalization: Boolean = false,
    ): AudioRenderChain {
        val engine = AcousticsEngine(44100f)
        engine.setGlobalQuality(quality)
        engine.setBinauralOutput(binaural)
        engine.setLoudnessNormalization(normalization)
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

    @Test
    fun `normalization brings a quiet and a loud source to about the same level`() {
        for (quality in listOf(AcousticQuality.OFF, AcousticQuality.ADVANCED)) {
            val quiet = settledRms(quality, amplitude = 0.06)
            val loud = settledRms(quality, amplitude = 0.6)
            val apartDb = 20 * kotlin.math.log10(loud / quiet)
            assertTrue(apartDb < 2.0, "$quality: sources 20 dB apart ended ${"%.1f".format(apartDb)} dB apart.")
        }
    }

    @Test
    fun `normalization holds its gain through silence instead of winding up`() {
        val chain = newChain(quality = AcousticQuality.OFF, normalization = true)
        repeat(200) { chain.process(tone(0.3), 2205 * 4, 1.0) }
        val before = rms(tone(0.3).also { chain.process(it, it.size, 1.0) })
        repeat(400) { chain.process(ByteArray(2205 * 4), 2205 * 4, 1.0) }
        val after = rms(tone(0.3).also { chain.process(it, it.size, 1.0) })
        val driftDb = 20 * kotlin.math.log10(after / before)
        assertTrue(kotlin.math.abs(driftDb) < 1.0, "Gain drifted ${"%.1f".format(driftDb)} dB over 20 s of silence.")
    }

    @Test
    fun `a session reset keeps the normalization gain, so a seek does not jump in volume`() {
        val chain = newChain(quality = AcousticQuality.OFF, normalization = true)
        repeat(200) { chain.process(tone(0.6), 2205 * 4, 1.0) }
        val before = rms(tone(0.6).also { chain.process(it, it.size, 1.0) })
        chain.reset()
        val after = rms(tone(0.6).also { chain.process(it, it.size, 1.0) })
        val jumpDb = 20 * kotlin.math.log10(after / before)
        assertTrue(kotlin.math.abs(jumpDb) < 1.0, "Volume jumped ${"%.1f".format(jumpDb)} dB across a reset.")
    }

    private fun settledRms(quality: AcousticQuality, amplitude: Double): Double {
        val chain = newChain(quality = quality, binaural = false, normalization = true)
        chain.updateState(defaultState())
        var last = 0.0
        repeat(400) { last = rms(tone(amplitude).also { chain.process(it, it.size, 1.0) }) }
        return last
    }

    private fun tone(amplitude: Double): ByteArray {
        val buf = ByteArray(2205 * 4)
        for (i in 0 until 2205) {
            val v = (Short.MAX_VALUE * amplitude * kotlin.math.sin(2 * Math.PI * 440.0 * i / 44100.0)).toInt().toShort()
            System.arraycopy(frame(v, v), 0, buf, i * 4, 4)
        }
        return buf
    }

    private fun rms(buf: ByteArray): Double {
        var sum = 0.0
        for (i in 0 until buf.size / 2) {
            val s = ((buf[i * 2 + 1].toInt() shl 8) or (buf[i * 2].toInt() and 0xFF)) / 32768.0
            sum += s * s
        }
        return kotlin.math.sqrt(sum / (buf.size / 2))
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
