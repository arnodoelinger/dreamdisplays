@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
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

    @Test
    fun unknownHostFallsBackToRemote() {
        val url = "https://example.com/video.mp4"
        val source = assertIs<MediaSource.Remote>(MediaSource.from(url))
        assertEquals(url, source.url)
    }
}
