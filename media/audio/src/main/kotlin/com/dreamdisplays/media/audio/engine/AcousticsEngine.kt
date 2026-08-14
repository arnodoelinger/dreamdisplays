package com.dreamdisplays.media.audio.engine

import com.dreamdisplays.api.media.audio.*
import kotlinx.atomicfu.atomic
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [AudioAcousticsService]: the acoustics DSP itself runs natively (`dreamdisplays_lav`,
 * see `RenderChain` in `native/lav/src/acoustics.rs`), so this only holds each registered
 * source's latest published state (read by [com.dreamdisplays.api.media.audio.AudioDspStage.latestState]
 * and polled across the FFI boundary) and forwards the shared listener pose / quality tier /
 * binaural toggle to the native engine.
 */
class AcousticsEngine(
    /** Forwards the listener pose to another consumer (the native audio engine) whenever it changes. */
    private val onListenerChanged: (ListenerPose) -> Unit = {},

    /** Forwards the global quality ceiling to another consumer whenever it changes. */
    private val onQualityChanged: (AcousticQuality) -> Unit = {},

    /** Forwards the binaural toggle to another consumer whenever it changes. */
    private val onBinauralChanged: (Boolean) -> Unit = {},
) : AudioAcousticsService {
    /** Holds one registered source's latest published state. */
    private class SourceStateHolder : AudioDspStage {
        @Volatile
        private var state: SourceAcousticState? = null

        fun updateState(newState: SourceAcousticState) {
            state = newState
        }

        override fun process(buf: ByteArray, len: Int, legacyGain: Double) {}
        override fun reset() {}
        override fun latestState(): SourceAcousticState? = state
    }

    private val sources = ConcurrentHashMap<UUID, SourceStateHolder>()
    private val listenerRef = atomic(ListenerPose.IDENTITY)
    private val qualityRef = atomic(AcousticQuality.ADVANCED)
    private val binauralRef = atomic(true)

    /** Selects binaural (headphone) rendering vs. constant-power stereo pan for every source. */
    fun setBinauralOutput(binaural: Boolean) {
        binauralRef.value = binaural
        onBinauralChanged(binaural)
    }

    override fun registerSource(id: UUID): AudioDspStage =
        sources.computeIfAbsent(id) { SourceStateHolder() }

    override fun unregisterSource(id: UUID) {
        sources.remove(id)?.close()
    }

    override fun updateSource(id: UUID, state: SourceAcousticState) {
        sources[id]?.updateState(state)
    }

    override fun updateListener(pose: ListenerPose) {
        listenerRef.value = pose
        onListenerChanged(pose)
    }

    override fun setGlobalQuality(quality: AcousticQuality) {
        qualityRef.value = quality
        onQualityChanged(quality)
    }
}
