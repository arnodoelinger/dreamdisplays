package com.dreamdisplays.platform.client.storage

import com.dreamdisplays.media.VideoQuality
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * In-memory cache and JSON persistence for per-display, client-local [ClientDisplaySettings].
 *
 * Backed by `client-display-settings.json`. Every mutator persists immediately so the file always
 * reflects the latest viewer preferences.
 */
object ClientSettingsStore {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/ClientSettingsStore")

    /** File name for the JSON settings file. */
    private const val FILE_NAME = "client-display-settings.json"

    /** In-memory cache of settings, keyed by display UUID. */
    private val settings = HashMap<UUID, ClientDisplaySettings>()

    /** Loads all client display settings from disk into the in-memory map, replacing any current state. */
    fun load() {
        if (!JsonFileStore.ensureDir(logger)) return
        val type = object : TypeToken<Map<String, ClientDisplaySettings>>() {}.type
        val loaded: Map<String, ClientDisplaySettings> =
            JsonFileStore.read(JsonFileStore.file(FILE_NAME), type, logger) ?: return
        settings.clear()
        loaded.forEach { (key, value) ->
            runCatching { settings[UUID.fromString(key)] = value }
                .onFailure { logger.error("Invalid UUID in client display settings: $key.") }
        }
    }

    /** Persists the in-memory settings map to disk. */
    fun save() {
        if (!JsonFileStore.ensureDir(logger)) return
        val toSave = settings.entries.associate { (k, v) -> k.toString() to v }
        JsonFileStore.write(JsonFileStore.file(FILE_NAME), toSave, logger)
    }

    /** Returns the settings for [displayUuid], creating a default entry if absent. */
    fun getSettings(displayUuid: UUID): ClientDisplaySettings =
        settings.getOrPut(displayUuid) { ClientDisplaySettings() }

    /** Updates all playback settings for [displayUuid] and immediately persists them to disk. */
    fun updateSettings(
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
    fun setUrlOverride(displayUuid: UUID, url: String?, lang: String?) {
        val s = getSettings(displayUuid)
        s.urlOverride = url
        s.langOverride = lang
        save()
    }

    /** Removes the settings for [displayUuid], persisting only if an entry existed. Returns whether anything was removed. */
    fun remove(displayUuid: UUID): Boolean {
        val removed = settings.remove(displayUuid) != null
        if (removed) save()
        return removed
    }
}
