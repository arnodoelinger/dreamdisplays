@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KickUrlsTest {
    @Test
    fun liveChannelUrl() {
        val k = KickUrls.parse("https://kick.com/xqc")
        assertEquals("xqc", k?.channel)
        assertNull(k?.videoUuid)
    }

    @Test
    fun channelVodUrl() {
        val k = KickUrls.parse("https://kick.com/xqc/videos/12345678-1234-1234-1234-123456789abc")
        assertEquals("xqc", k?.channel)
        assertEquals("12345678-1234-1234-1234-123456789abc", k?.videoUuid)
    }

    @Test
    fun directVideoUrl() {
        val k = KickUrls.parse("https://kick.com/video/12345678-1234-1234-1234-123456789abc")
        assertNull(k?.channel)
        assertEquals("12345678-1234-1234-1234-123456789abc", k?.videoUuid)
    }

    @Test
    fun reservedSlugsAreNotChannels() {
        assertNull(KickUrls.parse("https://kick.com/browse"))
        assertNull(KickUrls.parse("https://kick.com/following"))
        assertNull(KickUrls.parse("https://kick.com/category/just-chatting"))
    }

    @Test
    fun nonKickUrlIsNull() =
        assertNull(KickUrls.parse("https://twitch.tv/xqc"))

    @Test
    fun channelSlugCandidateAcceptsPlainName() {
        assertEquals("xqc", KickUrls.channelSlugCandidate("xQc"))
        assertNull(KickUrls.channelSlugCandidate("browse"))
        assertNull(KickUrls.channelSlugCandidate("has spaces"))
    }

    @Test
    fun mediaSourceRoutesKick() {
        val source = MediaSource.from("https://kick.com/xqc")
        assertTrue(source is MediaSource.Kick)
        assertEquals(MediaPlatform.KICK, source.platform)
    }
}
