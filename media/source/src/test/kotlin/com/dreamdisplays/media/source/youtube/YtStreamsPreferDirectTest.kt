package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.media.source.youtube.model.YtStream
import com.dreamdisplays.media.source.youtube.model.YtStreams
import kotlin.test.Test
import kotlin.test.assertEquals

class YtStreamsPreferDirectTest {
    private fun stream(
        itag: Int, protocol: String, video: Boolean, audio: Boolean, live: Boolean = false,
    ) = YtStream(
        url = "https://rr1---sn-x.googlevideo.com/videoplayback?itag=$itag",
        mimeType = if (video) "video/mp4" else "audio/webm",
        container = null, protocol = protocol, resolution = if (video) "1080p" else null,
        width = if (video) 1920 else null, height = if (video) 1080 else null,
        audioTrackId = null, audioTrackName = null,
        vcodec = if (video) "avc1.640028" else "none", acodec = if (audio) "opus" else "none",
        fps = if (video) 24.0 else null, tbrKbps = 2000.0,
        hasVideo = video, hasAudio = audio, isLive = live, isSeekable = !live, durationNanos = 210_000_000_000,
    )

    @Test
    fun `made-for-kids VOD drops muxed HLS in favour of DASH`() {
        val hls = stream(96, "m3u8_native", video = true, audio = true)
        val dashVideo = stream(137, "https", video = true, audio = false)
        val dashAudio = stream(251, "https", video = false, audio = true)

        assertEquals<List<YtStream>>([dashVideo, dashAudio], YtStreams.preferDirect([hls, dashVideo, dashAudio]))
    }

    @Test
    fun `live keeps HLS`() {
        val hls = stream(96, "m3u8_native", video = true, audio = true, live = true)
        val dashVideo = stream(137, "https", video = true, audio = false, live = true)

        assertEquals<List<YtStream>>([hls, dashVideo], YtStreams.preferDirect([hls, dashVideo]))
    }

    @Test
    fun `keeps HLS when direct tracks carry no audio`() {
        val hls = stream(96, "m3u8_native", video = true, audio = true)
        val dashVideo = stream(137, "https", video = true, audio = false)

        assertEquals<List<YtStream>>([hls, dashVideo], YtStreams.preferDirect([hls, dashVideo]))
    }
}
