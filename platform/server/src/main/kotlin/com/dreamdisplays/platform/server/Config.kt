package com.dreamdisplays.platform.server

import org.tomlj.Toml
import org.tomlj.TomlTable
import io.github.arnodoelinger.platformweaver.*
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

/**
 * Manages the configuration of the plugin. Parsing and language-loading logic live in
 * [ServerConfigModel]; this class only supplies Paper's resource lookup, data directory,
 * and `Material` resolution.
 */
@PaperOnly
@NullMarked
class Config(private val plugin: PaperServer) {
    /** The plugin's configuration file. */
    private val configFile = File(plugin.dataFolder, "config.toml")

    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/Config")

    private val langStore = LanguageStore(
        dataDir = plugin.dataFolder,
        resourceLookup = { plugin.getResource(it) },
        warn = { plugin.logger.warning(it) },
    )

    /** Config language. */
    lateinit var language: LanguageSection; private set

    /** Config settings. */
    lateinit var settings: SettingsSection; private set

    /** Config storage type. */
    lateinit var storage: StorageSection; private set

    /** Config permissions. */
    lateinit var permissions: PermissionsSection; private set

    /** Config messages. */
    val messages get() = langStore.messages

    /** Config languages. */
    val languages get() = langStore.languages

    /** Initializes the plugin's configuration. */
    init {
        createDefaultConfig()
        langStore.extractLangFiles(true)
        load()
        langStore.loadMessages(logger)
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
            .onFailure { logger.error("Failed to parse config.toml.", it) }
            .getOrNull()
        val parsed = parseServerConfig(t)
        language = parsed.language
        settings = parsed.settings
        storage = parsed.storage
        permissions = parsed.permissions
    }

    /** Re-reads `config.toml`, re-extracts language files and refreshes the in-memory message map. */
    fun reload() {
        load()
        langStore.extractLangFiles(false)
        langStore.loadMessages(logger)
    }

    /**
     * Resolves [key] in [player]'s locale, then in the configured default, then in the English fallback.
     * Returns null when no translation exists in any language.
     */
    @Suppress("DEPRECATION")
    fun getMessageForPlayer(player: Player?, key: String): Any? =
        langStore.getMessage(player?.locale ?: "en_us", language.default_language, key)
}
