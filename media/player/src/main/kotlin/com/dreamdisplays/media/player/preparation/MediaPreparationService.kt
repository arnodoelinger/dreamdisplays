package com.dreamdisplays.media.player.preparation

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.player.PlaybackEnvironment
import com.dreamdisplays.api.media.source.service.MediaResolverRegistry
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.stream.model.StreamPreferences
import com.dreamdisplays.api.media.stream.service.StreamSelector
import com.dreamdisplays.media.player.stream.ActiveStreams

/**
 * Resolves stream metadata via [MediaResolverRegistry], selects the best tracks via [StreamSelector],
 * and returns a [PreparedMedia] ready for playback. Runs on a background thread.
 */
internal object MediaPreparationService {

    /** Resolves [url] through the registry's [MediaResolverRegistry], selects tracks via [StreamSelector], and returns a [PreparedMedia] ready for playback. */
    fun prepare(url: String, lang: String, quality: VideoQuality, env: PlaybackEnvironment): PreparedMedia {
        val chain: MediaResolverRegistry = env.resolverChain()
        val selector: StreamSelector = env.streamSelector()
        val source = MediaSource.from(url)
        val resolved = chain.resolve(source)

        if (resolved.streams.isEmpty()) throw DreamMediaException.NotFound("No streams available for $url.")

        val prefs = StreamPreferences(
            maxHeight = (quality.targetHeight ?: 1080).takeIf { it > 0 },
            preferFps60 = System.getProperty("dreamdisplays.stream.prefer60", "false").toBoolean(),
            preferredAudioTrack = null,
            preferredAudioLanguage = lang.ifEmpty { null },
            allowHdr = false,
        )
        val selected = selector.select(resolved.streams, prefs)

        // An audio-only result is a distinct, actionable mistake (a music file / audio-only upload
        // pointed at a screen), not the generic "nothing usable" case, so it says so.
        val video = selected.videoStream ?: throw DreamMediaException.NotFound(
            if (resolved.hasAudio) "This link is audio only. A display needs a video to show."
            else "No usable video stream for $url.",
        )
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
            availableSubtitles = resolved.subtitles,
        )
    }
}
