package com.dreamdisplays.player.stream

import com.dreamdisplays.ytdlp.YtStream
import kotlin.math.abs

/**
 * Pure helpers for parsing stream quality labels and picking video / audio
 * tracks from the candidate lists returned by `yt-dlp`.
 */
object MediaStreamSelector {

    /** Parses a stream's resolution label (e.g. "720p") into an integer quality value (e.g. 720). */
    fun parseQuality(stream: YtStream): Int = parseQualityValue(stream.resolution, Int.MAX_VALUE)

    /** Parses quality value. */
    fun parseQualityValue(raw: String?, fallback: Int): Int {
        if (raw == null) return fallback
        var i = 0
        val n = raw.length
        while (i < n && !raw[i].isDigit()) i++
        val start = i
        while (i < n && raw[i].isDigit()) i++
        if (start == i) return fallback
        return raw.substring(start, i).toIntOrNull() ?: fallback
    }

    /**
     * Maps a quality value (e.g. 720) to a standard video dimension (e.g. 1280x720). This is used to pick the best
     * matching stream when the quality label is missing or unparseable.
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
     * Pick the best matching video stream from the list of candidates, based on the target quality and presence of audio.
     */
    fun pickVideo(streams: List<YtStream>?, target: Int): YtStream? {
        if (streams.isNullOrEmpty()) return null
        return streams.asSequence()
            .filter { it.resolution != null }
            .minWithOrNull(
                compareBy<YtStream> { abs(parseQuality(it) - target) }
                    .thenBy { if (it.isMuxed) 0 else 1 }
                    .thenBy { if (it.hasAudio()) 0 else 1 }
            )
    }

    /**
     * Pick the best matching audio stream from the list of candidates, based on the requested language and presence of
     * audio in the chosen video stream.
     */
    fun pickAudio(audioStreams: List<YtStream>, lang: String, chosenVideo: YtStream?): YtStream? {
        val audioOnly = audioStreams.filter { !it.hasVideo() }
        val requested = lang.trim()

        // Additional languages
        if (requested.isNotEmpty()) {
            audioOnly.firstOrNull { matchesLanguage(it, requested) }?.let { return it }
        }

        // Default
        audioOnly.firstOrNull {
            it.audioTrackName?.lowercase()?.let { n -> "original" in n || "default" in n } == true
        }?.let { return it }
        audioOnly.firstOrNull { it.audioTrackId.isNullOrBlank() || it.audioTrackId == "und" }
            ?.let { return it }
        audioOnly.firstOrNull()?.let { return it }

        if (chosenVideo != null && chosenVideo.hasAudio()) return chosenVideo
        if (requested.isNotEmpty()) {
            audioStreams.firstOrNull { matchesLanguage(it, requested) }?.let { return it }
        }
        return audioStreams.firstOrNull()
    }

    /** Matches the requested language against the stream's audio track ID and name. Case-insensitive, partial match. */
    fun matchesLanguage(stream: YtStream, lang: String): Boolean {
        val needle = lang.lowercase()
        if (needle.isEmpty()) return false
        return stream.audioTrackId?.lowercase()?.contains(needle) == true
                || stream.audioTrackName?.lowercase()?.contains(needle) == true
    }
}
