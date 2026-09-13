package com.dreamdisplays.media.source.twitch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwitchHlsTest {
    /** A trimmed-down real usher master playlist: source, one lower rendition, and audio-only. */
    private val master = """
        #EXTM3U
        #EXT-X-TWITCH-INFO:NODE="video-edge",MANIFEST-NODE-TYPE="weaver_cluster"
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=6851936,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
        https://video-weaver.example.hls.ttvnw.net/v1/playlist/source.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="720p60",NAME="720p60",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=3422999,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="720p60",FRAME-RATE=60.000
        https://video-weaver.example.hls.ttvnw.net/v1/playlist/720p60.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="audio_only",NAME="Audio Only",AUTOSELECT=NO,DEFAULT=NO
        #EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS="mp4a.40.2",VIDEO="audio_only"
        https://video-weaver.example.hls.ttvnw.net/v1/playlist/audio.m3u8
    """.trimIndent()

    @Test
    fun parsesRenditionsWithQuotedCodecs() {
        val renditions = TwitchHls.parseMaster(master)
        assertEquals(3, renditions.size)

        val source = renditions[0]
        assertEquals("https://video-weaver.example.hls.ttvnw.net/v1/playlist/source.m3u8", source.url)
        assertEquals(1920, source.width)
        assertEquals(1080, source.height)
        assertEquals(60.0, source.fps)
        assertEquals(6851936, source.bandwidthBps)
        // The quoted CODECS value must survive intact despite containing a comma
        assertEquals("avc1.64002A,mp4a.40.2", source.codecs)
        assertEquals("chunked", source.videoGroup)
        assertTrue(!source.isAudioOnly)
    }

    @Test
    fun flagsAudioOnlyRendition() {
        val audio = TwitchHls.parseMaster(master).last()
        assertTrue(audio.isAudioOnly)
        assertNull(audio.width)
        assertNull(audio.height)
        assertEquals("audio_only", audio.videoGroup)
    }

    @Test
    fun ignoresUrlWithoutPrecedingStreamInf() {
        val renditions = TwitchHls.parseMaster("#EXTM3U\nhttps://example.com/orphan.m3u8")
        assertTrue(renditions.isEmpty())
    }

    @Test
    fun usherUrlEncodesTokenAndLowercasesLogin() {
        val token = TwitchAccessToken(value = """{"channel":"X","expires":1}""", signature = "abc123")
        val url = TwitchHls.liveUrl("SomeChannel", token)
        assertTrue(url.startsWith("https://usher.ttvnw.net/api/channel/hls/somechannel.m3u8?"))
        assertTrue("sig=abc123" in url)
        // The token JSON must be percent-encoded (quotes and braces are not URL-safe)
        assertTrue("token=%7B%22channel%22%3A%22X%22%2C%22expires%22%3A1%7D" in url)
    }
}
