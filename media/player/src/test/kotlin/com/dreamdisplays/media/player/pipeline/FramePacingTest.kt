package com.dreamdisplays.media.player.pipeline

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FramePacingTest {
    private val ms = 1_000_000L

    @Test
    fun `a frame that is due is presented`() {
        assertFalse(FramePacing.pace(videoPts = 1_000 * ms, audioClock = { 1_000 * ms }))
    }

    @Test
    fun `a frame behind the clock is dropped by default`() {
        assertTrue(FramePacing.pace(videoPts = 1_000 * ms, audioClock = { 1_500 * ms }))
    }

    @Test
    fun `a frame behind the clock is presented when nothing fresher is waiting`() {
        assertFalse(
            FramePacing.pace(
                videoPts = 1_000 * ms,
                audioClock = { 1_500 * ms },
                dropWhenBehind = { false },
            )
        )
    }

    @Test
    fun `dropWhenBehind does not keep an on-time frame from being presented`() {
        assertFalse(
            FramePacing.pace(
                videoPts = 1_000 * ms,
                audioClock = { 1_020 * ms },
                dropWhenBehind = { true },
            )
        )
    }

    @Test
    fun `an aborted wait drops the frame`() {
        assertTrue(
            FramePacing.pace(
                videoPts = 5_000 * ms,
                audioClock = { 1_000 * ms },
                abort = { true },
            )
        )
    }
}
