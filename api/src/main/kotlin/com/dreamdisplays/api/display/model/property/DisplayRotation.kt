package com.dreamdisplays.api.display.model.property

import com.dreamdisplays.api.Unstable

/**
 * Display texture rotation in quarter turns; enum centralizes the `0..3` contract.
 *
 * @since 1.8.x
 */
@Unstable
enum class DisplayRotation(val quarterTurns: Int) {
    /** No content rotation. */
    NONE(0),

    /** Rotate content one quarter turn to the right. */
    RIGHT(1),

    /** Rotate content by half a turn. */
    HALF_TURN(2),

    /** Rotate content one quarter turn to the left. */
    LEFT(3);

    companion object {
        /** By quarter turns lookup table. */
        private val byQuarterTurns = entries.associateBy { it.quarterTurns }

        /** Decodes a rotation from persisted quarter turns, wrapping out-of-range values. */
        fun fromQuarterTurns(raw: Int): DisplayRotation =
            byQuarterTurns[Math.floorMod(raw, entries.size)] ?: NONE
    }
}
