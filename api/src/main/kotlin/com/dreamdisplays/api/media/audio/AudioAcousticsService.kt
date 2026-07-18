package com.dreamdisplays.api.media.audio

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import java.util.*

/**
 * Owns the acoustic source registry and per-source DSP chains for every playing display. Sources are
 * addressed by their display UUID; [registerSource] is idempotent so callers never need to check
 * whether a source already exists.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
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
