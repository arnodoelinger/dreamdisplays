package com.dreamdisplays.api.media.audio

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Per-source audio DSP stage applied in place to interleaved S16LE PCM before it reaches the output
 * line. One instance is bound to a single playback source for its lifetime.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
interface AudioDspStage : AutoCloseable {
    /**
     * Processes [len] valid bytes of interleaved S16LE stereo PCM in [buf] in place. [legacyGain] is
     * the distance / user volume computed by the legacy `VolumeController` pipeline; a bypassed stage
     * (quality OFF, popout, or budget overflow) applies exactly that gain and nothing else, so
     * behavior matches the pre-acoustics pipeline bit for bit.
     */
    fun process(buf: ByteArray, len: Int, legacyGain: Double)

    /** Resets internal filter / delay-line state; called at the start of every fresh playback session. */
    fun reset()

    /** Close. */
    override fun close() {}
}
