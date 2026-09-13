package com.dreamdisplays.api.media.audio.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.audio.model.AcousticQuality
import com.dreamdisplays.api.media.audio.model.ListenerPose
import com.dreamdisplays.api.media.audio.model.SourceAcousticState
import java.util.*

/**
 * Owns acoustic source registry and per-source DSP chains; [registerSource] is idempotent.
 *
 * @since 1.9.x
 */
@Unstable
interface AudioAcousticsService {
    /** Registers [id] if not already known and returns its [AudioDspStage], creating it on first call. */
    fun registerSource(id: UUID): AudioDspStage

    /** Removes [id] and releases its chain. No-op if unknown. */
    fun unregisterSource(id: UUID)

    /** Publishes the latest geometry / mix state for [id]. No-op if [id] was never registered. */
    fun updateSource(id: UUID, state: SourceAcousticState)

    /** Publishes the listener's current world pose, shared by every registered source. */
    fun updateListener(pose: ListenerPose)

    /**
     * Sets the global quality ceiling (from user config); individual sources may still fall back to
     * [AcousticQuality.OFF] over the priority budget.
     */
    fun setGlobalQuality(quality: AcousticQuality)
}
