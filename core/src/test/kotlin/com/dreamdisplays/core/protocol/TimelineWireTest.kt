package com.dreamdisplays.core.protocol

import com.dreamdisplays.core.playback.PlaybackMode
import com.dreamdisplays.core.playback.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineWireTest {
    @Test fun syncRoundTripsThroughTimelineWire() {
        val original = Timeline(positionMs = 2_000, serverTimeMs = 50_000, paused = false, durationMs = 600_000)
        val sync = original.toSync(ZERO_UUID, PlaybackMode.SYNCED, nowMs = 50_000)

        assertEquals(PlaybackMode.SYNCED.wire, sync.mode)
        assertTrue(sync.isSync)
        assertEquals(original, sync.toTimeline())
    }
}
