package com.dreamdisplays.media.player.stream

import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import com.dreamdisplays.api.media.stream.SupportedCodec
import com.dreamdisplays.util.OsInfo
import kotlin.math.abs

/** Pure helpers for parsing quality values and picking video / audio tracks from a [MediaStream] list. */
object MediaStreamSelector {
    private const val FPS60_PENALTY = 420

    private val isMac: Boolean = OsInfo.isMac
    private val isWindows: Boolean = OsInfo.isWindows
    private val isAppleSilicon: Boolean = OsInfo.isMac && OsInfo.isArm64

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
    internal fun switchQuality(streams: ActiveStreams, target: Int): ActiveStreams? {
        val best = pickVideo(streams.availableVideo, target)
            ?.takeIf { it.url != streams.currentVideo.url } ?: return null
        // Keep the current audio so the progressive pick isn't reverted on a quality switch
        return streams.copy(currentVideo = best, currentAudio = streams.currentAudio)
    }

    /**
     * Switches the active audio track to the one whose URL equals [targetUrl], leaving the video
     * selection untouched.
     *
     * @return the updated set, or null when there's no matching track, or it's already current.
     */
    internal fun switchAudioTrack(streams: ActiveStreams, targetUrl: String): ActiveStreams? {
        val best = streams.availableAudio.firstOrNull { it.url == targetUrl }
            ?.takeIf { it.url != streams.currentAudio.url } ?: return null
        return streams.copy(currentAudio = best)
    }

    /**
     * Pick the best video stream at or below [target] quality (height in pixels), falling back to
     * the closest stream above it only when nothing at-or-below is available — a quality setting is
     * a ceiling, not just a target to get near, so this never silently serves more than asked for.
     */
    fun pickVideo(streams: List<MediaStream>?, target: Int, preferFps60: Boolean = false): MediaStream? {
        if (streams.isNullOrEmpty()) return null
        return streams.asSequence()
            .filter { it.height != null }
            .minWithOrNull(
                compareBy<MediaStream> { parseQuality(it) > target }
                    .thenBy { realtimeScore(it, target, preferFps60) }
                    .thenBy { abs(parseQuality(it) - target) }
                    .thenBy { platformCodecPenalty(it) }
                    .thenBy { fpsPenalty(it, preferFps60) }
                    .thenBy { if (it.type == MediaStreamType.VIDEO_AUDIO) 0 else 1 }
                    .thenBy { if (it.type.hasAudio) 0 else 1 }
                    .thenByDescending { it.bitrate ?: 0 }
            )
    }

    /**
     * Human-readable selector explanation for debug logs. Keep it allocation-only-on-debug by
     * calling this from debug-only branches.
     */
    fun describeVideoChoice(stream: MediaStream?, target: Int, preferFps60: Boolean = false): String {
        if (stream == null) return "none target=${target}p"
        return "selected=${stream.height ?: "?"}p" +
                " fps=${stream.fps ?: "?"}" +
                " codec=${stream.codec ?: "?"}" +
                " bitrate=${stream.bitrate ?: "?"}" +
                " target=${target}p" +
                " score=${realtimeScore(stream, target, preferFps60)}" +
                " codecPenalty=${platformCodecPenalty(stream)}" +
                " fpsPenalty=${fpsPenalty(stream, preferFps60)}"
    }

    private fun realtimeScore(stream: MediaStream, target: Int, preferFps60: Boolean): Int {
        val height = parseQuality(stream)
        return abs(height - target) + platformCodecPenalty(stream) + fpsPenalty(stream, preferFps60)
    }

    private fun platformCodecPenalty(stream: MediaStream): Int {
        val height = stream.height ?: Int.MAX_VALUE
        return when {
            isMac -> when (codec(stream)) {
                SupportedCodec.H264 -> 0
                SupportedCodec.HEVC -> if (height <= 2160) 80 else 300
                SupportedCodec.AV1 -> if (isAppleSilicon && height <= 2160) 160 else 1200
                SupportedCodec.VP9 -> when {
                    height <= 1080 -> 220
                    height <= 1440 -> 760
                    else -> 1700
                }

                SupportedCodec.UNKNOWN -> 1300
            }

            isWindows -> when (codec(stream)) {
                SupportedCodec.H264 -> 0
                SupportedCodec.HEVC -> 90
                SupportedCodec.VP9 -> 140
                SupportedCodec.AV1 -> 220
                SupportedCodec.UNKNOWN -> 900
            }

            else -> when (codec(stream)) {
                SupportedCodec.H264 -> 0
                SupportedCodec.HEVC -> 120
                SupportedCodec.VP9 -> 180
                SupportedCodec.AV1 -> 260
                SupportedCodec.UNKNOWN -> 900
            }
        }
    }

    private fun fpsPenalty(stream: MediaStream, preferFps60: Boolean): Int {
        val fps = stream.fps ?: return 16
        return if (preferFps60) {
            if (fps >= 50.0) 0 else 48
        } else {
            if (fps >= 50.0) FPS60_PENALTY else 0
        }
    }

    private fun codec(stream: MediaStream): SupportedCodec = SupportedCodec.fromCodecName(stream.codec)

    /** Pick the best audio stream for [lang], falling back to default / first available. */
    fun pickAudio(audioStreams: List<MediaStream>, lang: String, chosenVideo: MediaStream?): MediaStream? {
        val audioOnly = audioStreams.filter { !it.type.hasVideo }
        val requested = lang.trim()

        if (requested.isNotEmpty()) {
            audioOnly.filter { matchesLanguage(it, requested) }.highestBitrate()?.let { return it }
        }

        audioOnly.filter {
            it.audioTrackName?.lowercase()?.let { n -> "original" in n || "default" in n } == true
        }.highestBitrate()?.let { return it }
        audioOnly.filter { it.audioTrackLang.isNullOrBlank() || it.audioTrackLang == "und" }
            .highestBitrate()?.let { return it }
        audioOnly.highestBitrate()?.let { return it }

        if (chosenVideo != null && chosenVideo.type.hasAudio) return chosenVideo
        if (requested.isNotEmpty()) {
            audioStreams.filter { matchesLanguage(it, requested) }.highestBitrate()?.let { return it }
        }
        return audioStreams.firstOrNull()
    }

    private fun List<MediaStream>.highestBitrate(): MediaStream? = maxByOrNull { it.bitrate ?: 0 }

    /** Case-insensitive partial match of [lang] against the stream's language tag and track name. */
    fun matchesLanguage(stream: MediaStream, lang: String): Boolean {
        val needle = lang.lowercase()
        return needle.isNotEmpty() && (stream.audioTrackLang?.lowercase()?.contains(needle) == true
                || stream.audioTrackName?.lowercase()?.contains(needle) == true)
    }
}
