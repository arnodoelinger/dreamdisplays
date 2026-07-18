package com.dreamdisplays.media.player.preparation

import com.dreamdisplays.api.media.DreamMediaException
import com.dreamdisplays.api.media.VideoQuality
import com.dreamdisplays.api.media.player.PlaybackEnvironment
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.stream.StreamPreferences
import com.dreamdisplays.api.media.stream.StreamSelector
import com.dreamdisplays.media.player.stream.ActiveStreams

/**
 * Resolves stream metadata via [MediaResolverRegistry], selects the best tracks via [StreamSelector],
 * and returns a [PreparedMedia] ready for playback. Runs on a background thread.
 */
internal object MediaPreparationService {

    /**
     * Resolves [url] through the registry's [MediaResolverRegistry], selects tracks via [StreamSelector],
     * and returns all necessary playback metadata.
     *
     * @param url raw media URL (YouTube, direct stream, etc.)
     * @param lang preferred audio language (empty = default)
     * @param quality preferred video quality ([VideoQuality.Auto] caps at 720p as a sane default)
     * @throws DreamMediaException if no usable streams are found
     */
    fun prepare(url: String, lang: String, quality: VideoQuality, env: PlaybackEnvironment): PreparedMedia {
        val chain: MediaResolverRegistry = env.resolverChain()
        val selector: StreamSelector = env.streamSelector()
        val source = MediaSource.from(url)
        val resolved = chain.resolve(source)

        if (resolved.streams.isEmpty()) throw DreamMediaException.NotFound("No streams available for $url.")

        val prefs = StreamPreferences(
            maxHeight = (quality.targetHeight ?: 720).takeIf { it > 0 },
            preferFps60 = System.getProperty("dreamdisplays.stream.prefer60", "false").toBoolean(),
            preferredAudioTrack = null,
            preferredAudioLanguage = lang.ifEmpty { null },
            allowHdr = false,
        )
        val selected = selector.select(resolved.streams, prefs)

        val video = selected.videoStream
            ?: throw DreamMediaException.NotFound("No usable video stream for $url.")
        val audio = selected.audioStream
            ?: throw DreamMediaException.NotFound("No usable audio stream for $url.")

        val durationNanos = resolved.metadata.duration?.inWholeNanoseconds ?: 0L

        return PreparedMedia(
            streamSet = ActiveStreams(
                availableVideo = resolved.videoStreams,
                availableAudio = resolved.audioStreams,
                currentVideo = video,
                currentAudio = audio,
            ),
            isLive = resolved.isLive,
            isSeekable = resolved.isSeekable,
            durationNanos = durationNanos,
        )
    }
}
