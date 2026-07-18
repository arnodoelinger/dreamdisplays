package com.dreamdisplays.media.source.ytdlp

import com.dreamdisplays.util.json.DreamJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpJsonSerializationTest {
    @Test
    fun outputParserReadsFormatsWithKotlinxJson() {
        val streams = YtDlpOutputParser.parseFormats(
            """
            {
              "duration": 12.5,
              "is_live": false,
              "formats": [
                {
                  "url": "https://cdn.example/video.webm",
                  "protocol": "https",
                  "vcodec": "vp9",
                  "acodec": "opus",
                  "ext": "webm",
                  "container": "webm_dash",
                  "width": 1280,
                  "height": 720,
                  "fps": 30,
                  "tbr": 1400.5
                },
                {
                  "url": "rtmp://cdn.example/live",
                  "protocol": "rtmp",
                  "vcodec": "none",
                  "acodec": "none"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, streams.size)
        val stream = streams.single()
        assertEquals("https://cdn.example/video.webm", stream.url)
        assertEquals("720p", stream.resolution)
        assertTrue(stream.hasVideo())
        assertTrue(stream.hasAudio())
        assertTrue(stream.isSeekable)
    }

    @Test
    fun ytStreamRoundTripsThroughDiskCacheJson() {
        val stream = YtStream(
            url = "https://cdn.example/video.m3u8",
            mimeType = "video/mp4",
            container = "mp4_dash",
            protocol = "m3u8_native",
            resolution = "1080p",
            width = 1920,
            height = 1080,
            audioTrackId = "en",
            audioTrackName = "English",
            vcodec = "avc1",
            acodec = "mp4a",
            fps = 60.0,
            tbrKbps = 4500.0,
            hasVideo = true,
            hasAudio = true,
            isLive = false,
            isSeekable = true,
            durationNanos = Durations.secondsToNanos(42.0),
        )

        val decoded = DreamJson.compact.decodeFromString<YtStream>(
            DreamJson.compact.encodeToString(stream)
        )

        assertEquals(stream.url, decoded.url)
        assertEquals("1080p", decoded.resolution)
        assertEquals(1920, decoded.width)
        assertEquals(1080, decoded.height)
        assertEquals("en", decoded.audioTrackId)
        assertTrue(decoded.hasVideo())
        assertTrue(decoded.hasAudio())
        assertFalse(decoded.isLive)
        assertTrue(decoded.isSeekable)
    }
}
