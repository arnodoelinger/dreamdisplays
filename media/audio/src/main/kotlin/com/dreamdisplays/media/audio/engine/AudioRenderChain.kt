package com.dreamdisplays.media.audio.engine

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.api.media.audio.SourceAcousticState
import com.dreamdisplays.media.audio.dsp.*
import com.dreamdisplays.media.audio.math.Vec3
import com.dreamdisplays.media.audio.spatial.EmitterLayout
import com.dreamdisplays.media.audio.spatial.ParametricBinaural
import com.dreamdisplays.media.audio.spatial.StereoPanner
import kotlin.math.*

/**
 * Per-display DSP graph: renders the fixed-format S16LE stereo block from the media pipeline as an
 * area-source, direction-aware binaural (or speaker-pan) mix, in place. One instance per registered
 * display, reused across video swaps — only [reset] runs per playback session.
 */
internal class AudioRenderChain(
    private val sampleRate: Float,
    private val engine: AcousticsEngine,
) : AudioDspStage {
    private companion object {
        const val TARGET_LUFS = -16f
        const val MAX_LOUDNESS_ADJUST_DB = 12f
        const val MAX_LOUDNESS_SLEW_DB_PER_SEC = 0.5f
        const val GAIN_SMOOTH_SECONDS = 0.08f
        const val AZIMUTH_SMOOTH_SECONDS = 0.06f
        const val OCCLUSION_SMOOTH_SECONDS = 0.10f
        const val REVERB_SMOOTH_SECONDS = 0.15f
        const val MAX_CUTOFF_HZ = 18000f
        const val MIN_OCCLUSION_CUTOFF_HZ = 550f
        const val OCCLUSION_MIN_GAIN = 0.5f
        const val AIR_ABSORPTION_REF_DISTANCE = 16f
        const val AIR_ABSORPTION_MIN_CUTOFF_HZ = 6000f
        const val REVERB_MAX_DECAY_SECONDS = 3.0f
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
    private val leftPanner = StereoPanner()
    private val rightPanner = StereoPanner()

    private val distanceGainL = ParamSmoother(GAIN_SMOOTH_SECONDS, 1f)
    private val distanceGainR = ParamSmoother(GAIN_SMOOTH_SECONDS, 1f)
    private val directivitySmoother = ParamSmoother(GAIN_SMOOTH_SECONDS, 1f)
    private val azimuthL = ParamSmoother(AZIMUTH_SMOOTH_SECONDS, 0f)
    private val azimuthR = ParamSmoother(AZIMUTH_SMOOTH_SECONDS, 0f)

    private val occlusionFilterL = Biquad()
    private val occlusionFilterR = Biquad()
    private val reverb = Reverb(sampleRate)
    private val occlusionCutoff = ParamSmoother(OCCLUSION_SMOOTH_SECONDS, MAX_CUTOFF_HZ)
    private val occlusionGain = ParamSmoother(GAIN_SMOOTH_SECONDS, 1f)
    private val reverbWet = ParamSmoother(REVERB_SMOOTH_SECONDS, 0f)

    private var floatL = FloatArray(0)
    private var floatR = FloatArray(0)

    override fun process(buf: ByteArray, len: Int, legacyGain: Double) {
        val st = state
        val tier = engine.currentQuality()
        if (tier == AcousticQuality.OFF || st == null || st.bypassSpatial || !st.acousticsEnabled) {
            applyLegacyGain(buf, len, legacyGain)
            return
        }

        val frames = len / 4
        if (frames <= 0) return
        ensureCapacity(frames)
        decode(buf, frames)

        val listener = engine.currentListener()
        val plane = st.plane
        val center = Vec3(plane.centerX, plane.centerY, plane.centerZ)
        val normal = Vec3(plane.normalX, plane.normalY, plane.normalZ)
        val uAxis = Vec3(plane.uAxisX, plane.uAxisY, plane.uAxisZ)
        val listenerPos = Vec3(listener.x, listener.y, listener.z)
        val listenerForward = Vec3(listener.forwardX, listener.forwardY, listener.forwardZ)
        val listenerUp = Vec3(listener.upX, listener.upY, listener.upZ)
        val listenerRight = (listenerForward cross listenerUp).normalized()

        val dtBlock = frames / sampleRate
        val refDistance = max(4.0, sqrt(plane.width * plane.height) * 0.5)
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

        val binaural = engine.currentBinaural() && tier != AcousticQuality.BASIC
        if (binaural) {
            leftBinaural.updateParams(azL.toDouble())
            rightBinaural.updateParams(azR.toDouble())
        }

        val advanced = tier == AcousticQuality.ADVANCED || tier == AcousticQuality.ULTRA
        val userGain = if (st.muted) 0f else st.userVolume
        val makeup = if (advanced) {
            loudness.makeupGain(TARGET_LUFS, MAX_LOUDNESS_ADJUST_DB, MAX_LOUDNESS_SLEW_DB_PER_SEC, dtBlock)
        } else 1f

        val env = st.environment
        val occ = env.occlusion.coerceIn(0f, 1f)
        val cutoffTarget = if (advanced) {
            val occCutoff = MAX_CUTOFF_HZ * (MIN_OCCLUSION_CUTOFF_HZ / MAX_CUTOFF_HZ).pow(occ)
            val centerDist = max(AIR_ABSORPTION_REF_DISTANCE, (listenerPos - center).length().toFloat())
            val airCutoff =
                max(AIR_ABSORPTION_MIN_CUTOFF_HZ, MAX_CUTOFF_HZ * (AIR_ABSORPTION_REF_DISTANCE / centerDist))
            min(occCutoff, airCutoff)
        } else MAX_CUTOFF_HZ
        val cutoff = occlusionCutoff.next(cutoffTarget, dtBlock)
        occlusionFilterL.configure(Biquad.Type.LOW_PASS, sampleRate, cutoff, 0.707f)
        occlusionFilterR.configure(Biquad.Type.LOW_PASS, sampleRate, cutoff, 0.707f)
        val occGain = occlusionGain.next(if (advanced) 1f - occ * (1f - OCCLUSION_MIN_GAIN) else 1f, dtBlock)

        val reverbTargetWet = if (advanced) env.reverbWetGain.coerceIn(0f, 1f) else 0f
        val wetGain = reverbWet.next(reverbTargetWet, dtBlock)
        val reverbActive = wetGain > 1e-3f || reverbTargetWet > 1e-3f // -60 dB threshold
        if (reverbActive) {
            reverb.updateParams((env.reverbDecaySeconds / REVERB_MAX_DECAY_SECONDS).coerceIn(0f, 1f), env.reverbDamping)
        }

        val dtSample = 1f / sampleRate
        for (i in 0 until frames) {
            val rawL = floatL[i];
            val rawR = floatR[i]
            loudness.observe(rawL, rawR, dtSample)

            val srcL = if (advanced) occlusionFilterL.process(rawL) else rawL
            val srcR = if (advanced) occlusionFilterR.process(rawR) else rawR
            val l = srcL * gL * userGain * makeup * occGain
            val r = srcR * gR * userGain * makeup * occGain

            if (binaural) {
                leftBinaural.renderSample(l)
                rightBinaural.renderSample(r)
                var outL = leftBinaural.lastL + rightBinaural.lastL
                var outR = leftBinaural.lastR + rightBinaural.lastR

                if (reverbActive) {
                    reverb.process((l + r) * 0.5f)
                    outL += reverb.lastL * wetGain
                    outR += reverb.lastR * wetGain
                }

                if (advanced) {
                    limiter.process(outL, outR)
                    outL = limiter.lastL
                    outR = limiter.lastR
                }
                floatL[i] = outL
                floatR[i] = outR
            } else {
                leftPanner.pan(l, azL.toDouble())
                rightPanner.pan(r, azR.toDouble())
                var outL = leftPanner.lastL + rightPanner.lastL
                var outR = leftPanner.lastR + rightPanner.lastR

                if (reverbActive) {
                    reverb.process((l + r) * 0.5f)
                    outL += reverb.lastL * wetGain
                    outR += reverb.lastR * wetGain
                }

                if (advanced) {
                    limiter.process(outL, outR)
                    outL = limiter.lastL
                    outR = limiter.lastR
                }
                floatL[i] = outL
                floatR[i] = outR
            }
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
        occlusionFilterL.reset(); occlusionFilterR.reset()
        reverb.reset()
        occlusionCutoff.snap(MAX_CUTOFF_HZ)
        occlusionGain.snap(1f)
        reverbWet.snap(0f)
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
