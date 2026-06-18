package com.dreamdisplays.player.stream

import com.dreamdisplays.media.api.MediaStream
import com.dreamdisplays.media.api.MediaStreamType
import kotlin.math.abs

/** Pure helpers for parsing quality values and picking video / audio tracks from a [MediaStream] list. */
object MediaStreamSelector {
    private val realtimeSafeSelection: Boolean =
        System.getProperty("dreamdisplays.stream.realtimeSafe", "true").toBoolean()
    private val defaultPreferFps60: Boolean =
        System.getProperty("dreamdisplays.stream.prefer60", "false").toBoolean()
    private val defaultFps60Penalty: Int =
        System.getProperty("dreamdisplays.stream.fps60Penalty", "420").toIntOrNull()?.coerceAtLeast(0) ?: 420
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()
    private val osArch: String = System.getProperty("os.arch").orEmpty().lowercase()
    private val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")
    private val isWindows: Boolean = osName.contains("win")
    private val isAppleSilicon: Boolean = isMac && (osArch.contains("aarch64") || osArch.contains("arm64"))

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
        // keep the current audio so the progressive pick isn't reverted on a quality switch
        return streams.copy(currentVideo = best, currentAudio = streams.currentAudio)
    }

    /** Pick the best video stream closest to [target] quality (height in pixels). */
    fun pickVideo(streams: List<MediaStream>?, target: Int, preferFps60: Boolean = defaultPreferFps60): MediaStream? {
        if (streams.isNullOrEmpty()) return null
        return streams.asSequence()
            .filter { it.height != null }
            .minWithOrNull(
                compareBy<MediaStream> { realtimeScore(it, target, preferFps60) }
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
    fun describeVideoChoice(stream: MediaStream?, target: Int, preferFps60: Boolean = defaultPreferFps60): String {
        if (stream == null) return "none target=${target}p"
        return "selected=${stream.height ?: "?"}p" +
                " fps=${stream.fps ?: "?"}" +
                " codec=${stream.codec ?: "?"}" +
                " bitrate=${stream.bitrate ?: "?"}" +
                " target=${target}p" +
                " score=${realtimeScore(stream, target, preferFps60)}" +
                " codecPenalty=${platformCodecPenalty(stream)}" +
                " fpsPenalty=${fpsPenalty(stream, preferFps60)}" +
                " fps60Penalty=$defaultFps60Penalty" +
                " realtimeSafe=$realtimeSafeSelection"
    }

    /**
     * Selection score measured in "height pixels plus realtime risk". It still strongly prefers
     * the requested height, but lets a hardware-friendly 1080p/1440p stream beat a risky 2160p
     * software path on platforms where that path is known to stutter.
     */
    private fun realtimeScore(stream: MediaStream, target: Int, preferFps60: Boolean): Int {
        val height = parseQuality(stream)
        return abs(height - target) + platformCodecPenalty(stream) + fpsPenalty(stream, preferFps60)
    }

    /**
     * Decode-cost penalty by platform. macOS is deliberately conservative: YouTube 4K VP9 often
     * lands outside the fast VideoToolbox path or pays an expensive hardware-surface download,
     * which is exactly the stutter pattern the LAV diagnostics exposed.
     */
    private fun platformCodecPenalty(stream: MediaStream): Int {
        if (!realtimeSafeSelection) return genericCodecRank(stream) * 32
        val height = stream.height ?: Int.MAX_VALUE
        return when {
            isMac -> when (codecFamily(stream)) {
                CodecFamily.H264 -> 0
                CodecFamily.HEVC -> if (height <= 2160) 80 else 300
                CodecFamily.AV1 -> if (isAppleSilicon && height <= 2160) 160 else 1200
                CodecFamily.VP9 -> when {
                    height <= 1080 -> 220
                    height <= 1440 -> 760
                    else -> 1700
                }
                CodecFamily.UNKNOWN -> 1300
            }
            isWindows -> when (codecFamily(stream)) {
                CodecFamily.H264 -> 0
                CodecFamily.HEVC -> 90
                CodecFamily.VP9 -> 140
                CodecFamily.AV1 -> 220
                CodecFamily.UNKNOWN -> 900
            }
            else -> when (codecFamily(stream)) {
                CodecFamily.H264 -> 0
                CodecFamily.HEVC -> 120
                CodecFamily.VP9 -> 180
                CodecFamily.AV1 -> 260
                CodecFamily.UNKNOWN -> 900
            }
        }
    }

    /**
     * 60 fps is not free in Minecraft's world render pass. By default, a 60 fps stream must be
     * substantially better than a 30 fps alternative to win; otherwise audio stays smooth while
     * the displayed video drops frames under render-thread pressure.
     */
    private fun fpsPenalty(stream: MediaStream, preferFps60: Boolean): Int {
        val fps = stream.fps ?: return 16
        return if (preferFps60) {
            if (fps >= 50.0) 0 else 48
        } else {
            if (fps >= 50.0) defaultFps60Penalty else 0
        }
    }

    private fun codecFamily(stream: MediaStream): CodecFamily {
        val c = stream.codec?.lowercase() ?: return CodecFamily.UNKNOWN
        return when {
            c.startsWith("avc") || c.startsWith("h264") -> CodecFamily.H264
            c.startsWith("hvc") || c.startsWith("hev") || c.startsWith("h265") -> CodecFamily.HEVC
            c.startsWith("vp9") || c.startsWith("vp09") -> CodecFamily.VP9
            c.startsWith("av01") || c.startsWith("av1") -> CodecFamily.AV1
            else -> CodecFamily.UNKNOWN
        }
    }

    private fun genericCodecRank(stream: MediaStream): Int {
        return when (codecFamily(stream)) {
            CodecFamily.H264 -> 0
            CodecFamily.HEVC -> 1
            CodecFamily.VP9 -> 2
            CodecFamily.AV1 -> 3
            CodecFamily.UNKNOWN -> 4
        }
    }

    private enum class CodecFamily { H264, HEVC, VP9, AV1, UNKNOWN }

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
