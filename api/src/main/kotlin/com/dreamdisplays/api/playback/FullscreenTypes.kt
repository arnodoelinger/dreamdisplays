package com.dreamdisplays.api.playback

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import kotlinx.serialization.Serializable

/**
 * Visual sub-mode of the fullscreen overlay. Travels on the wire as its [ordinal] int
 * ([wire] / [fromWire]); ordinals are append-only - never reorder or remove an entry.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
@Serializable
enum class FullscreenMode {
    /** Video letterboxed inside small margins; the game stays visible around the edges. */
    STANDARD,

    /** Video covers the entire screen on an opaque backdrop; nothing of the game shows through. */
    IMMERSIVE,

    ;

    /** The append-only wire value for this mode. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The mode for [wire], or [STANDARD] for unknown values (forward-compat with newer peers). */
        fun fromWire(wire: Int): FullscreenMode = byWire[wire] ?: STANDARD
    }
}

/**
 * Client acknowledgement of a fullscreen-broadcast transition (see `FullscreenAck`). Travels on
 * the wire as its [ordinal] int; ordinals are append-only.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
enum class FullscreenAckAction {
    /** The overlay was shown to the player. */
    SHOWN,

    /** The player closed an unforced broadcast overlay. */
    DISMISSED,

    /** The player minimized a forced broadcast overlay to PiP. */
    MINIMIZED,

    ;

    /** The append-only wire value for this action. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The action for [wire], or [SHOWN] for unknown values. */
        fun fromWire(wire: Int): FullscreenAckAction = byWire[wire] ?: SHOWN
    }
}
