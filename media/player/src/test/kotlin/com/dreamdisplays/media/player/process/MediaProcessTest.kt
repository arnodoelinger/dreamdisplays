package com.dreamdisplays.media.player.process

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaProcessTest {
    @Test
    fun `a plausible source rate is passed through unchanged`() {
        assertEquals(29.97, MediaProcess.outputFps(29.97))
        assertEquals(60.0, MediaProcess.outputFps(60.0))
        assertEquals(23.976, MediaProcess.outputFps(23.976))
    }

    @Test
    fun `a missing or nonsensical source rate falls back to the default`() {
        assertEquals(MediaProcess.DEFAULT_OUTPUT_FPS, MediaProcess.outputFps(null))
        assertEquals(MediaProcess.DEFAULT_OUTPUT_FPS, MediaProcess.outputFps(0.0))
        assertEquals(MediaProcess.DEFAULT_OUTPUT_FPS, MediaProcess.outputFps(-30.0))
        assertEquals(MediaProcess.DEFAULT_OUTPUT_FPS, MediaProcess.outputFps(1.0))
        assertEquals(MediaProcess.DEFAULT_OUTPUT_FPS, MediaProcess.outputFps(100_000.0))
        assertEquals(MediaProcess.DEFAULT_OUTPUT_FPS, MediaProcess.outputFps(Double.NaN))
        assertEquals(MediaProcess.DEFAULT_OUTPUT_FPS, MediaProcess.outputFps(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `a silent source is told apart from a broken one`() {
        // Verbatim from FFmpeg 7.1 asked for audio out of a video-only mp4. Reading this as a dying
        // session is what locked the player into an endless invalidate-and-re-resolve loop.
        val silent = """
            [out#0/s16le @ 0x140e155c0] Output file does not contain any stream
            Error opening output file -.
            Error opening output files: Invalid argument
        """.trimIndent()
        assertTrue(MediaProcess.indicatesNoAudioStream(silent))

        // The same empty output, but because the input never opened: that one must still retry.
        val unreachable = """
            [https @ 0x600] Server returned 403 Forbidden (access denied)
            [out#0/s16le @ 0x140] Output file does not contain any stream
            Error opening output files: Invalid argument
        """.trimIndent()
        assertFalse(MediaProcess.indicatesNoAudioStream(unreachable))

        assertFalse(MediaProcess.indicatesNoAudioStream(""))
        assertFalse(MediaProcess.indicatesNoAudioStream("Connection to tcp://host failed: Broken pipe"))
    }

    @Test
    fun `the rate handed to FFmpeg survives its own formatting`() {
        // FFmpeg is pinned with a fixed-point rendering of this value while the reader derives its
        // frame period from the double. If the two disagreed, the synthesized timeline would run at a
        // slightly different rate than the frames actually arrive and lip sync would drift linearly.
        for (fps in listOf(23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0)) {
            val pinned = String.format(java.util.Locale.US, "%.6f", MediaProcess.outputFps(fps)).toDouble()
            val readerFrameNs = (1_000_000_000.0 / MediaProcess.outputFps(fps)).toLong()
            val pinnedFrameNs = 1_000_000_000.0 / pinned
            // One hour of frames must not accumulate more than a millisecond of divergence
            val framesPerHour = (3600.0 * pinned).toLong()
            val divergenceNs = abs(readerFrameNs - pinnedFrameNs) * framesPerHour
            assertTrue(
                divergenceNs < 1_000_000.0,
                "At $fps fps the reader and FFmpeg diverge by ${divergenceNs / 1e6} ms per hour.",
            )
        }
    }
}
