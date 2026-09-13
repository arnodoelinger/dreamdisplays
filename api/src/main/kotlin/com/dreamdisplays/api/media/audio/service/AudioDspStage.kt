package com.dreamdisplays.api.media.audio.service

import com.dreamdisplays.api.Unstable

/**
 * Per-source audio DSP stage applied to S16LE PCM in place; one per playback source.
 *
 * @since 1.9.x
 */
@Unstable
interface AudioDspStage : AutoCloseable {
    /** Processes [len] bytes of S16LE stereo PCM in [buf] in place; [legacyGain] = distance / volume. */
    fun process(buf: ByteArray, len: Int, legacyGain: Double)

    /** Resets internal filter / delay-line state; called at the start of every fresh playback session. */
    fun reset()

    /** Close. */
    override fun close() {}
}
