package com.dreamdisplays.media

import com.dreamdisplays.media.api.MediaStream
import com.dreamdisplays.media.api.MediaStreamType
import com.dreamdisplays.media.api.StreamPreferences
import com.dreamdisplays.media.api.StreamSelector
import com.dreamdisplays.media.api.StreamSet
import com.dreamdisplays.player.stream.MediaStreamSelector

/**
 * Default [StreamSelector] backed by [MediaStreamSelector]. Picks the closest video stream to
 * [StreamPreferences.maxHeight] and the best-matching audio track for [StreamPreferences.preferredAudioLanguage].
 */
class DefaultStreamSelector : StreamSelector {

    override fun select(streams: List<MediaStream>, preferences: StreamPreferences): StreamSet {
        val videoStreams = streams.filter { it.type.hasVideo }
        val audioStreams = streams.filter { it.type == MediaStreamType.AUDIO }

        val targetHeight = preferences.maxHeight ?: 720
        val lang = preferences.preferredAudioLanguage ?: ""

        val video = MediaStreamSelector.pickVideo(videoStreams, targetHeight)
            ?: videoStreams.firstOrNull()
        val audio = MediaStreamSelector.pickAudio(audioStreams, lang, video)
            ?: audioStreams.firstOrNull()

        return StreamSet(videoStream = video, audioStream = audio, allStreams = streams)
    }
}
