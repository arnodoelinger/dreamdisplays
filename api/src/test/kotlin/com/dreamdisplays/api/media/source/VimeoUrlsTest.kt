@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VimeoUrlsTest {
    @Test
    fun plainVideoUrl() {
        val v = VimeoUrls.parse("https://vimeo.com/123456789")
        assertEquals("123456789", v?.videoId)
        assertNull(v?.hash)
    }

    @Test
    fun unlistedVideoWithHash() {
        val v = VimeoUrls.parse("https://vimeo.com/123456789/abc123def4")
        assertEquals("123456789", v?.videoId)
        assertEquals("abc123def4", v?.hash)
    }

    @Test
    fun hashInQueryParameter() {
        val v = VimeoUrls.parse("https://vimeo.com/123456789?h=abc123def4&share=copy")
        assertEquals("123456789", v?.videoId)
        assertEquals("abc123def4", v?.hash)
    }

    @Test
    fun playerEmbedUrl() =
        assertEquals("987654321", VimeoUrls.parse("https://player.vimeo.com/video/987654321")?.videoId)

    @Test
    fun channelUrl() =
        assertEquals("123456789", VimeoUrls.parse("https://vimeo.com/channels/staffpicks/123456789")?.videoId)

    @Test
    fun groupsUrl() =
        assertEquals("123456789", VimeoUrls.parse("https://vimeo.com/groups/name/videos/123456789")?.videoId)

    @Test
    fun keywordAfterIdIsNotAHash() =
        assertNull(VimeoUrls.parse("https://vimeo.com/123456789/settings")?.hash)

    @Test
    fun nonVimeoUrlIsNull() {
        assertNull(VimeoUrls.parse("https://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(VimeoUrls.parse("https://example.com/123456789"))
    }

    @Test
    fun mediaSourceRoutesVimeo() {
        val source = MediaSource.from("https://vimeo.com/123456789")
        assertTrue(source is MediaSource.Vimeo)
        assertEquals(MediaPlatform.VIMEO, source.platform)
    }

    @Test
    fun schemeLessPasteIsNormalizedWithScheme() {
        val source = MediaSource.from("vimeo.com/123456789") as MediaSource.Vimeo
        assertTrue(source.url.startsWith("https://"), "expected scheme, got ${source.url}")
        assertEquals("123456789", source.videoId)
    }
}
