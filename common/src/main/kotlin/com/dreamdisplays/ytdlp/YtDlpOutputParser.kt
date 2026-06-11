package com.dreamdisplays.ytdlp

import com.dreamdisplays.utils.optBoolean
import com.dreamdisplays.utils.optDouble
import com.dreamdisplays.utils.optInt
import com.dreamdisplays.utils.optString
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.round

/**
 * Pure parsing of `yt-dlp -J` JSON output into [YtStream] descriptors: format filtering, live / seekable
 * detection, and resolution extraction.
 *
 * @since 1.0.0
 */
internal object YtDlpOutputParser {
    /** Parses the JSON output of `yt-dlp -J` into a flat list of [YtStream] descriptors. */
    @Throws(IOException::class)
    fun parseFormats(json: String): List<YtStream> {
        val result = ArrayList<YtStream>()
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw IOException("Failed to parse yt-dlp JSON output", e)
        }
        if (!root.isJsonObject) throw IOException("Returned unexpected JSON shape.")
        val obj = root.asJsonObject
        val live = isLive(obj)
        val durationNanos = durationNanos(obj)
        val seekable = !live && durationNanos > 0L
        if (!obj.has("formats") || !obj.get("formats").isJsonArray) return result

        for (el in obj.getAsJsonArray("formats")) {
            if (!el.isJsonObject) continue
            val f = el.asJsonObject

            val url = f.optString("url")
            if (url.isNullOrEmpty()) continue

            val protocol = f.optString("protocol")
            if (!isSupportedProtocol(protocol, url)) continue

            val vcodec = f.optString("vcodec")
            val acodec = f.optString("acodec")
            val ext = f.optString("ext")
            val container = f.optString("container")

            val hasVideo = vcodec != null && vcodec != "none"
            val hasAudio = acodec != null && acodec != "none"
            if (!hasVideo && !hasAudio) continue

            val mime = (if (hasVideo) "video/" else "audio/") + (ext ?: "webm")

            val width = f.optInt("width")
            val height = f.optInt("height")

            val resolution: String? = if (hasVideo) {
                if (height != null && height > 0) "${height}p"
                else extractResolution(f.optString("resolution"), f.optString("format_note"), f.optString("format"))
            } else null

            result.add(
                YtStream(
                    url, mime, container, protocol, resolution,
                    width, height,
                    f.optString("language"), f.optString("format_note"), vcodec, acodec,
                    f.optDouble("fps"), f.optDouble("tbr"),
                    hasVideo, hasAudio, live, seekable, durationNanos
                )
            )
        }
        return result
    }

    /** Returns true if the `yt-dlp` output object indicates a live or upcoming stream. */
    private fun isLive(obj: JsonObject): Boolean {
        if (obj.optBoolean("is_live")) return true
        return LiveStatus.fromWire(obj.optString("live_status")).isLiveLike
    }

    /** Reads the `duration` field and converts seconds to nanoseconds; returns 0 for live or missing values. */
    private fun durationNanos(obj: JsonObject): Long {
        val durationSeconds = obj.optDouble("duration") ?: return 0L
        if (durationSeconds <= 0.0) return 0L
        val nanos = durationSeconds * 1_000_000_000.0
        if (nanos >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        return max(0L, round(nanos).toLong())
    }

    /** Returns true if the stream protocol (or fallback URL extension) is one we can pipe through FFmpeg. */
    private fun isSupportedProtocol(protocol: String?, url: String): Boolean {
        if (protocol.isNullOrBlank()) return true
        if (protocol.startsWith("http")) return true
        if ("m3u8" in protocol) return true
        if ("dash" in protocol) return true
        val lowerUrl = url.lowercase(Locale.ENGLISH)
        return ".m3u8" in lowerUrl || ".mpd" in lowerUrl
    }

    /** Tries each [candidates] string in order, returning the first one that contains a parseable resolution (e.g. "720p"). */
    private fun extractResolution(vararg candidates: String?): String? {
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            var m = Pattern.compile("(\\d{3,4})p").matcher(candidate)
            if (m.find()) return "${m.group(1)}p"
            m = Pattern.compile("(\\d{3,4})").matcher(candidate)
            if (m.find()) return "${m.group(1)}p"
        }
        return null
    }
}
