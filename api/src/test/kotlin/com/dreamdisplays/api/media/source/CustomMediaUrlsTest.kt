@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomMediaUrlsTest {
    @Test
    fun plainUrlIsUnchanged() =
        assertEquals("https://cdn.example.com/v.mp4", CustomMediaUrls.normalize("https://cdn.example.com/v.mp4"))

    @Test
    fun surroundingChatWrappersAreStripped() =
        assertEquals("https://cdn.example.com/v.mp4", CustomMediaUrls.normalize("<https://cdn.example.com/v.mp4>"))

    @Test
    fun trailingPunctuationIsStripped() =
        assertEquals("https://cdn.example.com/v.mp4", CustomMediaUrls.normalize("https://cdn.example.com/v.mp4,"))

    @Test
    fun schemeLessUrlWithPathGetsHttps() =
        assertEquals("https://example.com/v.mp4", CustomMediaUrls.normalize("example.com/v.mp4"))

    @Test
    fun bareFileNameIsNotAUrl() = assertNull(CustomMediaUrls.normalize("video.mp4"))

    @Test
    fun singleWordIsNotAUrl() = assertNull(CustomMediaUrls.normalize("minecraft"))

    @Test
    fun searchPhraseIsNotAUrl() = assertNull(CustomMediaUrls.normalize("some cool video"))

    @Test
    fun nonHttpSchemeIsRejected() {
        assertNull(CustomMediaUrls.normalize("file:///etc/passwd"))
        assertNull(CustomMediaUrls.normalize("javascript:alert(1)"))
        assertNull(CustomMediaUrls.normalize("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun emptyInputIsRejected() = assertNull(CustomMediaUrls.normalize("   "))

    @Test
    fun googleDriveShareBecomesDirectDownload() = assertEquals(
        "https://drive.google.com/uc?export=download&id=1A2B3C4D5E6F",
        CustomMediaUrls.normalize("https://drive.google.com/file/d/1A2B3C4D5E6F/view?usp=sharing"),
    )

    @Test
    fun googleDriveOpenIdBecomesDirectDownload() = assertEquals(
        "https://drive.google.com/uc?export=download&id=1A2B3C4D5E6F",
        CustomMediaUrls.normalize("https://drive.google.com/open?id=1A2B3C4D5E6F"),
    )

    @Test
    fun dropboxPreviewBecomesRaw() {
        val normalized = CustomMediaUrls.normalize("https://www.dropbox.com/s/abc123/clip.mp4?dl=0")
        assertEquals("https://www.dropbox.com/s/abc123/clip.mp4?raw=1", normalized)
    }

    @Test
    fun githubBlobBecomesRawContent() = assertEquals(
        "https://raw.githubusercontent.com/owner/repo/main/media/clip.mp4",
        CustomMediaUrls.normalize("https://github.com/owner/repo/blob/main/media/clip.mp4"),
    )

    @Test
    fun pixeldrainViewerBecomesApiFile() = assertEquals(
        "https://pixeldrain.com/api/file/AbCdEf",
        CustomMediaUrls.normalize("https://pixeldrain.com/u/AbCdEf"),
    )

    @Test
    fun videoExtensionsAreProgressive() {
        for (url in listOf("https://e.com/a.mp4", "https://e.com/a.webm", "https://e.com/a.mkv")) {
            assertEquals(CustomMediaKind.PROGRESSIVE, CustomMediaUrls.classify(url), url)
        }
    }

    @Test
    fun queryStringDoesNotConfuseClassification() =
        assertEquals(CustomMediaKind.PROGRESSIVE, CustomMediaUrls.classify("https://e.com/a.mp4?sig=x&exp=1"))

    @Test
    fun playlistIsHls() = assertEquals(CustomMediaKind.HLS, CustomMediaUrls.classify("https://e.com/master.m3u8"))

    @Test
    fun manifestIsDash() = assertEquals(CustomMediaKind.DASH, CustomMediaUrls.classify("https://e.com/m.mpd"))

    @Test
    fun audioContainerIsAudioOnly() =
        assertEquals(CustomMediaKind.AUDIO_ONLY, CustomMediaUrls.classify("https://e.com/song.mp3"))

    @Test
    fun audioOnlyIsNotDirect() = assertTrue(!CustomMediaUrls.isDirect("https://e.com/song.mp3"))

    @Test
    fun unknownExtensionIsUnknown() =
        assertEquals(CustomMediaKind.UNKNOWN, CustomMediaUrls.classify("https://vimeo.com/123456"))

    @Test
    fun displayNameIsTheDecodedFileName() =
        assertEquals("My Holiday Clip", CustomMediaUrls.displayName("https://e.com/files/My%20Holiday%20Clip.mp4"))

    @Test
    fun displayNameReplacesUnderscores() =
        assertEquals("cool clip", CustomMediaUrls.displayName("https://e.com/cool_clip.webm"))

    @Test
    fun displayNameFallsBackToHost() =
        assertEquals("e.com", CustomMediaUrls.displayName("https://e.com/"))

    @Test
    fun hostDropsWwwPrefix() = assertEquals("example.com", CustomMediaUrls.hostOf("https://www.example.com/a.mp4"))

    @Test
    fun directUrlBecomesDirectStreamSource() {
        val source = MediaSource.from("https://cdn.example.com/v.mp4")
        assertTrue(source is MediaSource.DirectStream, "expected DirectStream, got $source")
        assertEquals(CustomMediaKind.PROGRESSIVE, source.kind)
    }

    @Test
    fun youTubeStillWinsOverDirectClassification() =
        assertTrue(MediaSource.from("https://www.youtube.com/watch?v=dQw4w9WgXcQ") is MediaSource.YouTube)

    @Test
    fun twitchStillWinsOverDirectClassification() =
        assertTrue(MediaSource.from("https://www.twitch.tv/someone") is MediaSource.Twitch)

    @Test
    fun unknownSiteStaysRemoteForTheExtractorChain() =
        assertTrue(MediaSource.from("https://www.dailymotion.com/video/x8abcde") is MediaSource.Remote)

    @Test
    fun imgurGifvIsRewrittenToMp4() = assertEquals(
        "https://i.imgur.com/abcDEF.mp4",
        CustomMediaUrls.normalize("https://i.imgur.com/abcDEF.gifv"),
    )

    @Test
    fun cleanFileNameDropsExtensionAndSeparators() =
        assertEquals("my cool clip", CustomMediaUrls.cleanFileName("my_cool_clip.mp4"))
}
