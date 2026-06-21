package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.MediaStream
import com.dreamdisplays.api.media.MediaStreamType
import com.dreamdisplays.api.media.StreamPreferences
import com.dreamdisplays.api.media.StreamSelector
import com.dreamdisplays.api.media.StreamSet
import com.dreamdisplays.media.player.stream.MediaStreamSelector
import org.slf4j.LoggerFactory

/**
 * Default [StreamSelector] backed by [MediaStreamSelector]. Picks the closest video stream to
 * [StreamPreferences.maxHeight] and the best-matching audio track for [StreamPreferences.preferredAudioLanguage].
 */
class DefaultStreamSelector : StreamSelector {
    private val logger = LoggerFactory.getLogger("DreamDisplays/DefaultStreamSelector")
    private val debug: Boolean
        get() = System.getProperty("dreamdisplays.debug")?.toBoolean() == true
                || System.getenv("DREAMDISPLAYS_DEBUG").let { it == "1" || it.equals("true", ignoreCase = true) }

    private val preferProgressiveAudio: Boolean =
        System.getProperty("dreamdisplays.audio.preferProgressive", "true").toBoolean()

    override fun select(streams: List<MediaStream>, preferences: StreamPreferences): StreamSet {
        val videoStreams = streams.filter { it.type.hasVideo }
        val audioStreams = streams.filter { it.type == MediaStreamType.AUDIO }

        val targetHeight = preferences.maxHeight ?: 720
        val lang = preferences.preferredAudioLanguage ?: ""

        val video = MediaStreamSelector.pickVideo(videoStreams, targetHeight, preferences.preferFps60)
            ?: videoStreams.firstOrNull()
        val adaptiveAudio = MediaStreamSelector.pickAudio(audioStreams, lang, video)
            ?: audioStreams.firstOrNull()

        val progressiveAudio = streams.filter { it.type == MediaStreamType.VIDEO_AUDIO }
            .maxByOrNull { it.bitrate ?: 0 }
        val audio = if (preferProgressiveAudio && progressiveAudio != null) progressiveAudio else adaptiveAudio

        if (debug) {
            logger.debug(
                "Video stream ${MediaStreamSelector.describeVideoChoice(video, targetHeight, preferences.preferFps60)} " +
                        "candidates=${videoStreams.size} preferFps60=${preferences.preferFps60}.",
            )
        }

        return StreamSet(videoStream = video, audioStream = audio, allStreams = streams)
    }
}
