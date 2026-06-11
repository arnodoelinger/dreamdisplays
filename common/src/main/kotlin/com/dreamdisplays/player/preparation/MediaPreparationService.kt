package com.dreamdisplays.player.preparation

import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.get
import com.dreamdisplays.media.api.DreamMediaException
import com.dreamdisplays.media.api.MediaResolverChain
import com.dreamdisplays.media.api.MediaSource
import com.dreamdisplays.media.api.StreamPreferences
import com.dreamdisplays.media.api.StreamSelector
import com.dreamdisplays.media.api.VideoQuality
import com.dreamdisplays.player.stream.ActiveStreams

/**
 * Resolves stream metadata via [MediaResolverChain], selects the best tracks via [StreamSelector],
 * and returns a [PreparedMedia] ready for playback. Runs on a background thread.
 */
internal object MediaPreparationService {

    /**
     * Resolves [url] through the registry's [MediaResolverChain], selects tracks via [StreamSelector],
     * and returns all necessary playback metadata.
     *
     * @param url raw media URL (YouTube, direct stream, etc.)
     * @param lang preferred audio language (empty = default)
     * @param quality preferred video quality ([VideoQuality.Auto] caps at 720p as a sane default)
     * @throws DreamMediaException if no usable streams are found
     */
    fun prepare(url: String, lang: String, quality: VideoQuality): PreparedMedia {
        val registry = DreamServices.registry
        val chain = registry.get<MediaResolverChain>()
        val selector = registry.get<StreamSelector>()
        val source = MediaSource.from(url)
        val resolved = chain.resolve(source)

        if (resolved.streams.isEmpty()) throw DreamMediaException.NotFound("No streams available for $url.")

        val prefs = StreamPreferences(
            maxHeight = (quality.targetHeight ?: 720).takeIf { it > 0 },
            preferFps60 = true,
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
