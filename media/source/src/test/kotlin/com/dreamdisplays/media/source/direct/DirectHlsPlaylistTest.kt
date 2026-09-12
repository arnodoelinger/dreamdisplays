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
    fun separateAudioRenditionsArePairedWithTheirVariants() {
        // Apple's reference master: the variants carry no sound at all, and are listed once per
        // audio group. Pointing the audio decoder at one of them yields no stream whatsoever.
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud1",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="a1/prog.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud2",NAME="English AC-3",LANGUAGE="en",URI="a2/prog.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub1",NAME="English",URI="s1/prog.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,AUDIO="aud1"
            v1/prog.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360,AUDIO="aud2"
            v1/prog.m3u8
        """.trimIndent()

        val parsed = DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/vod/master.m3u8")

        assertEquals(1, parsed.variants.size, "The same rendition listed per audio group is one quality.")
        assertEquals("aud1", parsed.variants[0].audioGroupId)
        assertEquals(2, parsed.audioRenditions.size, "Subtitle renditions are not audio.")

        val paired = parsed.audioFor(parsed.variants[0])
        assertEquals(1, paired.size)
        assertEquals("https://cdn.example.com/vod/a1/prog.m3u8", paired[0].url)
        assertEquals("en", paired[0].language)
        assertTrue(paired[0].isDefault)
    }

    @Test
    fun mediaTagsWithoutTheirOwnPlaylistAreIgnored() {
        // TYPE=AUDIO with no URI describes sound that is already muxed into the variants, so the
        // variants stay playable on their own and must not be re-typed as video-only.
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud1",NAME="Main",DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,AUDIO="aud1"
            v1/prog.m3u8
        """.trimIndent()

        val parsed = DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/master.m3u8")

        assertTrue(parsed.audioRenditions.isEmpty())
        assertTrue(parsed.audioFor(parsed.variants[0]).isEmpty())
    }

    @Test
    fun segmentDurationsAddUpToTheMediaLength() {
        // The only place an HLS VOD's length is knowable without opening the stream. Missing it left
        // the player with no timeline, which in the UI means no seek bar at all.
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:5.99467,
            seg1.ts
            #EXTINF:6.00000,
            seg2.ts
            #EXTINF:2.50000,
            seg3.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/vod.m3u8")

        assertEquals(14_494_670_000L, parsed.totalDurationNanos)
        assertFalse(parsed.isLive)
    }

    @Test
    fun aMasterOnItsOwnReportsNoDuration() {
        // Masters list renditions, not segments; the length comes from whichever one gets probed
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
        """.trimIndent()

        assertEquals(0L, DirectHlsPlaylist.parse(playlist, "https://cdn.example.com/master.m3u8").totalDurationNanos)
    }

    @Test
    fun onlyRealPlaylistsAreRecognized() {
        assertTrue(DirectHlsPlaylist.looksLikePlaylist("#EXTM3U\n#EXT-X-ENDLIST"))
        assertFalse(DirectHlsPlaylist.looksLikePlaylist("<!doctype html><html>"))
    }
}
