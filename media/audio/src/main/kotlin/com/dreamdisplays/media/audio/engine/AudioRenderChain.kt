package com.dreamdisplays.media.audio.engine

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.api.media.audio.ListenerPose
import com.dreamdisplays.api.media.audio.SourceAcousticState
import com.dreamdisplays.media.audio.dsp.Limiter
import com.dreamdisplays.media.audio.dsp.LoudnessMeter
import com.dreamdisplays.media.audio.dsp.ParamSmoother
import com.dreamdisplays.media.audio.math.Vec3
import com.dreamdisplays.media.audio.spatial.EmitterLayout
import com.dreamdisplays.media.audio.spatial.ParametricBinaural
import com.dreamdisplays.media.audio.spatial.StereoPanner
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-display DSP graph: renders the fixed-format S16LE stereo block from the media pipeline as an
 * area-source, direction-aware binaural (or speaker-pan) mix, in place. One instance per registered
 * display, reused across video swaps — only [reset] runs per playback session.
 */
internal class AudioRenderChain(
    private val sampleRate: Float,
    private val listenerRef: AtomicReference<ListenerPose>,
    private val qualityRef: AtomicReference<AcousticQuality>,
    private val binauralRef: AtomicReference<Boolean>,
) : AudioDspStage {
    private companion object {
        const val TARGET_LUFS = -16f
        const val MAX_LOUDNESS_ADJUST_DB = 12f
        const val MAX_LOUDNESS_SLEW_DB_PER_SEC = 0.5f
        const val GAIN_SMOOTH_SECONDS = 0.08f
        const val AZIMUTH_SMOOTH_SECONDS = 0.12f
    }

    @Volatile
    private var state: SourceAcousticState? = null

    /** Publishes the latest geometry / mix state; called from the game thread. */
    fun updateState(newState: SourceAcousticState) {
        state = newState
    }

    private val loudness = LoudnessMeter(sampleRate)
    private val limiter = Limiter(sampleRate)
    private val leftBinaural = ParametricBinaural(sampleRate)
    private val rightBinaural = ParametricBinaural(sampleRate)

    private val distanceGainL = ParamSmoother(GAIN_SMOOTH_SECONDS, 1f)
    private val distanceGainR = ParamSmoother(GAIN_SMOOTH_SECONDS, 1f)
    private val directivitySmoother = ParamSmoother(GAIN_SMOOTH_SECONDS, 1f)
    private val azimuthL = ParamSmoother(AZIMUTH_SMOOTH_SECONDS, 0f)
    private val azimuthR = ParamSmoother(AZIMUTH_SMOOTH_SECONDS, 0f)

    private var floatL = FloatArray(0)
    private var floatR = FloatArray(0)

    override fun process(buf: ByteArray, len: Int, legacyGain: Double) {
        val st = state
        val tier = qualityRef.get()
        if (tier == AcousticQuality.OFF || st == null || st.bypassSpatial || !st.acousticsEnabled) {
            applyLegacyGain(buf, len, legacyGain)
            return
        }

        val frames = len / 4
        if (frames <= 0) return
        ensureCapacity(frames)
        decode(buf, frames)

        val listener = listenerRef.get()
        val plane = st.plane
        val center = Vec3(plane.centerX, plane.centerY, plane.centerZ)
        val normal = Vec3(plane.normalX, plane.normalY, plane.normalZ)
        val uAxis = Vec3(plane.uAxisX, plane.uAxisY, plane.uAxisZ)
        val listenerPos = Vec3(listener.x, listener.y, listener.z)
        val listenerForward = Vec3(listener.forwardX, listener.forwardY, listener.forwardZ)
        val listenerUp = Vec3(listener.upX, listener.upY, listener.upZ)
        val listenerRight = (listenerForward cross listenerUp).normalized()

        val dtBlock = frames / sampleRate
        val refDistance = max(1.0, sqrt(plane.width * plane.height) * 0.5)
        val toCenterDir = (listenerPos - center).normalized()
        val directivity = directivitySmoother.next(
            EmitterLayout.directivityGain(normal, toCenterDir).toFloat(), dtBlock,
        )

        val leftEmitter = EmitterLayout.leftEmitter(center, uAxis, plane.width)
        val rightEmitter = EmitterLayout.rightEmitter(center, uAxis, plane.width)

        val gL = distanceGainL.next(
            EmitterLayout.distanceGain((listenerPos - leftEmitter).length(), refDistance).toFloat(), dtBlock,
        ) * directivity
        val gR = distanceGainR.next(
            EmitterLayout.distanceGain((listenerPos - rightEmitter).length(), refDistance).toFloat(), dtBlock,
        ) * directivity

        val azL = azimuthL.next(azimuthOf(leftEmitter, listenerPos, listenerForward, listenerRight), dtBlock)
        val azR = azimuthR.next(azimuthOf(rightEmitter, listenerPos, listenerForward, listenerRight), dtBlock)

        val binaural = binauralRef.get() && tier != AcousticQuality.BASIC
        if (binaural) {
            leftBinaural.updateParams(azL.toDouble())
            rightBinaural.updateParams(azR.toDouble())
        }

        val advanced = tier == AcousticQuality.ADVANCED || tier == AcousticQuality.ULTRA
        val userGain = if (st.muted) 0f else st.userVolume
        val makeup = if (advanced) {
            loudness.makeupGain(TARGET_LUFS, MAX_LOUDNESS_ADJUST_DB, MAX_LOUDNESS_SLEW_DB_PER_SEC, dtBlock)
        } else 1f

        val dtSample = 1f / sampleRate
        for (i in 0 until frames) {
            val rawL = floatL[i]; val rawR = floatR[i]
            loudness.observe(rawL, rawR, dtSample)

            val l = rawL * gL * userGain * makeup
            val r = rawR * gR * userGain * makeup

            val lPair = if (binaural) leftBinaural.renderSample(l) else StereoPanner.pan(l, azL.toDouble())
            val rPair = if (binaural) rightBinaural.renderSample(r) else StereoPanner.pan(r, azR.toDouble())

            var outL = lPair[0] + rPair[0]
            var outR = lPair[1] + rPair[1]
            if (advanced) {
                val limited = limiter.process(outL, outR)
                outL = limited[0]; outR = limited[1]
            }
            floatL[i] = outL
            floatR[i] = outR
        }

        encode(buf, frames)
    }

    override fun reset() {
        loudness.reset()
        limiter.reset()
        leftBinaural.reset()
        rightBinaural.reset()
        distanceGainL.snap(1f); distanceGainR.snap(1f)
        directivitySmoother.snap(1f)
        azimuthL.snap(0f); azimuthR.snap(0f)
    }

    private fun ensureCapacity(frames: Int) {
        if (floatL.size < frames) {
            floatL = FloatArray(frames)
            floatR = FloatArray(frames)
        }
    }

    private fun decode(buf: ByteArray, frames: Int) {
        var idx = 0
        for (i in 0 until frames) {
            val lLo = buf[idx].toInt() and 0xFF
            val lHi = buf[idx + 1].toInt()
            val rLo = buf[idx + 2].toInt() and 0xFF
            val rHi = buf[idx + 3].toInt()
            floatL[i] = ((lHi shl 8) or lLo) / 32768f
            floatR[i] = ((rHi shl 8) or rLo) / 32768f
            idx += 4
        }
    }

    private fun encode(buf: ByteArray, frames: Int) {
        var idx = 0
        for (i in 0 until frames) {
            val l = (floatL[i] * 32768f).toInt().coerceIn(-32768, 32767)
            val r = (floatR[i] * 32768f).toInt().coerceIn(-32768, 32767)
            buf[idx] = (l and 0xFF).toByte(); buf[idx + 1] = ((l shr 8) and 0xFF).toByte()
            buf[idx + 2] = (r and 0xFF).toByte(); buf[idx + 3] = ((r shr 8) and 0xFF).toByte()
            idx += 4
        }
    }

    private fun azimuthOf(emitter: Vec3, listenerPos: Vec3, forward: Vec3, right: Vec3): Float {
        val rel = emitter - listenerPos
        return atan2(rel dot right, rel dot forward).toFloat()
    }

    /**
     * Exactly [MediaBufferEffects.applyVolumeS16LE]'s algorithm,
     * duplicated so the bypass path is bit-for-bit identical without a dependency on `:media:player`.
     */
    private fun applyLegacyGain(buf: ByteArray, len: Int, gain: Double) {
        if (abs(gain - 1.0) < 1e-5) return
        var i = 0
        while (i + 1 < len) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val s = (hi shl 8) or lo
            val scaled = (s * gain).toInt().coerceIn(-32768, 32767)
            buf[i] = (scaled and 0xFF).toByte()
            buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}
