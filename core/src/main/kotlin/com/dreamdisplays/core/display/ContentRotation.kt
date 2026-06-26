package com.dreamdisplays.core.display

/** Content texture rotation in quarter turns. Wire / storage boundaries persist [quarterTurns]. */
enum class ContentRotation(val quarterTurns: Int) {
    NONE(0),
    RIGHT(1),
    HALF_TURN(2),
    LEFT(3);

    companion object {
        private val byQuarterTurns = entries.associateBy { it.quarterTurns }

        fun fromQuarterTurns(raw: Int): ContentRotation =
            byQuarterTurns[Math.floorMod(raw, entries.size)] ?: NONE
    }
}
