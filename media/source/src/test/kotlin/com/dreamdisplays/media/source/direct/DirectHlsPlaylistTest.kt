package com.dreamdisplays.media.source.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectHlsPlaylistTest {
    @Test
    fun masterVariantsAreParsedAndSortedByHeight() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,FRAME-RATE=60.000
            1080/index.m3u8
        """.trimIndent()

        val parsed = DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/vod/master.m3u8")

        assertTrue(parsed.isMaster)
        assertEquals(2, parsed.variants.size)
        assertEquals(1080, parsed.variants[0].height)
        assertEquals(60.0, parsed.variants[0].fps)
        assertEquals(360, parsed.variants[1].height)
    }

    @Test
    fun relativeVariantUrlsResolveAgainstThePlaylist() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
        """.trimIndent()

        val parsed = DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/vod/master.m3u8")

        assertEquals("https://cdn.example.com/vod/360/index.m3u8", parsed.variants[0].url)
    }

    @Test
    fun quotedAttributeCommasAreNotSeparators() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:CODECS="avc1.64001f,mp4a.40.2",BANDWIDTH=900000,RESOLUTION=1280x720
            720.m3u8
        """.trimIndent()

        val parsed = DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/master.m3u8")

        assertEquals(1, parsed.variants.size)
        assertEquals(720, parsed.variants[0].height)
        assertEquals(900_000, parsed.variants[0].bandwidthBps)
        assertEquals("avc1.64001f", parsed.variants[0].codecs)
    }

    @Test
    fun mediaPlaylistWithoutEndListIsLive() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.000,
            seg1.ts
            #EXTINF:4.000,
            seg2.ts
        """.trimIndent()

        val parsed = DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/live.m3u8")

        assertFalse(parsed.isMaster)
        assertTrue(parsed.isLive)
    }

    @Test
    fun mediaPlaylistWithEndListIsVod() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.000,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertFalse(DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/vod.m3u8").isLive)
    }

    @Test
    fun vodPlaylistTypeMarksItNotLive() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:4.000,
            seg1.ts
        """.trimIndent()

        assertFalse(DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/vod.m3u8").isLive)
    }

    @Test
    fun masterPlaylistIsNeverReportedLive() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
        """.trimIndent()

        assertFalse(DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/master.m3u8").isLive)
    }

    @Test
    fun onlyRealPlaylistsAreRecognized() {
        assertTrue(DirectHlsPlaylist.looksLikePlaylist("#EXTM3U\n#EXT-X-ENDLIST"))
        assertFalse(DirectHlsPlaylist.looksLikePlaylist("<!doctype html><html>"))
    }
}
