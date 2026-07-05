@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api.security

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaUrlPolicyTest {
    @Test
    fun emptyIsAllowed() = assertTrue(MediaUrlPolicy.isAllowed(""))

    @Test
    fun bareYouTubeIdIsAllowed() = assertTrue(MediaUrlPolicy.isAllowed("dQw4w9WgXcQ"))

    @Test
    fun httpUrlIsAllowed() = assertTrue(MediaUrlPolicy.isAllowed("https://youtube.com/watch?v=dQw4w9WgXcQ"))

    @Test
    fun uppercaseHttpSchemeIsAllowed() = assertTrue(MediaUrlPolicy.isAllowed("HTTPS://example.com/video.mp4"))

    @Test
    fun hostlessHttpUrlIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("https:///watch?v=dQw4w9WgXcQ"))

    @Test
    fun nonHttpUrlIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("javascript:alert(1)"))

    @Test
    fun whitespaceIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("https://example.com/a b"))

    @Test
    fun controlCharIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("https://example.com/\u0000"))

    @Test
    fun nonUrlIsRejected() = assertFalse(MediaUrlPolicy.isAllowed("not-a-url"))

    @Test
    fun urlAtCapIsAllowed() {
        val url = "https://example.com/" + "a".repeat(MediaUrlPolicy.MAX_URL_LENGTH - 20)
        assertTrue(MediaUrlPolicy.isAllowed(url))
        assertTrue(url.length == MediaUrlPolicy.MAX_URL_LENGTH)
    }

    @Test
    fun overlongUrlIsRejected() {
        val url = "https://example.com/" + "a".repeat(MediaUrlPolicy.MAX_URL_LENGTH)
        assertFalse(MediaUrlPolicy.isAllowed(url))
    }

    @Test
    fun overlongRawUrlIsRejectedEvenWhenTrimmedValueIsValid() {
        val url = " ".repeat(MediaUrlPolicy.MAX_URL_LENGTH) + "https://example.com/video.mp4"
        assertFalse(MediaUrlPolicy.isAllowed(url))
    }

    @Test
    fun languageTagIsSanitized() {
        assertEquals("enus", MediaUrlPolicy.sanitizeLang(" en\tus\u0000"))
        assertEquals("abcdefghijklmnop", MediaUrlPolicy.sanitizeLang("abcdefghijklmnopq"))
    }
}
