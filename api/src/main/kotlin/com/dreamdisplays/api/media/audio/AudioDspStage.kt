package com.dreamdisplays.api.media.audio

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Per-source audio DSP stage applied to S16LE PCM in place; one per playback source.
 *
 * @since 1.9.x
 */
@DreamDisplaysUnstableApi
interface AudioDspStage : AutoCloseable {
    /** Processes [len] bytes of S16LE stereo PCM in [buf] in place; [legacyGain] = distance / volume. */
    fun process(buf: ByteArray, len: Int, legacyGain: Double)

    /** Resets internal filter / delay-line state; called at the start of every fresh playback session. */
    fun reset()

    /** The most recently published geometry / mix state, or null before the first [process] update. */
    fun latestState(): SourceAcousticState? = null

    /** Close. */
    override fun close() {}
}
