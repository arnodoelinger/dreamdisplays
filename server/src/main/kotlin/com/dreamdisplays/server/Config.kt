package com.dreamdisplays.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.moandjiezana.toml.Toml
import me.inotsleep.utils.logging.LoggingManager
import me.inotsleep.utils.storage.StorageSettings
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer
import io.github.arsmotorin.ofrat.*
import org.bukkit.Material
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages the configuration of the plugin.
 */
@PaperOnly @NullMarked class Config(private val plugin: Main) {
    private val configFile = File(plugin.dataFolder, "config.toml")
    private var toml = Toml()

    lateinit var language: LanguageSection
        private set
    lateinit var settings: SettingsSection
        private set
    lateinit var storage: StorageSection
        private set
    lateinit var permissions: PermissionsSection
        private set
    val messages = mutableMapOf<String, Any>()
    val languages = mutableMapOf<String, Map<String, Any>>()

    init {
        createDefaultConfig()
        extractLangFiles(true)
        load()
        loadMessages()
    }

    /** Copies the bundled `config.toml` into the plugin folder on first run. */
    private fun createDefaultConfig() {
        if (!configFile.exists()) {
            plugin.dataFolder.mkdirs()
            plugin.getResource("config.toml")?.use {
                Files.copy(it, configFile.toPath())
            } ?: plugin.logger.severe("Could not create default config.toml")
        }
    }

    /** Parses `config.toml`, falling back to defaults when sections are missing or malformed. */
    private fun load() {
        toml = try {
            Toml().read(configFile)
        } catch (e: Exception) {
            LoggingManager.error("[Config] Failed to parse config.toml", e)
            Toml()
        }

        language = toml.to(LanguageSection::class.java) ?: LanguageSection()
        settings = toml.to(SettingsSection::class.java)?.apply { initMaterials() } ?: SettingsSection()
        storage = toml.to(StorageSection::class.java) ?: StorageSection()
        permissions = toml.to(PermissionsSection::class.java) ?: PermissionsSection()
    }

    /** Re-reads `config.toml`, re-extracts language files and refreshes the in-memory message map. */
    fun reload() {
        load()
        extractLangFiles(false)
        loadMessages()
    }

    /** Copies bundled language JSONs into the plugin's `lang/` folder; overwrites when [overwrite] is true. */
    private fun extractLangFiles(overwrite: Boolean) {
        val langFolder = File(plugin.dataFolder, "lang")
        if (!langFolder.exists() && !langFolder.mkdirs()) {
            plugin.logger.warning("Could not create lang folder")
            return
        }

        LANGUAGE_FILES.forEach { fileName ->
            runCatching {
                plugin.getResource("assets/dreamdisplays/lang/$fileName")?.use { input ->
                    val target = File(langFolder, fileName)
                    if (overwrite || !target.exists()) {
                        Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }.onFailure {
                plugin.logger.warning("Could not extract $fileName: ${it.message}")
            }
        }
    }

    /** Parses every language JSON in `lang/` into `languages` and reseeds the English fallback map. */
    private fun loadMessages() {
        languages.clear()
        LANGUAGE_FILES.forEach { fileName ->
            val langCode = fileName.removeSuffix(".json")
            val langFile = File(plugin.dataFolder, "lang/$fileName")
            if (langFile.exists()) {
                runCatching {
                    val msgs = GSON.fromJson<Map<String, Any>>(
                        langFile.readText(),
                        object : TypeToken<Map<String, Any>>() {}.type
                    )
                    languages[langCode] = msgs
                }.onFailure {
                    LoggingManager.error("[Config] Error loading language file: $fileName.", it)
                }
            }
        }
        messages.clear()
        messages.putAll(languages["en"] ?: emptyMap())
    }

    data class LanguageSection(
        val default_language: String = "en",
    )

    data class SettingsSection(
        val reports: ReportsConfig = ReportsConfig(),
        val updates: UpdatesConfig = UpdatesConfig(),
        val display: DisplayConfig = DisplayConfig(),
    ) {
        // Reports
        val webhookUrl get() = reports.webhook_url
        val reportCooldown get() = reports.cooldown * 1000

        // Updates
        val repoName get() = updates.repo_name
        val repoOwner get() = updates.repo_owner
        val updatesEnabled get() = display.updates && updates.enabled

        // Materials
        lateinit var selectionMaterial: Material
        lateinit var baseMaterial: Material

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

        /** Resolves [Material] names from the TOML, defaulting to diamond axe and black concrete. */
        internal fun initMaterials() {
            selectionMaterial = Material.matchMaterial(display.selection_material) ?: Material.DIAMOND_AXE
            baseMaterial = Material.matchMaterial(display.base_material) ?: Material.BLACK_CONCRETE
        }

        data class ReportsConfig(
            val webhook_url: String = "",
            val cooldown: Int = 15,
        )

        data class UpdatesConfig(
            val enabled: Boolean = true,
            val repo_name: String = "dreamdisplays",
            val repo_owner: String = "arsmotorin",
        )

        data class DisplayConfig(
            val selection_material: String = "DIAMOND_AXE",
            val base_material: String = "BLACK_CONCRETE",
            val updates: Boolean = true,
            val particles: Boolean = true,
            val particles_color: String = "#00FFFF",
            val mod_detection_enabled: Boolean = true,
            val min_width: Int = 1,
            val min_height: Int = 1,
            val max_width: Int = 32,
            val max_height: Int = 24,
            val max_render_distance: Double = 96.0,
        )
    }

    // Storage configuration
    data class StorageSection(
        val storage: StorageConfig = StorageConfig(),
    ) : StorageSettings() {
        init {
            this.host = storage.host
            this.port = storage.port
            this.database = storage.database
            this.password = storage.password
            this.username = storage.username
            this.options = "autoReconnect=true&useSSL=false;"
            this.tablePrefix = storage.table_prefix
        }

        data class StorageConfig(
            val type: String = "SQLITE",
            val host: String = "localhost",
            val port: String = "3306",
            val database: String = "my_database",
            val password: String = "veryStrongPassword",
            val username: String = "username",
            val table_prefix: String = "",
        )
    }

    // Permissions configuration
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
        )
    }

    /**
     * Resolves [key] in [player]'s locale, then in the configured default, then in the English fallback.
     * Returns null when no translation exists in any language.
     */
    @Suppress("DEPRECATION")
    fun getMessageForPlayer(player: Player?, key: String): Any? {
        val locale = player?.locale ?: "en_us"
        val langCode = mapLocaleToLang(locale)
        val defaultLangCode = mapLocaleToLang(language.default_language)
        return languages[langCode]?.get(key)
            ?: languages[defaultLangCode]?.get(key)
            ?: messages[key]
    }

    /** Maps a Minecraft locale string (e.g. `ru_ru`) to the plugin's short language code (e.g. `ru`). */
    private fun mapLocaleToLang(locale: String): String {
        return when (val normalized = locale.lowercase()) {
            "ru_ru" -> "ru"
            "uk_ua" -> "uk"
            "pl_pl" -> "pl"
            "de_de" -> "de"
            "cs_cz" -> "cs"
            "be_by" -> "be"
            "he_il" -> "he"
            else -> normalized.substringBefore('_').substringBefore('-').ifEmpty { "en" }
        }
    }

    companion object {
        private val GSON = Gson()
        private val LANGUAGE_FILES =
            listOf(
                "en.json",
                "es.json",
                "fr.json",
                "it.json",
                "pl.json",
                "ru.json",
                "uk.json",
                "de.json",
                "cs.json",
                "be.json",
                "he.json"
            )
    }
}

/**
 * Different from `Paper` implementation.
 *
 * Server-side configuration. Mirrors the `Paper` config structure but uses registry names as strings
 * for materials and does not depend on `Bukkit`.
 */
// TODO: merge
@FabricOnly class FabricConfig {
    private val logger = LoggerFactory.getLogger("DreamDisplays/ServerConfig")

    private val configDir: File = FabricLoader.getInstance().configDir.resolve("dreamdisplays").toFile()
    private val configFile = File(configDir, "config.toml")
    private var toml = Toml()

    lateinit var language: LanguageSection
        private set
    lateinit var settings: SettingsSection
        private set
    lateinit var storage: StorageSection
        private set
    lateinit var permissions: PermissionsSection
        private set

    val messages = mutableMapOf<String, Any>()
    val languages = mutableMapOf<String, Map<String, Any>>()

    init {
        createDefaultConfig()
        extractLangFiles(overwrite = true)
        load()
        loadMessages()
    }

    private fun createDefaultConfig() {
        if (!configDir.exists()) configDir.mkdirs()
        if (!configFile.exists()) {
            val resource = FabricConfig::class.java.classLoader
                .getResourceAsStream("assets/dreamdisplays/lang/server/config.toml")
                ?: FabricConfig::class.java.classLoader
                    .getResourceAsStream("config.toml")
            if (resource != null) {
                resource.use { Files.copy(it, configFile.toPath()) }
            } else {
                configFile.writeText(DEFAULT_CONFIG)
            }
        }
    }

    private fun load() {
        toml = try {
            Toml().read(configFile)
        } catch (e: Exception) {
            logger.error("[Config] Failed to parse config.toml", e)
            Toml()
        }
        language = toml.to(LanguageSection::class.java) ?: LanguageSection()
        settings = toml.to(SettingsSection::class.java) ?: SettingsSection()
        storage = toml.to(StorageSection::class.java) ?: StorageSection()
        permissions = toml.to(PermissionsSection::class.java) ?: PermissionsSection()
    }

    fun reload() {
        load()
        extractLangFiles(overwrite = false)
        loadMessages()
    }

    private fun extractLangFiles(overwrite: Boolean) {
        val langFolder = File(configDir, "lang")
        if (!langFolder.exists() && !langFolder.mkdirs()) {
            logger.warn("[Config] Could not create lang folder")
            return
        }

        LANGUAGE_FILES.forEach { fileName ->
            runCatching {
                val resource = FabricConfig::class.java.classLoader
                    .getResourceAsStream("assets/dreamdisplays/lang/server/$fileName")
                    ?: FabricConfig::class.java.classLoader
                        .getResourceAsStream("assets/dreamdisplays/lang/$fileName")
                resource?.use { input ->
                    val target = File(langFolder, fileName)
                    if (overwrite || !target.exists()) {
                        Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }.onFailure {
                logger.warn("[Config] Could not extract $fileName: ${it.message}")
            }
        }
    }

    private fun loadMessages() {
        languages.clear()
        LANGUAGE_FILES.forEach { fileName ->
            val langCode = fileName.removeSuffix(".json")
            val langFile = File(configDir, "lang/$fileName")
            if (langFile.exists()) {
                runCatching {
                    val msgs = GSON.fromJson<Map<String, Any>>(
                        langFile.readText(),
                        object : TypeToken<Map<String, Any>>() {}.type
                    )
                    languages[langCode] = msgs
                }.onFailure {
                    logger.error("[Config] Error loading language file: $fileName", it)
                }
            }
        }
        messages.clear()
        messages.putAll(languages["en"] ?: emptyMap())
    }

    fun getMessageForPlayer(player: ServerPlayer?, key: String): Any? {
        val locale = player?.clientInformation()?.language() ?: "en"
        val langCode = mapLocaleToLang(locale)
        val defaultLangCode = mapLocaleToLang(language.default_language)
        return languages[langCode]?.get(key)
            ?: languages[defaultLangCode]?.get(key)
            ?: messages[key]
    }

    private fun mapLocaleToLang(locale: String): String {
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

    data class LanguageSection(
        val default_language: String = "en",
    )

    data class SettingsSection(
        val reports: ReportsConfig = ReportsConfig(),
        val updates: UpdatesConfig = UpdatesConfig(),
        val display: DisplayConfig = DisplayConfig(),
    ) {
        val webhookUrl get() = reports.webhook_url
        val reportCooldown get() = reports.cooldown * 1000L

        val repoName get() = updates.repo_name
        val repoOwner get() = updates.repo_owner
        val updatesEnabled get() = display.updates && updates.enabled

        /** Registry name for the selection tool, e.g. "minecraft:diamond_axe". */
        val selectionMaterial get() = display.selection_material

        /** Registry name for the base material, e.g. "minecraft:black_concrete". */
        val baseMaterial get() = display.base_material

        val particlesEnabled get() = display.particles
        val particleRenderDelay = 2

        val modDetectionEnabled get() = display.mod_detection_enabled

        val minWidth get() = display.min_width
        val minHeight get() = display.min_height
        val maxWidth get() = display.max_width
        val maxHeight get() = display.max_height
        val maxRenderDistance get() = display.max_render_distance

        data class ReportsConfig(
            val webhook_url: String = "",
            val cooldown: Int = 15,
        )

        data class UpdatesConfig(
            val enabled: Boolean = true,
            val repo_name: String = "dreamdisplays",
            val repo_owner: String = "arsmotorin",
        )

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
        )
    }

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

        data class StorageConfig(
            val type: String = "SQLITE",
            val host: String = "localhost",
            val port: String = "3306",
            val database: String = "my_database",
            val password: String = "veryStrongPassword",
            val username: String = "username",
            val table_prefix: String = "",
        )
    }

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
        )
    }

    companion object {
        private val GSON = Gson()
        val LANGUAGE_FILES = listOf(
            "en.json", "es.json", "fr.json", "it.json", "pl.json",
            "ru.json", "uk.json", "de.json", "cs.json", "be.json", "he.json"
        )

        private val DEFAULT_CONFIG = """
# Dream Displays configuration
# Fabric server implementation
# Support: https://discord.gg/uwMMZ2KWk6

[language]
default_language = "en"

[display]
selection_material = "minecraft:diamond_axe"
base_material = "minecraft:black_concrete"
max_render_distance = 96.0
min_width = 1
min_height = 1
max_width = 32
max_height = 24
particles = true
particles_color = "#00FFFF"
mod_detection_enabled = true
updates = true

[reports]
webhook_url = ""
cooldown = 30

[storage]
type = "SQLITE"
host = "localhost"
port = "3306"
database = "database"
username = "username"
password = "password"
table_prefix = ""

[permissions]
create = "dreamdisplays.create"
video = "dreamdisplays.video"
info = "dreamdisplays.info"
help = "dreamdisplays.help"
list = "dreamdisplays.list"
stats = "dreamdisplays.stats"
premium = "dreamdisplays.premium"
delete = "dreamdisplays.delete"
reload = "dreamdisplays.reload"
updates = "dreamdisplays.updates"
toggle_others = "dreamdisplays.toggle.others"
""".trimIndent()
    }
}
