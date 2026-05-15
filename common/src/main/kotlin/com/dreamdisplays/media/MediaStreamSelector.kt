package com.dreamdisplays.media

import com.dreamdisplays.ytdlp.YtStream
import kotlin.math.abs

/**
 * Pure helpers for parsing stream quality labels and picking video / audio
 * tracks from the candidate lists returned by `yt-dlp`.
 */
object MediaStreamSelector {


    fun parseQuality(stream: YtStream): Int = parseQualityValue(stream.resolution, Int.MAX_VALUE)


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


    fun qualityToDims(quality: Int): IntArray = when {
        quality <= 240 -> intArrayOf(426, 240)
        quality <= 360 -> intArrayOf(640, 360)
        quality <= 480 -> intArrayOf(854, 480)
        quality <= 720 -> intArrayOf(1280, 720)
        quality <= 1080 -> intArrayOf(1920, 1080)
        quality <= 1440 -> intArrayOf(2560, 1440)
        else -> intArrayOf(3840, 2160)
    }


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


    fun pickAudio(audioStreams: List<YtStream>, lang: String, chosenVideo: YtStream?): YtStream? {
        val audioOnly = audioStreams.filter { !it.hasVideo() }
        val requested = lang.trim()

        // Explicit language requested: match case-insensitively against the
        // track id (e.g. "ru", "en-US") or the human-readable name (e.g.
        // "Russian", "English (original)").  YouTube's audioTrackName for
        // multilingual videos carries the language word, so this catches both
        // codes and full names regardless of case.
        if (requested.isNotEmpty()) {
            audioOnly.firstOrNull { matchesLanguage(it, requested) }?.let { return it }
        }

        // No explicit language (or no match): prefer the track YouTube marks
        // as the original / default, falling back to an unspecified ("und")
        // language code, then the first audio stream.
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


    fun matchesLanguage(stream: YtStream, lang: String): Boolean {
        val needle = lang.lowercase()
        if (needle.isEmpty()) return false
        return stream.audioTrackId?.lowercase()?.contains(needle) == true
                || stream.audioTrackName?.lowercase()?.contains(needle) == true
    }
}
