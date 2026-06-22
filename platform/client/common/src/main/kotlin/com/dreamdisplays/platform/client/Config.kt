package com.dreamdisplays.platform.client

import java.io.File
import kotlin.math.roundToInt

/** Client configuration loaded from and persisted to `config.yml`. */
class Config(private val baseDir: File) {
    /** The backing `config.yml` file on disk. */
    private val file = File(baseDir, "config.yml")

    /** Whether to mute all displays while the game window is not focused. */
    var muteOnAltTab: Boolean = false

    /** Default render distance for new displays, in blocks (snapped to a multiple of 16). */
    var defaultDistance: Int = 96

    /** Default volume for new displays, in range `0.0`..`1.0`. */
    var defaultDisplayVolume: Double = 0.5

    /** Whether displays are enabled at all. */
    var displaysEnabled: Boolean = true

    /** Browser to import `yt-dlp` cookies from, or `none` to disable. */
    var ytdlpCookiesFromBrowser: String = "none"

    /** Proxy URL passed to `yt-dlp`, or empty for a direct connection. */
    var ytdlpProxy: String = ""

    /** Whether to use hardware-accelerated video decoding. */
    var useHwAccel: Boolean = true

    init {
        load()
    }

    /** Re-reads values from disk, replacing any in-memory state. */
    fun reload() = load()

    /**
     * Loads the configuration from disk, applying default values for missing or malformed entries.
     * If the configuration file does not exist, it will be created with default values.
     */
    private fun load() {
        if (!file.exists()) { save(); return }
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
        ytdlpCookiesFromBrowser = data["ytdlp-cookies-from-browser"] ?: ytdlpCookiesFromBrowser
        ytdlpProxy = data["ytdlp-proxy"] ?: ytdlpProxy
        useHwAccel = data["use-hw-accel"]?.toBooleanStrictOrNull() ?: useHwAccel
    }

    /** Persists the current configuration values to disk. */
    fun save() {
        baseDir.mkdirs()
        file.writeText(buildString {
            appendLine("mute-on-alt-tab: $muteOnAltTab")
            appendLine("default-render-distance: $defaultDistance")
            appendLine("default-default-display-volume: $defaultDisplayVolume")
            appendLine("displays-enabled: $displaysEnabled")
            appendLine("ytdlp-cookies-from-browser: ${ytdlpCookiesFromBrowser.yamlQuoted()}")
            appendLine("ytdlp-proxy: ${ytdlpProxy.yamlQuoted()}")
            appendLine("use-hw-accel: $useHwAccel")
        })
    }

    companion object {
        init {
            System.setProperty("file.encoding", "UTF-8")
        }

        /** Wraps the string in single quotes if it is empty or contains YAML-special characters. */
        private fun String.yamlQuoted(): String =
            if (isEmpty() || any { it in ":#{}[]|>&!*'\",\n\r\t" })
                "'${replace("'", "''")}'"
            else this
    }
}
