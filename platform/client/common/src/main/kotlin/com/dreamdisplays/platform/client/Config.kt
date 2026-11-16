package com.dreamdisplays.platform.client

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.media.source.youtube.cookie.CookieSource
import java.io.File
import kotlin.math.roundToInt

/** Client configuration loaded from and persisted to `config.yml`. */
class Config(private val baseDir: File) {
    private val file = File(baseDir, "config.yml")

    var muteOnAltTab: Boolean = false
    var defaultDistance: Int = 96
    var defaultDisplayVolume: Double = 0.5
    var displaysEnabled: Boolean = true
    var ytdlpCookieSource: CookieSource = CookieSource.NONE
    var ytdlpProxy: String = ""
    var ytdlpJsRuntime: String = "auto"
    var useHwAccel: Boolean = true
    var unshadedDisplays: Boolean = true
    var audioAcoustics: AcousticQuality = AcousticQuality.ADVANCED
    var audioBinauralOutput: Boolean = true
    var audioNormalization: Boolean = true
    var newDisplayVolume: Double = DEFAULT_NEW_DISPLAY_VOLUME
    var newDisplayQuality: String = DEFAULT_NEW_DISPLAY_QUALITY
    var displayDistanceChunks: Int = 0
    var maxActiveDisplays: Int = 0

    init {
        load()
    }

    /** Re-reads values from disk, replacing any in-memory state. */
    fun reload() = load()

    private fun load() {
        if (!file.exists()) {
            save(); return
        }
        val data = file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null
                else line.substring(0, colon).trim() to
                        line.substring(colon + 1).trim().removeSurrounding("'").removeSurrounding("\"")
            }
            .toMap()

        muteOnAltTab = data["mute-on-alt-tab"]?.toBooleanStrictOrNull() ?: muteOnAltTab
        val rawDistance = data["default-render-distance"]?.toIntOrNull() ?: defaultDistance
        defaultDistance = ((rawDistance / 16.0).roundToInt().coerceIn(2, 12)) * 16
        defaultDisplayVolume = data["default-default-display-volume"]?.toDoubleOrNull() ?: defaultDisplayVolume
        displaysEnabled = data["displays-enabled"]?.toBooleanStrictOrNull() ?: displaysEnabled
        ytdlpCookieSource = data["ytdlp-cookies-from-browser"]
            ?.let { CookieSource.fromConfig(it) }
            ?: ytdlpCookieSource
        ytdlpProxy = data["ytdlp-proxy"] ?: ytdlpProxy
        ytdlpJsRuntime = data["ytdlp-js-runtime"]?.ifBlank { null } ?: ytdlpJsRuntime
        useHwAccel = data["use-hw-accel"]?.toBooleanStrictOrNull() ?: useHwAccel
        unshadedDisplays = data["unshaded-displays"]?.toBooleanStrictOrNull() ?: unshadedDisplays
        audioAcoustics = data["audio-acoustics"]?.let { token ->
            AcousticQuality.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
        } ?: audioAcoustics
        audioNormalization = data["audio-normalization"]?.toBooleanStrictOrNull() ?: audioNormalization
        newDisplayVolume = data["new-display-volume"]?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: newDisplayVolume
        newDisplayQuality = data["new-display-quality"]?.let { VideoQuality.parse(it).serialize() } ?: newDisplayQuality
        displayDistanceChunks = data["display-distance-chunks"]?.toIntOrNull()?.coerceIn(0, MAX_DISTANCE_CHUNKS)
            ?: displayDistanceChunks
        maxActiveDisplays = data["max-active-displays"]?.toIntOrNull()?.coerceIn(0, MAX_ACTIVE_DISPLAYS)
            ?: maxActiveDisplays
        audioBinauralOutput = when (data["audio-output-profile"]?.lowercase()) {
            "speakers" -> false
            "headphones", "auto" -> true
            else -> audioBinauralOutput
        }
    }

    /** Persists the current configuration values to disk. */
    fun save() {
        baseDir.mkdirs()
        file.writeText(buildString {
            appendLine("mute-on-alt-tab: $muteOnAltTab")
            appendLine("default-render-distance: $defaultDistance")
            appendLine("default-default-display-volume: $defaultDisplayVolume")
            appendLine("displays-enabled: $displaysEnabled")
            appendLine("ytdlp-cookies-from-browser: ${ytdlpCookieSource.configToken.yamlQuoted()}")
            appendLine("ytdlp-proxy: ${ytdlpProxy.yamlQuoted()}")
            appendLine("ytdlp-js-runtime: ${ytdlpJsRuntime.yamlQuoted()}")
            appendLine("use-hw-accel: $useHwAccel")
            appendLine("unshaded-displays: $unshadedDisplays")
            appendLine("audio-acoustics: ${audioAcoustics.name.lowercase()}")
            appendLine("audio-output-profile: ${if (audioBinauralOutput) "headphones" else "speakers"}")
            appendLine("audio-normalization: $audioNormalization")
            appendLine("new-display-volume: $newDisplayVolume")
            appendLine("new-display-quality: $newDisplayQuality")
            appendLine("display-distance-chunks: $displayDistanceChunks")
            appendLine("max-active-displays: $maxActiveDisplays")
        })
    }

    companion object {
        const val DEFAULT_NEW_DISPLAY_VOLUME = 0.25
        const val DEFAULT_NEW_DISPLAY_QUALITY = "1080"
        const val MAX_DISTANCE_CHUNKS = 32
        const val MAX_ACTIVE_DISPLAYS = 16

        init {
            System.setProperty("file.encoding", "UTF-8")
        }

        private fun String.yamlQuoted(): String =
            if (isEmpty() || any { it in ":#{}[]|>&!*'\",\n\r\t" })
                "'${replace("'", "''")}'"
            else this
    }
}
