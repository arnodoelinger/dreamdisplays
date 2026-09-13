package com.dreamdisplays.media.player.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackClockTest {
    private val second = 1_000_000_000L

    @Test
    fun `a fresh clock reports its origin and is not running`() {
        val clock = PlaybackClock()
        clock.reset(5 * second)
        assertFalse(clock.isRunning)
        assertEquals(5 * second, clock.originNanos)
        assertEquals(5 * second, clock.currentTime())
    }

    @Test
    fun `a running clock advances from its origin`() {
        val clock = PlaybackClock()
        clock.reset(10 * second)
        clock.markFirstFrame()
        assertTrue(clock.isRunning)
        Thread.sleep(20)
        val elapsed = clock.currentTime() - 10 * second
        assertTrue(elapsed in 10_000_000L..500_000_000L, "Expected ~20 ms of elapsed time, got $elapsed ns.")
    }

    @Test
    fun `markFirstFrame only arms the clock once`() {
        val clock = PlaybackClock()
        clock.reset(0)
        clock.markFirstFrame()
        Thread.sleep(30)
        clock.markFirstFrame() // A second call must not restart the elapsed measurement
        assertTrue(clock.currentTime() >= 20_000_000L, "The second markFirstFrame reset the elapsed time.")
    }

    @Test
    fun `moveTo on a running clock replaces the position instead of stacking on the elapsed time`() {
        val clock = PlaybackClock()
        clock.reset(0)
        clock.markFirstFrame()
        Thread.sleep(60)

        // This is the case that used to corrupt playback: a caller correcting the position to
        // "wherever we are now" would previously add the whole elapsed interval on top of it,
        // jumping the reported position (and everything paced against it) forward.
        val here = clock.currentTime()
        clock.moveTo(here)

        val afterMove = clock.currentTime()
        assertTrue(
            afterMove - here < 50_000_000L,
            "moveTo jumped the clock by ${(afterMove - here) / 1_000_000} ms instead of holding the position.",
        )
        assertTrue(clock.isRunning, "moveTo must not stop a running clock.")
    }

    @Test
    fun `moveTo on a stopped clock re-origins it and leaves it stopped`() {
        val clock = PlaybackClock()
        clock.reset(0)
        clock.moveTo(42 * second)
        assertFalse(clock.isRunning)
        assertEquals(42 * second, clock.currentTime())
    }

    @Test
    fun `rebaseTo keeps the clock running at the new origin`() {
        val clock = PlaybackClock()
        clock.reset(0)
        clock.markFirstFrame()
        Thread.sleep(20)
        clock.rebaseTo(100 * second)
        assertTrue(clock.isRunning)
        val drift = clock.currentTime() - 100 * second
        assertTrue(drift in 0..100_000_000L, "Expected to resume at the rebase point, drifted $drift ns.")
    }

    @Test
    fun `addPausedDuration excludes the parked interval from elapsed time`() {
        val clock = PlaybackClock()
        clock.reset(0)
        clock.markFirstFrame()
        Thread.sleep(20)
        val beforePark = clock.currentTime()
        Thread.sleep(80) // Stand in for a dormant interval
        clock.addPausedDuration(80_000_000L)
        val afterPark = clock.currentTime()
        assertTrue(
            afterPark - beforePark < 60_000_000L,
            "The parked interval leaked into the position (+${(afterPark - beforePark) / 1_000_000} ms).",
        )
    }

    @Test
    fun `addPausedDuration is a no-op on a stopped clock`() {
        val clock = PlaybackClock()
        clock.reset(7 * second)
        clock.addPausedDuration(second)
        assertEquals(7 * second, clock.currentTime())
    }
}
