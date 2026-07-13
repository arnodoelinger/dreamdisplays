package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.server.storage.StorageBackend
import com.dreamdisplays.util.asJsonObjectOrNull
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.toPlainJsonValue
import org.slf4j.Logger
import org.tomlj.TomlTable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `config.toml` shape shared by every server platform (`Paper`, Fabric, NeoForge). [Config] and
 * [VanillaConfig] each own only the platform-specific I / O (resource lookup, data directory,
 * `Material` resolution) and delegate parsing/language-loading here so the two never drift.
 */

/** Language section of the config. */
data class LanguageSection(
    val default_language: String = "en",
)

/** Settings section of the config. */
data class SettingsSection(
    val reports: ReportsConfig = ReportsConfig(),
    val updates: UpdatesConfig = UpdatesConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val fullscreen: FullscreenConfig = FullscreenConfig(),
) {
    // Reports
    val webhookUrl get() = reports.webhook_url
    val reportCooldown get() = reports.cooldown * 1000L

    // Updates
    val repoName get() = updates.repo_name
    val repoOwner get() = updates.repo_owner
    val updatesEnabled get() = display.updates && updates.enabled

    // Particles
    val particlesEnabled get() = display.particles
    val particleRenderDelay = 2

    // Mod detection
    val modDetectionEnabled get() = display.mod_detection_enabled

    // Display
    val minWidth get() = display.min_width
    val minHeight get() = display.min_height
    val maxWidth get() = display.max_width
    val maxHeight get() = display.max_height
    val maxRenderDistance get() = display.max_render_distance

    /** Volume in [0, 1] ready for the wire; config stores 0-100 percent. */
    val defaultVolume get() = display.default_volume / 200f

    // Fullscreen broadcasts
    val fullscreenAllowForced get() = fullscreen.allow_forced
    val fullscreenQualityCap get() = fullscreen.quality_cap
    val fullscreenDefaultMode: com.dreamdisplays.api.playback.FullscreenMode
        get() = runCatching { com.dreamdisplays.api.playback.FullscreenMode.valueOf(fullscreen.default_mode.uppercase()) }
            .getOrDefault(com.dreamdisplays.api.playback.FullscreenMode.STANDARD)

    /** Reports section. */
    data class ReportsConfig(
        val webhook_url: String = "",
        val cooldown: Int = 15,
    )

    /** Updates section. */
    data class UpdatesConfig(
        val enabled: Boolean = true,
        val repo_name: String = "dreamdisplays",
        val repo_owner: String = "arnodoelinger",
    )

    /** Display section. Materials are registry ids (e.g. `minecraft:diamond_axe`); platform code
     *  resolves them to their own material type (see `selectionMaterial`/`baseMaterial` in [Config]/[VanillaConfig]). */
    data class DisplayConfig(
        val selection_material: String = "minecraft:diamond_axe",
        val base_material: String = "minecraft:black_concrete",
        val updates: Boolean = true,
        val particles: Boolean = true,
        val particles_color: String = "#00FFFF",
        val mod_detection_enabled: Boolean = true,
        val min_width: Int = 1,
        val min_height: Int = 1,
        val max_width: Int = 32,
        val max_height: Int = 24,
        val max_render_distance: Double = 96.0,
        val default_volume: Int = 50,
    )

    /** Fullscreen broadcast section. */
    data class FullscreenConfig(
        val default_mode: String = "STANDARD",
        val allow_forced: Boolean = true,
        val quality_cap: Int = 1080,
    )
}

/** Storage section of the config. */
data class StorageSection(
    val storage: StorageConfig = StorageConfig(),
) {
    val type get() = storage.type
    val host get() = storage.host
    val port get() = storage.port
    val database get() = storage.database
    val password get() = storage.password
    val username get() = storage.username
    val tablePrefix get() = storage.table_prefix
    val useSSL get() = storage.use_ssl

    data class StorageConfig(
        val type: String = StorageBackend.SQLITE.configToken,
        val host: String = "localhost",
        val port: String = "3306",
        val database: String = "my_database",
        val password: String = "veryStrongPassword",
        val username: String = "username",
        val table_prefix: String = "",
        val use_ssl: Boolean = false,
    )
}

/** Permissions section of the config. */
data class PermissionsSection(
    val permissions: PermissionsConfig = PermissionsConfig(),
) {
    val create get() = permissions.create
    val video get() = permissions.video
    val info get() = permissions.info
    val premium get() = permissions.premium
    val delete get() = permissions.delete
    val list get() = permissions.list
    val reload get() = permissions.reload
    val updates get() = permissions.updates
    val help get() = permissions.help
    val stats get() = permissions.stats
    val toggleOthers get() = permissions.toggle_others
    val local get() = permissions.local
    val synced get() = permissions.synced
    val broadcast get() = permissions.broadcast
    val watchparty get() = permissions.watchparty
    val lock get() = permissions.lock
    val deleteOthers get() = permissions.delete_others
    val createBypass get() = permissions.create_bypass
    val fullscreenStart get() = permissions.fullscreen_start
    val fullscreenStop get() = permissions.fullscreen_stop
    val fullscreenList get() = permissions.fullscreen_list

    data class PermissionsConfig(
        val create: String = "dreamdisplays.create",
        val video: String = "dreamdisplays.video",
        val info: String = "dreamdisplays.info",
        val premium: String = "group.premium",
        val delete: String = "dreamdisplays.delete",
        val list: String = "dreamdisplays.list",
        val reload: String = "dreamdisplays.reload",
        val updates: String = "dreamdisplays.updates",
        val help: String = "dreamdisplays.help",
        val stats: String = "dreamdisplays.stats",
        val toggle_others: String = "dreamdisplays.toggle.others",
        val local: String = "dreamdisplays.local",
        val synced: String = "dreamdisplays.synced",
        val broadcast: String = "dreamdisplays.broadcast",
        val watchparty: String = "dreamdisplays.watchparty",
        val lock: String = "dreamdisplays.lock",
        val delete_others: String = "dreamdisplays.delete.others",
        val create_bypass: String = "dreamdisplays.create.bypass",
        val fullscreen_start: String = "dreamdisplays.fullscreen.start",
        val fullscreen_stop: String = "dreamdisplays.fullscreen.stop",
        val fullscreen_list: String = "dreamdisplays.fullscreen.list",
    )
}

/** Parsed result of a `config.toml`. */
data class ParsedServerConfig(
    val language: LanguageSection,
    val settings: SettingsSection,
    val storage: StorageSection,
    val permissions: PermissionsSection,
)

/** Parses [t] into a [ParsedServerConfig], falling back to defaults for missing or malformed sections. */
fun parseServerConfig(t: TomlTable?): ParsedServerConfig = ParsedServerConfig(
    language = LanguageSection(
        default_language = t?.getString("language.default_language") ?: "en"
    ),
    settings = SettingsSection(
        reports = SettingsSection.ReportsConfig(
            webhook_url = t?.getString("reports.webhook_url") ?: "",
            cooldown = t?.getLong("reports.cooldown")?.toInt() ?: 15
        ),
        updates = SettingsSection.UpdatesConfig(
            enabled = t?.getBoolean("updates.enabled") ?: true,
            repo_name = t?.getString("updates.repo_name") ?: "dreamdisplays",
            repo_owner = t?.getString("updates.repo_owner") ?: "arnodoelinger"
        ),
        display = SettingsSection.DisplayConfig(
            selection_material = t?.getString("display.selection_material") ?: "minecraft:diamond_axe",
            base_material = t?.getString("display.base_material") ?: "minecraft:black_concrete",
            updates = t?.getBoolean("display.updates") ?: true,
            particles = t?.getBoolean("display.particles") ?: true,
            particles_color = t?.getString("display.particles_color") ?: "#00FFFF",
            mod_detection_enabled = t?.getBoolean("display.mod_detection_enabled") ?: true,
            min_width = t?.getLong("display.min_width")?.toInt() ?: 1,
            min_height = t?.getLong("display.min_height")?.toInt() ?: 1,
            max_width = t?.getLong("display.max_width")?.toInt() ?: 32,
            max_height = t?.getLong("display.max_height")?.toInt() ?: 24,
            max_render_distance = t?.getDouble("display.max_render_distance") ?: 96.0,
            default_volume = t?.getLong("display.default_volume")?.toInt()?.coerceIn(0, 100) ?: 50,
        ),
        fullscreen = SettingsSection.FullscreenConfig(
            default_mode = t?.getString("fullscreen.default_mode") ?: "STANDARD",
            allow_forced = t?.getBoolean("fullscreen.allow_forced") ?: true,
            quality_cap = t?.getLong("fullscreen.quality_cap")?.toInt() ?: 1080,
        ),
    ),
    storage = StorageSection(
        storage = StorageSection.StorageConfig(
            type = t?.getString("storage.type") ?: StorageBackend.SQLITE.configToken,
            host = t?.getString("storage.host") ?: "localhost",
            port = t?.getString("storage.port") ?: "3306",
            database = t?.getString("storage.database") ?: "my_database",
            password = t?.getString("storage.password") ?: "veryStrongPassword",
            username = t?.getString("storage.username") ?: "username",
            table_prefix = t?.getString("storage.table_prefix") ?: "",
            use_ssl = t?.getBoolean("storage.use_ssl") ?: false,
        )
    ),
    permissions = PermissionsSection(
        permissions = PermissionsSection.PermissionsConfig(
            create = t?.getString("permissions.create") ?: "dreamdisplays.create",
            video = t?.getString("permissions.video") ?: "dreamdisplays.video",
            info = t?.getString("permissions.info") ?: "dreamdisplays.info",
            premium = t?.getString("permissions.premium") ?: "group.premium",
            delete = t?.getString("permissions.delete") ?: "dreamdisplays.delete",
            list = t?.getString("permissions.list") ?: "dreamdisplays.list",
            reload = t?.getString("permissions.reload") ?: "dreamdisplays.reload",
            updates = t?.getString("permissions.updates") ?: "dreamdisplays.updates",
            help = t?.getString("permissions.help") ?: "dreamdisplays.help",
            stats = t?.getString("permissions.stats") ?: "dreamdisplays.stats",
            toggle_others = t?.getString("permissions.toggle_others") ?: "dreamdisplays.toggle.others",
            local = t?.getString("permissions.local") ?: "dreamdisplays.local",
            synced = t?.getString("permissions.synced") ?: "dreamdisplays.synced",
            broadcast = t?.getString("permissions.broadcast") ?: "dreamdisplays.broadcast",
            watchparty = t?.getString("permissions.watchparty") ?: "dreamdisplays.watchparty",
            lock = t?.getString("permissions.lock") ?: "dreamdisplays.lock",
            delete_others = t?.getString("permissions.delete_others") ?: "dreamdisplays.delete.others",
            create_bypass = t?.getString("permissions.create_bypass") ?: "dreamdisplays.create.bypass",
            fullscreen_start = t?.getString("permissions.fullscreen_start") ?: "dreamdisplays.fullscreen.start",
            fullscreen_stop = t?.getString("permissions.fullscreen_stop") ?: "dreamdisplays.fullscreen.stop",
            fullscreen_list = t?.getString("permissions.fullscreen_list") ?: "dreamdisplays.fullscreen.list",
        )
    ),
)

/** Language files bundled with every server platform's JAR. */
internal val LANGUAGE_FILES = listOf(
    "en.json", "es.json", "fr.json", "it.json", "pl.json",
    "ru.json", "uk.json", "de.json", "cs.json", "be.json", "he.json",
)

/** Maps a Minecraft locale string (e.g. `ru_ru`, `ru-ru`) to the plugin's short language code (e.g. `ru`). */
internal fun mapLocaleToLang(locale: String): String {
    return when (val normalized = locale.lowercase()) {
        "ru_ru", "ru-ru" -> "ru"
        "uk_ua", "uk-ua" -> "uk"
        "pl_pl", "pl-pl" -> "pl"
        "de_de", "de-de" -> "de"
        "cs_cz", "cs-cz" -> "cs"
        "be_by", "be-by" -> "be"
        "he_il", "he-il" -> "he"
        else -> normalized.substringBefore('_').substringBefore('-').ifEmpty { "en" }
    }
}

private fun loadLanguageMessages(file: File): Map<String, Any> {
    val root = DreamJson.compact.parseToJsonElement(file.readText()).asJsonObjectOrNull()
        ?: error("Language file root must be a JSON object.")
    return root.mapNotNull { (key, value) ->
        value.toPlainJsonValue()?.let { key to it }
    }.toMap()
}

/**
 * Extracts the bundled per-language JSONs into [dataDir]/lang and loads them into memory, resolving
 * messages by locale with an English fallback. [resourceLookup] and [warn] are the only platform seam:
 * Paper resolves resources via the plugin classloader ([net.md_5][io.papermc] `JavaPlugin.getResource`),
 * Fabric/NeoForge via the raw JVM classloader.
 */
internal class LanguageStore(
    private val dataDir: File,
    private val resourceLookup: (String) -> InputStream?,
    private val warn: (String) -> Unit,
) {
    val messages = mutableMapOf<String, Any>()
    val languages = mutableMapOf<String, Map<String, Any>>()

    /** Copies bundled language JSONs into [dataDir]/lang; overwrites existing files when [overwrite] is true. */
    fun extractLangFiles(overwrite: Boolean) {
        val langFolder = File(dataDir, "lang")
        if (!langFolder.exists() && !langFolder.mkdirs()) {
            warn("Could not create lang folder")
            return
        }

        LANGUAGE_FILES.forEach { fileName ->
            runCatching {
                val resource = resourceLookup("assets/dreamdisplays/lang/server/$fileName")
                    ?: resourceLookup("assets/dreamdisplays/lang/$fileName")
                resource?.use { input ->
                    val target = File(langFolder, fileName)
                    if (overwrite || !target.exists()) {
                        Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }.onFailure {
                warn("Could not extract $fileName: ${it.message}")
            }
        }
    }

    /** Parses every language JSON in [dataDir]/lang into [languages] and reseeds the English fallback map. */
    fun loadMessages(logger: Logger) {
        languages.clear()
        LANGUAGE_FILES.forEach { fileName ->
            val langCode = fileName.removeSuffix(".json")
            val langFile = File(dataDir, "lang/$fileName")
            if (langFile.exists()) {
                runCatching {
                    languages[langCode] = loadLanguageMessages(langFile)
                }.onFailure {
                    logger.error("Error loading language file: $fileName.", it)
                }
            }
        }
        messages.clear()
        messages.putAll(languages["en"] ?: emptyMap())
    }

    /** Resolves [key] in [locale], then in [defaultLocale], then in the English fallback. */
    fun getMessage(locale: String, defaultLocale: String, key: String): Any? {
        val langCode = mapLocaleToLang(locale)
        val defaultLangCode = mapLocaleToLang(defaultLocale)
        return languages[langCode]?.get(key)
            ?: languages[defaultLangCode]?.get(key)
            ?: messages[key]
    }
}
