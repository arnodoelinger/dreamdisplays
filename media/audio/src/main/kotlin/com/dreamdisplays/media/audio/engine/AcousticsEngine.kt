package com.dreamdisplays.media.audio.engine

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.audio.AudioAcousticsService
import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.api.media.audio.ListenerPose
import com.dreamdisplays.api.media.audio.SourceAcousticState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.atomicfu.atomic

/**
 * Default [AudioAcousticsService]: owns one [AudioRenderChain] per registered display and the shared
 * listener pose / global quality tier / output profile they all read from.
 */
class AcousticsEngine(private val sampleRate: Float = 44100f) : AudioAcousticsService {
    private val chains = ConcurrentHashMap<UUID, AudioRenderChain>()
    private val listenerRef = atomic(ListenerPose.IDENTITY)
    private val qualityRef = atomic(AcousticQuality.ADVANCED)
    private val binauralRef = atomic(true)

    internal fun currentListener(): ListenerPose = listenerRef.value
    internal fun currentQuality(): AcousticQuality = qualityRef.value
    internal fun currentBinaural(): Boolean = binauralRef.value

    /** Selects binaural (headphone) rendering vs. constant-power stereo pan for every source. */
    fun setBinauralOutput(binaural: Boolean) {
        binauralRef.value = binaural
    }

    override fun registerSource(id: UUID): AudioDspStage =
        chains.computeIfAbsent(id) { AudioRenderChain(sampleRate, this) }

    override fun unregisterSource(id: UUID) {
        chains.remove(id)?.close()
    }

    override fun updateSource(id: UUID, state: SourceAcousticState) {
        chains[id]?.updateState(state)
    }

    override fun updateListener(pose: ListenerPose) {
        listenerRef.value = pose
    }

    override fun setGlobalQuality(quality: AcousticQuality) {
        qualityRef.value = quality
    }
}
