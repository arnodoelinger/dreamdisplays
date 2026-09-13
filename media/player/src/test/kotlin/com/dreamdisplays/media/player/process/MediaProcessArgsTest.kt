package com.dreamdisplays.media.player.process

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaProcessArgsTest {
    private val url = "https://cdn.example.com/vod/v1/prog_index.m3u8"

    private val httpOnly = listOf(
        "-headers", "-reconnect", "-reconnect_streamed", "-reconnect_delay_max",
        "-reconnect_on_network_error", "-reconnect_on_http_error", "-multiple_requests",
    )

    private fun args(offsetNanos: Long, seekByDecoding: Boolean, trimmed: HlsSeekPlaylist.Trimmed? = null) =
        MediaProcess.inputCommand("FFMPEG", url, offsetNanos, HwAccelBackend.NONE, seekByDecoding, trimmed)

    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

    @Test
    fun `an ordinary input asks the demuxer to jump`() {
        val a = args(30_000_000_000L, seekByDecoding = false)
        assertEquals(url, a.valueOf("-i"))
        assertTrue(a.indexOf("-ss") < a.indexOf("-i"), "A demuxer seek must come before the input.")
        assertEquals("30.000000", a.valueOf("-ss"))
        assertTrue(a.containsAll(httpOnly), "An http input should keep its connection options.")
    }

    @Test
    fun `an inline playlist carries no http-protocol options`() {
        val trimmed = HlsSeekPlaylist.Trimmed("data:application/vnd.apple.mpegurl;base64,QUJD", 2_500_000_000L)
        val a = args(300_000_000_000L, seekByDecoding = true, trimmed = trimmed)

        assertEquals(trimmed.url, a.valueOf("-i"))
        httpOnly.forEach { assertFalse(it in a, "$it cannot be applied to a data: input.") }
        assertEquals("3", a.valueOf("-seg_max_retry"))
        assertContentEquals(listOf("-f", "hls"), a.subList(a.indexOf("-f"), a.indexOf("-f") + 2))
        assertEquals("2.500000", a.valueOf("-ss"))
        assertTrue(a.indexOf("-ss") > a.indexOf("-i"), "The residual is an output-side skip.")
    }

    @Test
    fun `a playlist that could not be trimmed falls back to decoding forward`() {
        val a = args(300_000_000_000L, seekByDecoding = true, trimmed = null)

        assertEquals(url, a.valueOf("-i"))
        assertTrue(a.indexOf("-ss") > a.indexOf("-i"), "This demuxer cannot be asked to jump.")
        assertEquals("300.000000", a.valueOf("-ss"))
        assertTrue(a.containsAll(httpOnly), "The fallback is still an http input.")
    }

    @Test
    fun `playback from the start never asks for a seek`() {
        for (seekByDecoding in listOf(false, true)) {
            val a = args(0L, seekByDecoding)
            assertFalse("-ss" in a, "Nothing to seek to at offset 0 (seekByDecoding=$seekByDecoding).")
            assertEquals(url, a.valueOf("-i"))
        }
    }
}
