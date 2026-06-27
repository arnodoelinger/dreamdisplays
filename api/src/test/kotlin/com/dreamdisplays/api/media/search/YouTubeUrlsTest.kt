@file:OptIn(com.dreamdisplays.api.DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api.media.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class YouTubeUrlsTest {
    @Test
    fun bareVideoIdIsAccepted() {
        assertEquals(VIDEO_ID, YouTubeUrls.extractVideoId(" $VIDEO_ID "))
    }

    @Test
    fun watchUrlQueryIsParsedByParameterName() {
        assertEquals(
            VIDEO_ID,
            YouTubeUrls.extractVideoId("https://www.youtube.com/watch?feature=share&v=$VIDEO_ID&t=43")
        )
    }

    @Test
    fun schemelessShortsEmbedAndLiveUrlsAreAccepted() {
        assertEquals(VIDEO_ID, YouTubeUrls.extractVideoId("youtube.com/shorts/$VIDEO_ID?feature=share"))
        assertEquals(VIDEO_ID, YouTubeUrls.extractVideoId("www.youtube.com/embed/$VIDEO_ID"))
        assertEquals(VIDEO_ID, YouTubeUrls.extractVideoId("m.youtube.com/live/$VIDEO_ID"))
    }

    @Test
    fun shortUrlIsParsedByHostAndPathSegment() {
        assertEquals(VIDEO_ID, YouTubeUrls.extractVideoId("https://youtu.be/$VIDEO_ID?si=abc"))
    }

    @Test
    fun lookalikeHostsAreRejected() {
        assertNull(YouTubeUrls.extractVideoId("https://notyoutube.com/watch?v=$VIDEO_ID"))
        assertNull(YouTubeUrls.extractVideoId("https://youtube.com.evil.example/watch?v=$VIDEO_ID"))
    }

    @Test
    fun buildersRequireValidatedIds() {
        assertEquals("https://www.youtube.com/watch?v=$VIDEO_ID", YouTubeUrls.watchUrl(VIDEO_ID))
        assertEquals("https://i.ytimg.com/vi/$VIDEO_ID/mqdefault.jpg", YouTubeUrls.thumbnailUrl(VIDEO_ID))
        assertFailsWith<IllegalArgumentException> { YouTubeUrls.watchUrl("not-a-video-id") }
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }
}
