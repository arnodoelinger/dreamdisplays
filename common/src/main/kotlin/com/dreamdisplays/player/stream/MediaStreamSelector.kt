package com.dreamdisplays.player.stream

import com.dreamdisplays.media.api.MediaStream
import com.dreamdisplays.media.api.MediaStreamType
import kotlin.math.abs

/** Pure helpers for parsing quality values and picking video / audio tracks from a [MediaStream] list. */
object MediaStreamSelector {

    /** Returns the pixel height of [stream], or [Int.MAX_VALUE] if unknown. */
    fun parseQuality(stream: MediaStream): Int = stream.height ?: Int.MAX_VALUE

    /**
     * Maps a quality value (e.g. 720) to a standard video dimension (e.g. 1280 x 720). Used to
     * pick the best matching stream when the quality label is missing or unparseable.
     */
    fun qualityToDims(quality: Int): IntArray = when {
        quality <= 240 -> intArrayOf(426, 240)
        quality <= 360 -> intArrayOf(640, 360)
        quality <= 480 -> intArrayOf(854, 480)
        quality <= 720 -> intArrayOf(1280, 720)
        quality <= 1080 -> intArrayOf(1920, 1080)
        quality <= 1440 -> intArrayOf(2560, 1440)
        else -> intArrayOf(3840, 2160)
    }

    /**
     * Picks the stream pair closest to [target] height from [streams]' available tracks.
     * @return the updated set, or null when no switch is possible (no candidate, or the best
     *   candidate is already the current video).
     */
    internal fun switchQuality(streams: ActiveStreams, target: Int, lang: String): ActiveStreams? {
        val best = pickVideo(streams.availableVideo, target)
            ?.takeIf { it.url != streams.currentVideo.url } ?: return null
        val audio = pickAudio(streams.availableAudio, lang, best) ?: streams.currentAudio
        return streams.copy(currentVideo = best, currentAudio = audio)
    }

    /** Pick the best video stream closest to [target] quality (height in pixels). */
    fun pickVideo(streams: List<MediaStream>?, target: Int): MediaStream? {
        if (streams.isNullOrEmpty()) return null
        return streams.asSequence()
            .filter { it.height != null }
            .minWithOrNull(
                compareBy<MediaStream> { abs(parseQuality(it) - target) }
                    .thenBy { if (it.type == MediaStreamType.VIDEO_AUDIO) 0 else 1 }
                    .thenBy { if (it.type.hasAudio) 0 else 1 }
            )
    }

    /** Pick the best audio stream for [lang], falling back to default / first available. */
    fun pickAudio(audioStreams: List<MediaStream>, lang: String, chosenVideo: MediaStream?): MediaStream? {
        val audioOnly = audioStreams.filter { !it.type.hasVideo }
        val requested = lang.trim()

        if (requested.isNotEmpty()) {
            audioOnly.firstOrNull { matchesLanguage(it, requested) }?.let { return it }
        }

        audioOnly.firstOrNull {
            it.audioTrackName?.lowercase()?.let { n -> "original" in n || "default" in n } == true
        }?.let { return it }
        audioOnly.firstOrNull { it.audioTrackLang.isNullOrBlank() || it.audioTrackLang == "und" }
            ?.let { return it }
        audioOnly.firstOrNull()?.let { return it }

        if (chosenVideo != null && chosenVideo.type.hasAudio) return chosenVideo
        if (requested.isNotEmpty()) {
            audioStreams.firstOrNull { matchesLanguage(it, requested) }?.let { return it }
        }
        return audioStreams.firstOrNull()
    }

    /** Case-insensitive partial match of [lang] against the stream's language tag and track name. */
    fun matchesLanguage(stream: MediaStream, lang: String): Boolean {
        val needle = lang.lowercase()
        if (needle.isEmpty()) return false
        return stream.audioTrackLang?.lowercase()?.contains(needle) == true
                || stream.audioTrackName?.lowercase()?.contains(needle) == true
    }
}
