package com.dreamdisplays.display

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
import java.io.*
import java.security.MessageDigest
import java.util.*

/** Manages loading and saving of display settings and data. */
object DisplaySettings {
    private val logger = LoggerFactory.getLogger("DreamDisplays/DisplaySettings")
    private val SETTINGS_DIR = File("./config/dreamdisplays")
    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private val displaySettings = HashMap<UUID, ClientDisplaySettings>()
    private val serverDisplays = HashMap<String, MutableMap<UUID, FullDisplayData>>()
    private var currentServerId: String? = null

    /** Loads per-display client settings from `client-display-settings.json` into the in-memory map. */
    fun load() {
        if (!SETTINGS_DIR.exists() && !SETTINGS_DIR.mkdirs()) {
            logger.error("Failed to create settings directory.")
            return
        }
        val clientSettingsFile = File(SETTINGS_DIR, "client-display-settings.json")
        try {
            FileReader(clientSettingsFile).use { reader ->
                val type = object : TypeToken<Map<String, ClientDisplaySettings>>() {}.type
                val loaded: Map<String, ClientDisplaySettings>? = GSON.fromJson(reader, type)
                if (loaded != null) {
                    displaySettings.clear()
                    loaded.forEach { (key, value) ->
                        runCatching { displaySettings[UUID.fromString(key)] = value }
                            .onFailure { logger.error("Invalid UUID in client display settings: $key.") }
                    }
                }
            }
        } catch (_: FileNotFoundException) {
        } catch (e: IOException) {
            logger.error("Failed to load client display settings", e)
        }
    }

    /** Loads the display registry for [serverId] from `server-{serverId}-displays.json` and sets it as current. */
    fun loadServerDisplays(serverId: String) {
        currentServerId = serverId
        val serverFile = readableServerFile(serverId)
        try {
            FileReader(serverFile).use { reader ->
                val type = object : TypeToken<Map<String, FullDisplayData>>() {}.type
                val loaded: Map<String, FullDisplayData>? = GSON.fromJson(reader, type)
                val displays = HashMap<UUID, FullDisplayData>()
                loaded?.forEach { (key, value) ->
                    runCatching { displays[UUID.fromString(key)] = value }
                        .onFailure { logger.error("Invalid UUID in server displays: $key.") }
                }
                serverDisplays[serverId] = displays
                logger.info("Loaded ${displays.size} displays for server: $serverId.")
            }
        } catch (_: FileNotFoundException) {
            serverDisplays[serverId] = HashMap()
        } catch (e: IOException) {
            logger.error("Failed to load server displays for $serverId", e)
            serverDisplays[serverId] = HashMap()
        }
    }

    /** Persists the current in-memory client display settings map to `client-display-settings.json`. */
    fun save() {
        try {
            if (!SETTINGS_DIR.exists() && !SETTINGS_DIR.mkdirs()) {
                logger.error("Failed to create settings directory.")
                return
            }
            val toSave = displaySettings.entries.associate { (k, v) -> k.toString() to v }
            FileWriter(File(SETTINGS_DIR, "client-display-settings.json")).use { writer ->
                GSON.toJson(toSave, writer)
            }
        } catch (e: IOException) {
            logger.error("Failed to save client display settings", e)
        }
    }

    /** Persists the display registry for [serverId] to `server-{serverId}-displays.json`. */
    fun saveServerDisplays(serverId: String) {
        try {
            if (!SETTINGS_DIR.exists() && !SETTINGS_DIR.mkdirs()) {
                logger.error("Failed to create settings directory.")
                return
            }
            val displays = serverDisplays[serverId] ?: HashMap()
            val toSave = displays.entries.associate { (k, v) -> k.toString() to v }
            FileWriter(safeServerFile(serverId)).use { writer ->
                GSON.toJson(toSave, writer)
            }
        } catch (e: IOException) {
            logger.error("Failed to save server displays for $serverId", e)
        }
    }

    /** Returns the [ClientDisplaySettings] for [displayUuid], creating a default entry if absent. */
    fun getSettings(displayUuid: UUID): ClientDisplaySettings =
        displaySettings.getOrPut(displayUuid) { ClientDisplaySettings() }

    /** Updates all playback settings for [displayUuid] and immediately persists them to disk. */
    fun updateSettings(
        displayUuid: UUID,
        volume: Float,
        quality: String,
        brightness: Float,
        muted: Boolean,
        paused: Boolean,
    ) {
        val settings = getSettings(displayUuid)
        settings.volume = volume
        settings.quality = quality
        settings.brightness = brightness
        settings.muted = muted
        settings.paused = paused
        save()
    }

    /** Sets the client-side URL and language override for [displayUuid] and saves. */
    fun setUrlOverride(displayUuid: UUID, url: String?, lang: String?) {
        val settings = getSettings(displayUuid)
        settings.urlOverride = url
        settings.langOverride = lang
        save()
    }

    /** Returns the cached [FullDisplayData] for [displayUuid] on the current server, or null if absent. */
    fun getDisplayData(displayUuid: UUID): FullDisplayData? {
        val server = currentServerId ?: return null
        return serverDisplays[server]?.get(displayUuid)
    }

    /** Stores [data] for [displayUuid] in the current server's registry and persists it to disk. */
    fun saveDisplayData(displayUuid: UUID, data: FullDisplayData) {
        val server = currentServerId ?: return
        serverDisplays.getOrPut(server) { HashMap() }[displayUuid] = data
        saveServerDisplays(server)
    }

    /** Removes [displayUuid] from all server registries and from client settings, then saves both. */
    fun removeDisplay(displayUuid: UUID) {
        for ((serverId, displays) in serverDisplays) {
            if (displays.remove(displayUuid) != null) {
                saveServerDisplays(serverId)
            }
        }
        displaySettings.remove(displayUuid)
        save()
    }

    /**
     * Returns the file to read the display registry for [serverId], preferring the safe hashed filename but falling back
     * to the legacy one for backwards compatibility.
     */
    private fun readableServerFile(serverId: String): File {
        val safe = safeServerFile(serverId)
        val legacy = File(SETTINGS_DIR, "server-$serverId-displays.json")
        return if (safe.exists() || !legacy.exists()) safe else legacy
    }

    /** Returns the file to write the display registry for [serverId], creating a safe hashed filename. */
    private fun safeServerFile(serverId: String): File =
        File(SETTINGS_DIR, "server-${safeServerFileId(serverId)}-displays.json")

    /** Returns a safe filename for [serverId] based on its lowercased slug and SHA-256 hash. */
    private fun safeServerFileId(serverId: String): String {
        val slug = serverId
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "server" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(serverId.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "$slug-$hash"
    }

    class ClientDisplaySettings {
        var volume: Float = 0.5f
        var quality: String = "720"
        var brightness: Float = 1.0f
        var muted: Boolean = false
        var paused: Boolean = true
        var urlOverride: String? = null
        var langOverride: String? = null
    }

    class FullDisplayData(
        var uuid: UUID,
        var x: Int,
        var y: Int,
        var z: Int,
        var facing: String,
        var width: Int,
        var height: Int,
        var videoUrl: String,
        var lang: String,
        var volume: Float,
        var quality: String,
        var brightness: Float,
        var muted: Boolean,
        var isSync: Boolean,
        var ownerUuid: UUID,
        var renderDistance: Int = 64,
        var currentTimeNanos: Long = 0,
    )
}
