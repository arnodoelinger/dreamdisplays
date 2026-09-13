package com.dreamdisplays.media.player.managers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamWatchdogTest {
    private fun watchdog(stamp: AtomicLong, stalled: CountDownLatch, active: () -> Boolean = { true }) =
        StreamWatchdog(
            debugLabel = "test",
            isSessionActive = active,
            getLastFrameNanos = stamp::get,
            stallThresholdMs = 10_000L,
            startupThresholdMs = 150L,
            checkIntervalMs = 20L,
            onStall = { stalled.countDown() },
        )

    @Test
    fun `a session that never delivers a first frame is cut off early`() {
        // The live-resume failure this guards: a session pointed at a playlist window the server has
        // already moved past never produces anything, and waiting out the full stall budget is pure
        // dead time for the viewer.
        val stamp = AtomicLong(System.nanoTime())
        val stalled = CountDownLatch(1)
        val wd = watchdog(stamp, stalled)
        wd.start()
        try {
            assertTrue(stalled.await(3, TimeUnit.SECONDS), "The startup budget should have expired by now.")
        } finally {
            wd.stop()
        }
    }

    @Test
    fun `a session that is delivering frames keeps the long budget`() {
        // Same silence, but frames arrived first: this is a running stream that hiccuped, and it is
        // given the full stall budget because those usually recover without a restart.
        val stamp = AtomicLong(System.nanoTime())
        val stalled = CountDownLatch(1)
        val wd = watchdog(stamp, stalled)
        wd.start()
        try {
            repeat(5) {
                Thread.sleep(40)
                stamp.set(System.nanoTime()) // A frame landed
            }
            assertFalse(
                stalled.await(500, TimeUnit.MILLISECONDS),
                "A stream that has delivered frames must not be restarted on the startup budget.",
            )
        } finally {
            wd.stop()
        }
    }

    @Test
    fun `a stall is reported once and then the watch ends`() {
        // Reporting it every tick is what turned recovery into a restart storm: the recovering
        // session has no frames yet either, so each tick tore down the attempt in progress and
        // asked for another, and nothing ever got far enough to show a picture.
        val stamp = AtomicLong(System.nanoTime())
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val wd = StreamWatchdog(
            debugLabel = "test",
            isSessionActive = { true },
            getLastFrameNanos = stamp::get,
            stallThresholdMs = 10_000L,
            startupThresholdMs = 100L,
            checkIntervalMs = 20L,
            onStall = { calls.incrementAndGet() },
        )
        wd.start()
        try {
            Thread.sleep(800) // ~35 ticks past the startup budget
            assertEquals(1, calls.get(), "The stall should have been reported exactly once.")
        } finally {
            wd.stop()
        }
    }

    @Test
    fun `an inactive session is never restarted`() {
        val stamp = AtomicLong(System.nanoTime())
        val stalled = CountDownLatch(1)
        val wd = watchdog(stamp, stalled, active = { false })
        wd.start()
        try {
            assertFalse(stalled.await(500, TimeUnit.MILLISECONDS), "A parked or stopped session is not a stall.")
        } finally {
            wd.stop()
        }
    }
}
