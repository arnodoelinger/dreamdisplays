package com.dreamdisplays.platform.client.storage

import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.display.model.settings.ClientDisplaySettings
import com.dreamdisplays.api.display.model.settings.ClientSettingsStorage
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache and JSON persistence for per-display, client-local [ClientDisplaySettings].
 *
 * Backed by `client-display-settings.json`. Every mutator persists immediately so the file always
 * reflects the latest viewer preferences.
 */
object ClientSettingsStore : ClientSettingsStorage {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** File name for the JSON settings file. */
    private const val FILE_NAME = "client-display-settings.json"
    private const val SCHEMA_VERSION = 1

    private val jsonFiles = JsonFileStore()
    private val settingsSerializer = MapSerializer(String.serializer(), ClientDisplaySettings.serializer())

    /** In-memory cache of settings, keyed by display UUID. */
    private val settings = ConcurrentHashMap<UUID, ClientDisplaySettings>()

    /** Loads all client display settings from disk into the in-memory map, replacing any current state. */
    override fun load() {
        val loaded: Map<String, ClientDisplaySettings> =
            jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), settingsSerializer, SCHEMA_VERSION, logger) ?: return
        settings.clear()
        loaded.forEach { (key, value) ->
            runCatching { settings[UUID.fromString(key)] = value }
                .onFailure { logger.error("Invalid UUID in client display settings: $key.") }
        }
    }

    /** Persists the in-memory settings map to disk. */
    override fun save() {
        if (!jsonFiles.ensureDir(logger)) return
        val toSave = settings.entries.associate { (k, v) -> k.toString() to v }
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), settingsSerializer, toSave, SCHEMA_VERSION, logger)
    }

    /** Returns the settings for [displayUuid], creating a default entry if absent. */
    override fun getSettings(
        displayUuid: UUID,
        defaultVolume: Float,
    ): ClientDisplaySettings =
        settings.computeIfAbsent(displayUuid) {
            ClientDisplaySettings(volume = defaultVolume.coerceIn(0f, 1f))
        }

    /** Updates all playback settings for [displayUuid] and immediately persists them to disk. */
    override fun updateSettings(
        displayUuid: UUID,
        volume: Float,
        quality: VideoQuality,
        brightness: Float,
        muted: Boolean,
        paused: Boolean,
    ) {
        val s = getSettings(displayUuid)
        s.volume = volume
        s.quality = quality.serialize()
        s.brightness = brightness
        s.muted = muted
        s.paused = paused
        save()
    }

    /** Sets the client-side URL and language override for [displayUuid] and saves. */
    override fun setUrlOverride(displayUuid: UUID, url: String?, lang: String?) {
        val s = getSettings(displayUuid)
        s.urlOverride = url
        s.langOverride = lang
        save()
    }

    /** Sets the viewer-picked audio track language for [displayUuid] and saves. */
    override fun setAudioTrackLang(displayUuid: UUID, lang: String?) {
        val s = getSettings(displayUuid)
        s.audioTrackLang = lang
        save()
    }

    /** Sets the viewer's subtitle track language for [displayUuid] (null disables subtitles) and saves. */
    override fun setSubtitleTrackLang(displayUuid: UUID, lang: String?) {
        val s = getSettings(displayUuid)
        s.subtitleTrackLang = lang
        s.subtitlesEnabled = lang != null
        save()
    }

    /** Sets the last known playback position for [displayUuid] and saves. */
    override fun setSavedTimeNanos(displayUuid: UUID, nanos: Long) {
        val s = getSettings(displayUuid)
        s.savedTimeNanos = nanos
        save()
    }

    /** Sets whether [displayUuid] is pinned to a Picture-in-Picture overlay and saves. */
    override fun setPipOpen(displayUuid: UUID, open: Boolean) {
        val s = getSettings(displayUuid)
        s.pipOpen = open
        save()
    }

    /** Sets whether the 3D acoustics engine applies to [displayUuid] and saves. */
    override fun setAcousticsEnabled(displayUuid: UUID, enabled: Boolean) {
        val s = getSettings(displayUuid)
        s.acousticsEnabled = enabled
        save()
    }

    /** Removes the settings for [displayUuid], persisting only if an entry existed. Returns whether anything was removed. */
    override fun remove(displayUuid: UUID): Boolean {
        val removed = settings.remove(displayUuid) != null
        if (removed) save()
        return removed
    }
}
