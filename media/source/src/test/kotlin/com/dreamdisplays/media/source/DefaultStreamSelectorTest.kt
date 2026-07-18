@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import com.dreamdisplays.api.media.stream.StreamPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultStreamSelectorTest {
    private val selector = DefaultStreamSelector()

    private fun stream(
        url: String,
        type: MediaStreamType,
        height: Int? = null,
        fps: Double? = null,
        bitrate: Int? = null,
    ) = MediaStream(
        url = url, type = type, codec = "avc1.4D401F",
        width = height?.let { it * 16 / 9 }, height = height, fps = fps, bitrate = bitrate,
        audioTrackName = null, audioTrackLang = null,
    )

    @Test
    fun muxedHlsVideoUsesDedicatedAudioOnlyRendition() {
        val v720 = stream(
            "https://euc12.playlist.ttvnw.net/v1/playlist/v720",
            MediaStreamType.VIDEO_AUDIO,
            720,
            60.0,
            3_400_000
        )
        val v1080 = stream(
            "https://euc12.playlist.ttvnw.net/v1/playlist/v1080",
            MediaStreamType.VIDEO_AUDIO,
            1080,
            60.0,
            6_800_000
        )
        val audioOnly =
            stream("https://euc12.playlist.ttvnw.net/v1/playlist/audio", MediaStreamType.AUDIO, bitrate = 160_000)

        val set = selector.select(listOf(v1080, v720, audioOnly), StreamPreferences(720, false, null, null, false))

        assertEquals(v720.url, set.videoStream?.url)
        assertEquals(audioOnly.url, set.audioStream?.url)
    }

    @Test
    fun muxedProgressiveVideoKeepsSameUrlAudio() {
        val muxed =
            stream("https://rr1.googlevideo.com/videoplayback?itag=18", MediaStreamType.VIDEO_AUDIO, 360, 30.0, 700_000)
        val adaptive =
            stream("https://rr1.googlevideo.com/videoplayback?itag=140", MediaStreamType.AUDIO, bitrate = 128_000)

        val set = selector.select(listOf(muxed, adaptive), StreamPreferences(720, false, null, null, false))

        assertEquals(muxed.url, set.videoStream?.url)
        assertEquals(muxed.url, set.audioStream?.url)
    }
}
