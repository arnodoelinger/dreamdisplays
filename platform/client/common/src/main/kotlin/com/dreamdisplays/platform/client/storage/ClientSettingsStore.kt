package com.dreamdisplays.platform.client.storage

import com.dreamdisplays.api.storage.ClientDisplaySettings
import com.dreamdisplays.api.storage.ClientSettingsStorage
import com.dreamdisplays.api.media.VideoQuality
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * In-memory cache and JSON persistence for per-display, client-local [ClientDisplaySettings].
 *
 * Backed by `client-display-settings.json`. Every mutator persists immediately so the file always
 * reflects the latest viewer preferences.
 */
object ClientSettingsStore : ClientSettingsStorage {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/ClientSettingsStore")

    /** File name for the JSON settings file. */
    private const val FILE_NAME = "client-display-settings.json"
    private const val SCHEMA_VERSION = 1

    private val jsonFiles = JsonFileStore()
    private val settingsSerializer = MapSerializer(String.serializer(), ClientDisplaySettings.serializer())

    /** In-memory cache of settings, keyed by display UUID. */
    private val settings = HashMap<UUID, ClientDisplaySettings>()

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
        settings.getOrPut(displayUuid) {
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

    /** Sets the last known playback position for [displayUuid] and saves. */
    override fun setSavedTimeNanos(displayUuid: UUID, nanos: Long) {
        val s = getSettings(displayUuid)
        s.savedTimeNanos = nanos
        save()
    }

    /** Sets the viewer-chosen render distance (in blocks) for [displayUuid] and saves. */
    override fun setRenderDistance(displayUuid: UUID, blocks: Int) {
        val s = getSettings(displayUuid)
        s.renderDistance = blocks
        save()
    }

    /** Sets whether [displayUuid] is pinned to a Picture-in-Picture overlay and saves. */
    override fun setPipOpen(displayUuid: UUID, open: Boolean) {
        val s = getSettings(displayUuid)
        s.pipOpen = open
        save()
    }

    /** Removes the settings for [displayUuid], persisting only if an entry existed. Returns whether anything was removed. */
    override fun remove(displayUuid: UUID): Boolean {
        val removed = settings.remove(displayUuid) != null
        if (removed) save()
        return removed
    }
}
