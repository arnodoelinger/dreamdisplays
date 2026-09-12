package com.dreamdisplays.media.source.youtube.newpipe

import com.dreamdisplays.api.media.stream.model.SubtitleTrack
import com.dreamdisplays.media.source.youtube.model.Durations
import com.dreamdisplays.media.source.youtube.model.YtStream
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.slf4j.LoggerFactory

/** Fully resolved video, shared between [com.dreamdisplays.media.source.youtube.NewPipeResolver]'s resolve and fetch entry points. */
class NewPipeResolved(
    val streams: List<YtStream>,
    val title: String?,
    val uploader: String?,
    val durationNanos: Long,
    val thumbnailUrl: String?,
    val viewCount: Long?,
    val likeCount: Long?,
    val isLive: Boolean,
    val isSeekable: Boolean,
    val subtitles: List<SubtitleTrack> = emptyList(),
)

/**
 * Drives `NewPipeExtractor`'s [StreamExtractor] directly and maps its output to [YtStream]s. Pure
 * extraction logic, with no caching or [com.dreamdisplays.api.media.source.service.MediaResolverService] contract
 * of its own — those live in [com.dreamdisplays.media.source.youtube.NewPipeResolver].
 */
object NewPipeStreamExtraction {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Drives [StreamExtractor] directly: single fetchPage() + reads from live stream. */
    fun extract(url: String): NewPipeResolved? {
        return runCatching {
            val service = NewPipe.getServiceByUrl(url)
            val extractor = service.getStreamExtractor(url)
            extractor.fetchPage()

            val streamType = safe { extractor.streamType } ?: StreamType.VIDEO_STREAM
            val live = streamType == StreamType.LIVE_STREAM ||
                    streamType == StreamType.AUDIO_LIVE_STREAM ||
                    streamType == StreamType.POST_LIVE_STREAM
            val durationNanos = Durations.secondsToNanos(safe { extractor.length } ?: 0L)
            val seekable = !live && durationNanos > 0L

            val streams = mapStreams(extractor, live, seekable, durationNanos)
            if (streams.isEmpty()) return null

            NewPipeResolved(
                streams = streams,
                title = safe { extractor.name }?.takeIf { it.isNotBlank() },
                uploader = safe { extractor.uploaderName }?.takeIf { it.isNotBlank() },
                durationNanos = durationNanos,
                thumbnailUrl = safe { extractor.thumbnails.firstOrNull()?.url },
                viewCount = safe { extractor.viewCount }?.takeIf { it > 0L },
                likeCount = safe { extractor.likeCount }?.takeIf { it > 0L },
                isLive = live,
                isSeekable = seekable,
                subtitles = mapSubtitles(extractor),
            )
        }.onFailure { e ->
            logger.debug("NewPipeExtractor fetch failed for {}: {}", url, e.message)
        }.getOrNull()
    }

    /** Maps the directly-fetched [extractor]'s stream lists into the flat [YtStream] list the player pipeline expects. */
    private fun mapStreams(
        extractor: StreamExtractor,
        live: Boolean,
        seekable: Boolean,
        durationNanos: Long,
    ): List<YtStream> {
        val out = ArrayList<YtStream>()
        // Muxed progressive streams (video + audio in one URL)
        for (s in safe { extractor.videoStreams }.orEmpty()) {
            if (!acceptable(s)) continue
            out.add(videoToYt(s, hasAudio = true, live = live, seekable = seekable, durationNanos = durationNanos))
        }
        // Adaptive video-only streams
        for (s in safe { extractor.videoOnlyStreams }.orEmpty()) {
            if (!acceptable(s)) continue
            out.add(videoToYt(s, hasAudio = false, live = live, seekable = seekable, durationNanos = durationNanos))
        }
        // Adaptive audio-only streams
        for (s in safe { extractor.audioStreams }.orEmpty()) {
            if (!acceptable(s)) continue
            out.add(audioToYt(s, live = live, seekable = seekable, durationNanos = durationNanos))
        }
        return out
    }

    /** Runs [block], swallowing any extractor failure and returning null so optional fields degrade gracefully. */
    private inline fun <T> safe(block: () -> T): T? = runCatching(block).getOrNull()

    /** Maps the extractor's WebVTT caption tracks (uploader-provided and auto-generated). */
    private fun mapSubtitles(extractor: StreamExtractor): List<SubtitleTrack> {
        val tracks = safe { extractor.getSubtitles(MediaFormat.VTT) }.orEmpty()
        return tracks
            .filter { it.isUrl && it.content.isNotBlank() }
            .mapNotNull { subtitleToTrack(it) }
            .groupBy { it.lang }
            .values
            .map { group -> group.firstOrNull { !it.isAutoGenerated } ?: group.first() }
    }

    /** Converts a `NewPipeExtractor` [SubtitlesStream] to a [SubtitleTrack], dropping tracks with no usable language tag. */
    private fun subtitleToTrack(s: SubtitlesStream): SubtitleTrack? {
        val lang = safe { s.languageTag }?.takeIf { it.isNotBlank() } ?: return null
        return SubtitleTrack(
            url = s.content,
            lang = lang,
            displayName = safe { s.displayLanguageName }?.takeIf { it.isNotBlank() },
            isAutoGenerated = safe { s.isAutoGenerated } == true,
        )
    }

    /** Converts a `NewPipeExtractor` [VideoStream] to a [YtStream]. */
    private fun videoToYt(
        s: VideoStream,
        hasAudio: Boolean,
        live: Boolean,
        seekable: Boolean,
        durationNanos: Long,
    ): YtStream {
        val ext = s.format?.suffix
        val mime = s.format?.mimeType ?: "video/${ext ?: "mp4"}"
        return YtStream(
            s.content,
            mime,
            ext,
            protocolOf(s),
            s.getResolution().ifBlank { null },
            s.width.takeIf { it > 0 },
            s.height.takeIf { it > 0 },
            null,
            null,
            s.codec.ifBlank { null },
            null,
            s.fps.takeIf { it > 0 }?.toDouble(),
            s.bitrate.takeIf { it > 0 }?.let { it / 1000.0 },
            true,
            hasAudio,
            live,
            seekable,
            durationNanos,
        )
    }

    /** Converts a `NewPipeExtractor` [AudioStream] to a [YtStream]. */
    private fun audioToYt(
        s: AudioStream,
        live: Boolean,
        seekable: Boolean,
        durationNanos: Long,
    ): YtStream {
        val ext = s.format?.suffix
        val mime = s.format?.mimeType ?: "audio/${ext ?: "mp4"}"
        return YtStream(
            url = s.content,
            mimeType = mime,
            container = ext,
            protocol = protocolOf(s),
            resolution = null,
            width = null,
            height = null,
            audioTrackId = s.audioTrackId,
            audioTrackName = s.audioTrackName,
            vcodec = null,
            acodec = s.codec.ifBlank { null },
            fps = null,
            tbrKbps = s.averageBitrate.takeIf { it > 0 }?.toDouble(),
            hasVideo = false,
            hasAudio = true,
            isLive = live,
            isSeekable = seekable,
            durationNanos = durationNanos,
        )
    }

    /** True if the stream is a directly playable HTTP or HLS URL (FFmpeg can consume those as `-i`). */
    private fun acceptable(s: Stream): Boolean =
        s.isUrl && s.content.isNotBlank() &&
                (s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || s.deliveryMethod == DeliveryMethod.HLS)

    /** Maps the `NewPipeExtractor` delivery method to the protocol label used by [YtStream]. */
    private fun protocolOf(s: Stream): String =
        if (s.deliveryMethod == DeliveryMethod.HLS) "m3u8_native" else "https"
}
