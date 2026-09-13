@file:OptIn(Unstable::class)

package com.dreamdisplays.api.media.source.model

import com.dreamdisplays.api.Unstable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MediaSourceTest {
    @Test
    fun twitchChannelUrlParsesChannel() {
        val url = "https://www.twitch.tv/somechannel"
        val source = assertIs<MediaSource.Twitch>(MediaSource.from(url))
        assertEquals("somechannel", source.channel)
        assertNull(source.videoId)
        assertNull(source.clipSlug)
        assertEquals(url, source.toResolvableUrl())
    }

    @Test
    fun twitchVodUrlParsesVideoId() {
        val url = "https://www.twitch.tv/videos/123456789"
        val source = assertIs<MediaSource.Twitch>(MediaSource.from(url))
        assertNull(source.channel)
        assertEquals("123456789", source.videoId)
        assertNull(source.clipSlug)
        assertEquals(url, source.toResolvableUrl())
    }

    @Test
    fun twitchClipUrlParsesClipSlug() {
        val url = "https://clips.twitch.tv/AwesomeClipSlug"
        val source = assertIs<MediaSource.Twitch>(MediaSource.from(url))
        assertNull(source.channel)
        assertNull(source.videoId)
        assertEquals("AwesomeClipSlug", source.clipSlug)
        assertEquals(url, source.toResolvableUrl())
    }

    @Test
    fun youTubeUrlStillParsesAsYouTube() {
        val source = assertIs<MediaSource.YouTube>(MediaSource.from("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", source.videoId)
    }

    /** An unrecognized page still goes to the extractor chain via [MediaSource.Remote]. */
    @Test
    fun unknownHostFallsBackToRemote() {
        val url = "https://example.com/watch/some-video"
        val source = assertIs<MediaSource.Remote>(MediaSource.from(url))
        assertEquals(url, source.url)
    }

    /** A URL that names a media file skips the extractors entirely (see [com.dreamdisplays.api.media.source.url.CustomMediaUrls]). */
    @Test
    fun unknownHostWithMediaFileParsesAsDirectStream() {
        val url = "https://example.com/video.mp4"
        val source = assertIs<MediaSource.DirectStream>(MediaSource.from(url))
        assertEquals(url, source.streamUrl)
        assertEquals(CustomMediaKind.PROGRESSIVE, source.kind)
        assertEquals(url, source.toResolvableUrl())
    }

    @Test
    fun bilibiliBangumiEpisodeUrlParsesEpId() {
        val url = "https://www.bilibili.com/bangumi/play/ep21484"
        val source = assertIs<MediaSource.Bilibili>(MediaSource.from(url))
        assertEquals(21484L, source.epId)
        assertNull(source.seasonId)
        assertNull(source.bvid)
        assertEquals(url, source.toResolvableUrl())
    }

    @Test
    fun bilibiliBangumiSeasonUrlParsesSeasonId() {
        val url = "https://www.bilibili.com/bangumi/play/ss1182"
        val source = assertIs<MediaSource.Bilibili>(MediaSource.from(url))
        assertEquals(1182L, source.seasonId)
        assertNull(source.epId)
    }

    @Test
    fun bilibiliVodUrlStillParsesBvid() {
        val url = "https://www.bilibili.com/video/BV1Lx411w76a"
        val source = assertIs<MediaSource.Bilibili>(MediaSource.from(url))
        assertEquals("BV1Lx411w76a", source.bvid)
        assertNull(source.epId)
        assertNull(source.seasonId)
    }
}
