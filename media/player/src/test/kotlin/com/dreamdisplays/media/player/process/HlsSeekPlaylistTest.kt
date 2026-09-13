package com.dreamdisplays.media.player.process

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsSeekPlaylistTest {
    private val base = "https://cdn.example.com/vod/v1/prog_index.m3u8"

    private fun fmp4(segments: Int = 5, duration: String = "6.00000"): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-VERSION:7")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        appendLine("#EXT-X-MAP:URI=\"main.mp4\",BYTERANGE=\"721@0\"")
        repeat(segments) { i ->
            appendLine("#EXTINF:$duration,\t")
            appendLine("#EXT-X-BYTERANGE:100000@${721 + i * 100_000}")
            appendLine("main.mp4")
        }
        appendLine("#EXT-X-ENDLIST")
    }

    private fun decode(trimmed: HlsSeekPlaylist.Trimmed): String {
        val payload = trimmed.url.substringAfter("base64,")
        return String(Base64.getDecoder().decode(payload))
    }

    @Test
    fun `the trimmed playlist starts at the segment holding the target`() {
        val trimmed = assertNotNull(HlsSeekPlaylist.trimText(fmp4(), base, 14_000_000_000L))
        val text = decode(trimmed)

        assertEquals(3, text.lines().count { it == "https://cdn.example.com/vod/v1/main.mp4" })
        assertEquals(2_000_000_000L, trimmed.residualNanos)
        assertContains(text, "#EXT-X-ENDLIST")
    }

    @Test
    fun `the init segment survives the trim and is made absolute`() {
        // Losing this line is the entire bug being worked around: without the init segment the
        // demuxer has no sample description and decodes nothing at all.
        val text = decode(assertNotNull(HlsSeekPlaylist.trimText(fmp4(), base, 14_000_000_000L)))
        assertContains(text, "#EXT-X-MAP:URI=\"https://cdn.example.com/vod/v1/main.mp4\",BYTERANGE=\"721@0\"")
    }

    @Test
    fun `byte ranges are carried over with their segments`() {
        val text = decode(assertNotNull(HlsSeekPlaylist.trimText(fmp4(), base, 14_000_000_000L)))
        // Third segment onwards; the first two ranges must be gone or the wrong bytes get decoded
        assertContains(text, "#EXT-X-BYTERANGE:100000@200721")
        assertTrue("#EXT-X-BYTERANGE:100000@721" !in text)
    }

    @Test
    fun `a target inside the first segment keeps the whole playlist`() {
        val trimmed = assertNotNull(HlsSeekPlaylist.trimText(fmp4(), base, 3_000_000_000L))
        assertEquals(5, decode(trimmed).lines().count { it.startsWith("https://") })
        assertEquals(3_000_000_000L, trimmed.residualNanos)
    }

    @Test
    fun `nothing is produced for cases the caller must handle itself`() {
        assertNull(HlsSeekPlaylist.trimText(fmp4(), base, 0L), "No seek, no rewrite.")
        assertNull(
            HlsSeekPlaylist.trimText(fmp4(), base, 600_000_000_000L),
            "A target past the end has no segment to start from.",
        )
        val mpegTs = fmp4().lines().filterNot { it.startsWith("#EXT-X-MAP") }.joinToString("\n")
        assertNull(
            HlsSeekPlaylist.trimText(mpegTs, base, 14_000_000_000L),
            "MPEG-TS seeks correctly on its own and must not pay for this.",
        )
    }
}
