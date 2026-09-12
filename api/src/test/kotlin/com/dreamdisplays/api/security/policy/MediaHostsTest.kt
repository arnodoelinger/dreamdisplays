@file:OptIn(Unstable::class)

package com.dreamdisplays.api.security.policy

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.source.model.MediaPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaHostsTest {
    @Test
    fun `platform hosts and their CDNs are recognized`() {
        assertEquals(MediaPlatform.YOUTUBE, MediaHosts.platformOf("https://www.youtube.com/watch?v=x"))
        assertEquals(MediaPlatform.YOUTUBE, MediaHosts.platformOf("https://rr3---sn-oxu.googlevideo.com/videoplayback?x=1"))
        assertEquals(MediaPlatform.TWITCH, MediaHosts.platformOf("https://video-weaver.arn1.hls.ttvnw.net/v1/playlist.m3u8"))
        assertEquals(MediaPlatform.VIMEO, MediaHosts.platformOf("https://player.vimeo.com/video/1/config"))
        assertEquals(MediaPlatform.KICK, MediaHosts.platformOf("https://stream.kick.com/x.m3u8"))
        assertEquals(MediaPlatform.BILIBILI, MediaHosts.platformOf("https://upos-sz.bilivideo.com/x.m4s"))
    }

    @Test
    fun `a pasted host is nobody's platform`() {
        assertNull(MediaHosts.platformOf("https://example.com/clip.mp4"))
        assertFalse(MediaHosts.isFirstParty("https://example.com/clip.mp4"))
        assertTrue(MediaHosts.isFirstParty("https://youtu.be/x"))
    }

    @Test
    fun `lookalike domains do not pass as platforms`() {
        assertNull(MediaHosts.platformOf("https://youtube.com.evil.tld/clip.mp4"))
        assertNull(MediaHosts.platformOf("https://notyoutube.com/clip.mp4"))
        assertNull(MediaHosts.platformOf("https://evil.tld/?host=youtube.com"))
    }

    @Test
    fun `shared CDNs stay third party`() {
        assertNull(MediaHosts.platformOf("https://d1234.cloudfront.net/clip.mp4"))
        assertNull(MediaHosts.platformOf("https://x.akamaized.net/clip.mp4"))
        assertNull(MediaHosts.platformOf("https://storage.googleapis.com.evil.tld/clip.mp4"))
        assertNull(MediaHosts.platformOf("https://storage.googleapis.com/bucket/clip.mp4"))
        assertNull(MediaHosts.platformOf("https://commondatastorage.googleapis.com/x/clip.mp4"))
        assertEquals(
            MediaPlatform.YOUTUBE,
            MediaHosts.platformOf("https://youtubei.googleapis.com/youtubei/v1/player"),
        )
    }

    @Test
    fun `only platform requests carry a referer`() {
        assertEquals("https://www.bilibili.com/", MediaHosts.refererFor("https://upos-sz.bilivideo.com/x.m4s"))
        assertEquals("https://kick.com/", MediaHosts.refererFor("https://stream.kick.com/x.m3u8"))
        assertEquals("https://www.youtube.com/", MediaHosts.refererFor("https://rr3.googlevideo.com/videoplayback"))
        assertNull(
            MediaHosts.refererFor("https://example.com/clip.mp4"),
            "A pasted host must not be told which site we came from.",
        )
    }

    @Test
    fun `hosts are parsed defensively`() {
        assertNull(MediaHosts.hostOf("not a url"))
        assertNull(MediaHosts.hostOf(""))
        assertEquals("example.com", MediaHosts.hostOf("HTTPS://Example.COM/clip.mp4"))
        assertEquals("::1", MediaHosts.hostOf("http://[::1]:8080/clip.mp4"))
    }
}
