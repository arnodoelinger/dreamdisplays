package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.server.storage.StorageBackend
import com.dreamdisplays.util.asJsonObjectOrNull
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.toPlainJsonValue
import org.tomlj.Toml
import org.tomlj.TomlTable
import net.minecraft.server.level.ServerPlayer
import io.github.arnodoelinger.platformweaver.*
import org.bukkit.Material
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private fun loadLanguageMessages(file: File): Map<String, Any> {
    val root = DreamJson.compact.parseToJsonElement(file.readText()).asJsonObjectOrNull()
        ?: error("Language file root must be a JSON object.")
    return root.mapNotNull { (key, value) ->
        value.toPlainJsonValue()?.let { key to it }
    }.toMap()
}

/**
 * Manages the configuration of the plugin.
 */
@PaperOnly
@NullMarked
class Config(private val plugin: Main) {
    /** The plugin's configuration file. */
    private val configFile = File(plugin.dataFolder, "config.toml")

    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/Config")

    /** Config language. */
    lateinit var language: LanguageSection; private set

    /** Config settings. */
    lateinit var settings: SettingsSection; private set

    /** Config storage type. */
    lateinit var storage: StorageSection; private set

    /** Config permissions. */
    lateinit var permissions: PermissionsSection; private set

    /** Config messages. */
    val messages = mutableMapOf<String, Any>()

    /** Config languages. */
    val languages = mutableMapOf<String, Map<String, Any>>()

    /** Initializes the plugin's configuration. */
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
        val t: TomlTable? = runCatching { Toml.parse(configFile.toPath()) }
            .onFailure { logger.error("Failed to parse config.toml", it) }
            .getOrNull()

        language = LanguageSection(
            default_language = t?.getString("language.default_language") ?: "en"
        )
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
                selection_material = t?.getString("display.selection_material") ?: "DIAMOND_AXE",
                base_material = t?.getString("display.base_material") ?: "BLACK_CONCRETE",
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
            )
        ).apply { initMaterials() }
        storage = StorageSection(
            storage = StorageSection.StorageConfig(
                type = t?.getString("storage.type") ?: StorageBackend.SQLITE.configToken,
                host = t?.getString("storage.host") ?: "localhost",
                port = t?.getString("storage.port") ?: "3306",
                database = t?.getString("storage.database") ?: "my_database",
                password = t?.getString("storage.password") ?: "veryStrongPassword",
                username = t?.getString("storage.username") ?: "username",
                table_prefix = t?.getString("storage.table_prefix") ?: ""
            )
        )
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
                create_bypass = t?.getString("permissions.create_bypass") ?: "dreamdisplays.create.bypass"
            )
        )
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
                    languages[langCode] = loadLanguageMessages(langFile)
                }.onFailure {
                    logger.error("Error loading language file: $fileName.", it)
                }
            }
        }
        messages.clear()
        messages.putAll(languages["en"] ?: emptyMap())
    }

    /** Language section of the config. */
    data class LanguageSection(
        val default_language: String = "en",
    )

    /** Settings section of the config. */
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
        /** Volume in [0, 1] ready for the wire; config stores 0–100 percent. */
        val defaultVolume get() = display.default_volume / 200f

        /** Resolves [Material] names from the TOML, defaulting to diamond axe and black concrete. */
        internal fun initMaterials() {
            selectionMaterial = Material.matchMaterial(display.selection_material) ?: Material.DIAMOND_AXE
            baseMaterial = Material.matchMaterial(display.base_material) ?: Material.BLACK_CONCRETE
        }

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

        /** Display section. */
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
            val default_volume: Int = 50,
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

        data class StorageConfig(
            val type: String = StorageBackend.SQLITE.configToken,
            val host: String = "localhost",
            val port: String = "3306",
            val database: String = "my_database",
            val password: String = "veryStrongPassword",
            val username: String = "username",
            val table_prefix: String = "",
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
        /** Language files to extract from the plugin's JAR. */
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
 * Vanilla server-side configuration.
 */
class VanillaConfig(private val configDir: File) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/ServerConfig")

    private val configFile = File(configDir, "config.toml")

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
            val resource = VanillaConfig::class.java.classLoader
                .getResourceAsStream("assets/dreamdisplays/lang/server/config.toml")
                ?: VanillaConfig::class.java.classLoader
                    .getResourceAsStream("config.toml")
            if (resource != null) {
                resource.use { Files.copy(it, configFile.toPath()) }
            } else {
                configFile.writeText(DEFAULT_CONFIG)
            }
        }
    }

    private fun load() {
        val t: TomlTable? = runCatching { Toml.parse(configFile.toPath()) }
            .onFailure { logger.error("Failed to parse config.toml", it) }
            .getOrNull()

        language = LanguageSection(
            default_language = t?.getString("language.default_language") ?: "en"
        )
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
            )
        )
        storage = StorageSection(
            storage = StorageSection.StorageConfig(
                type = t?.getString("storage.type") ?: StorageBackend.SQLITE.configToken,
                host = t?.getString("storage.host") ?: "localhost",
                port = t?.getString("storage.port") ?: "3306",
                database = t?.getString("storage.database") ?: "my_database",
                password = t?.getString("storage.password") ?: "veryStrongPassword",
                username = t?.getString("storage.username") ?: "username",
                table_prefix = t?.getString("storage.table_prefix") ?: ""
            )
        )
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
                create_bypass = t?.getString("permissions.create_bypass") ?: "dreamdisplays.create.bypass"
            )
        )
    }

    fun reload() {
        load()
        extractLangFiles(overwrite = false)
        loadMessages()
    }

    private fun extractLangFiles(overwrite: Boolean) {
        val langFolder = File(configDir, "lang")
        if (!langFolder.exists() && !langFolder.mkdirs()) {
            logger.warn("Could not create lang folder")
            return
        }

        LANGUAGE_FILES.forEach { fileName ->
            runCatching {
                val resource = VanillaConfig::class.java.classLoader
                    .getResourceAsStream("assets/dreamdisplays/lang/server/$fileName")
                    ?: VanillaConfig::class.java.classLoader
                        .getResourceAsStream("assets/dreamdisplays/lang/$fileName")
                resource?.use { input ->
                    val target = File(langFolder, fileName)
                    if (overwrite || !target.exists()) {
                        Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }.onFailure {
                logger.warn("Could not extract $fileName: ${it.message}")
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
                    languages[langCode] = loadLanguageMessages(langFile)
                }.onFailure {
                    logger.error("Error loading language file: $fileName", it)
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
        /** Volume in [0, 1] ready for the wire; config stores 0–100 percent. */
        val defaultVolume get() = display.default_volume / 200f

        data class ReportsConfig(
            val webhook_url: String = "",
            val cooldown: Int = 15,
        )

        data class UpdatesConfig(
            val enabled: Boolean = true,
            val repo_name: String = "dreamdisplays",
            val repo_owner: String = "arnodoelinger",
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
            val default_volume: Int = 50,
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
            val type: String = StorageBackend.SQLITE.configToken,
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
        val local get() = permissions.local
        val synced get() = permissions.synced
        val broadcast get() = permissions.broadcast
        val watchparty get() = permissions.watchparty
        val lock get() = permissions.lock
        val deleteOthers get() = permissions.delete_others
        val createBypass get() = permissions.create_bypass

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
        )
    }

    companion object {
        val LANGUAGE_FILES = listOf(
            "en.json", "es.json", "fr.json", "it.json", "pl.json",
            "ru.json", "uk.json", "de.json", "cs.json", "be.json", "he.json"
        )

        private val DEFAULT_CONFIG = """
# Dream Displays configuration
# Mod server implementation
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
default_volume = 50

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
local = "dreamdisplays.local"
synced = "dreamdisplays.synced"
broadcast = "dreamdisplays.broadcast"
watchparty = "dreamdisplays.watchparty"
lock = "dreamdisplays.lock"
list = "dreamdisplays.list"
stats = "dreamdisplays.stats"
premium = "dreamdisplays.premium"
delete = "dreamdisplays.delete"
delete_others = "dreamdisplays.delete.others"
reload = "dreamdisplays.reload"
updates = "dreamdisplays.updates"
toggle_others = "dreamdisplays.toggle.others"
create_bypass = "dreamdisplays.create.bypass"
""".trimIndent()
    }
}
