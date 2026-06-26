package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.client.net.Packets
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.platform.server.datatypes.PaperDisplayData
import com.dreamdisplays.platform.server.listeners.FabricPlayerListener
import com.dreamdisplays.platform.server.listeners.FabricProtectionListener
import com.dreamdisplays.platform.server.listeners.FabricSelectionListener
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.managers.StorageManager
import com.dreamdisplays.platform.server.meta.FabricUpdater
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.metrics.TelemetryMetrics
import com.dreamdisplays.platform.server.playback.FabricPlaybackTransport
import com.dreamdisplays.platform.server.playback.PaperPlaybackTransport
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.registrar.ChannelRegistrar
import com.dreamdisplays.platform.server.registrar.CommandRegistrar
import com.dreamdisplays.platform.server.registrar.FabricCommandRegistrar
import com.dreamdisplays.platform.server.registrar.ListenerRegistrar
import com.dreamdisplays.platform.server.registrar.SchedulerRegistrar
import com.dreamdisplays.platform.server.storage.StorageBackend
import com.dreamdisplays.platform.server.utils.net.FabricV2Networking
import com.dreamdisplays.platform.server.utils.net.ServerPacketHandler
import io.github.arsmotorin.ofrat.*
import org.semver4j.Semver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.bstats.bukkit.Metrics
import org.bukkit.plugin.java.JavaPlugin
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Entry point of `Dream Displays` server-side plugin.
 *
 * `@PaperOnly` annotation is used when code relies on `Paper` APIs or server-side logic, and will not be loaded on
 * `Fabric` servers. The `Server` class is annotated with `@FabricOnly` to indicate that it is only used on
 * `Fabric` servers.
 *
 * `@FabricOnly` is used to indicate that the class (or other thing) is only used on `Fabric` servers.
 *
 * These annotations are used to prevent code duplication and ensure that the plugin is only loaded
 * on the correct platform.
 *
 * @see <a href="https://github.com/arsmotorin/OFRAT">OFRAT</a>
 */
@PaperOnly
@NullMarked
class Main : JavaPlugin() {
    /** Storage manager for persistent data. */
    lateinit var storage: StorageManager

    /** Captures the plugin instance, loads config, and registers `Brigadier` commands before any worlds load. */
    override fun onLoad() {
        instance = this
        Companion.config = Config(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(CommandRegistrar.buildDisplayCommand(), "Main Dream Displays command")
        }
    }

    /** Standard `Bukkit` hook, delegates to [doEnable] so reload commands can reuse the logic. */
    override fun onEnable() {
        doEnable()
    }

    /** Disables the plugin, disconnecting from the database and tearing down resources. */
    override fun onDisable() {
        doDisable()
    }

    /** Initializes scheduler, storage, listeners, channels, and metrics. Safe to call from a reload. */
    fun doEnable() {
        @Suppress("DEPRECATION")
        logger.info("Enabling Dream Displays ${description.version}...")

        Scheduler.init(this)

        val s = Companion.config.storage
        val backend = StorageBackend.fromConfig(s.type)
        storage = StorageManager(
            backend = backend, dataDir = dataFolder, tablePrefix = s.tablePrefix,
            host = s.host, port = s.port, database = s.database,
            username = s.username, password = s.password,
        )
        storage.createSchema()
        DisplayManager.register(storage.loadAllPaperDisplays())

        WatchPartyManager.init(PaperPlaybackTransport)
        TimelineManager.init(PaperPlaybackTransport)

        ListenerRegistrar.registerListeners(this)
        ChannelRegistrar.registerChannels(this)
        SchedulerRegistrar.runRepeatingTasks(this)

        TelemetryMetrics.register(this, Metrics(this, 26488))
    }

    /** Persists state and tears down resources. Safe to call from a reload. */
    fun doDisable() {
        logger.info("Disabling Dream Displays ${pluginMeta.version}...")
        if (::storage.isInitialized) {
            DisplayManager.save { data: PaperDisplayData -> storage.saveDisplay(data) }
            ServerCoroutines.shutdown()
            storage.disconnect()
        }
    }

    companion object {
        /** Logger. */
        val logger = LoggerFactory.getLogger("DreamDisplays/Plugin")

        /** Mod config (`Fabric` server included). */
        lateinit var config: Config

        /** Mod version */
        var modVersion: Semver? = null

        /** Latest plugin version string from GitHub (populated by updater). */
        var pluginLatestVersion: String? = null

        /** Returns the singleton plugin instance. */
        fun getInstance(): Main = instance

        /** Forces `Bukkit` to disable this plugin (used when fatal startup errors occur). */
        fun disablePlugin() {
            instance.server.pluginManager.disablePlugin(instance)
        }

        /** The plugin instance. */
        private lateinit var instance: Main
    }
}

/**
 * `Fabric`-specific implementation of [Main].
 */
@Suppress("UNUSED")
@FabricOnly
class Server : ModInitializer {
    /**
     * Initializes the server-side mod. It registers all necessary event listeners, packet handlers, and commands.
     * Also sets up repeating tasks for display updates and update checking.
     *
     * This method is called by the `Fabric` loader when the mod is loaded.
     */
    override fun onInitialize() {
        logger.info("Initializing server-side mod...")

        configInstance = FabricConfig()

        registerPayloadTypes()

        ServerPacketHandler.registerReceivers()
        FabricV2Networking.registerReceivers()
        FabricCommandRegistrar.register()
        FabricPlayerListener.register()
        FabricProtectionListener.register()
        FabricSelectionListener.register()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            serverInstance = server
            val s = configInstance.storage
            val dataDir = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).parent
                .resolve("dreamdisplays").toFile().also { it.mkdirs() }
            val backend = StorageBackend.fromConfig(s.type)
            if (backend == StorageBackend.SQLITE) migrateGlobalDb(dataDir)
            storageInstance = StorageManager(
                backend = backend, dataDir = dataDir,
                tablePrefix = s.tablePrefix,
                host = s.host, port = s.port, database = s.database,
                username = s.username, password = s.password,
            )
            storageInstance!!.createSchema()
            DisplayManager.register(storageInstance!!.loadAllFabricDisplays())
            FabricPlaybackTransport.bind(server)
            WatchPartyManager.init(FabricPlaybackTransport)
            TimelineManager.init(FabricPlaybackTransport)
            logger.info("Server started. Storage connected.")
            startRepeatingTasks(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            logger.info("Server stopping. Saving displays...")
            DisplayManager.save { storageInstance?.saveDisplay(it) }
            ServerCoroutines.shutdown()
            storageInstance?.disconnect()
        }

        logger.info("Server-side initialization complete.")
    }

    /** Registers all custom payload types for clientbound and serverbound play channels. */
    private fun registerPayloadTypes() {
        try {
            val clientbound = payloadRegistry("clientboundPlay", "playS2C")
            registerPayload(clientbound, V2Payload.TYPE, V2Payload.CODEC)
            registerPayload(clientbound, Packets.Info.PACKET_ID, Packets.Info.PACKET_CODEC)
            registerPayload(clientbound, Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC)
            registerPayload(clientbound, Packets.Premium.PACKET_ID, Packets.Premium.PACKET_CODEC)
            registerPayload(clientbound, Packets.IsAdmin.PACKET_ID, Packets.IsAdmin.PACKET_CODEC)
            registerPayload(clientbound, Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC)
            registerPayload(clientbound, Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC)
            registerPayload(clientbound, Packets.ReportEnabled.PACKET_ID, Packets.ReportEnabled.PACKET_CODEC)
            registerPayload(clientbound, Packets.ClearCache.PACKET_ID, Packets.ClearCache.PACKET_CODEC)

            val serverbound = payloadRegistry("serverboundPlay", "playC2S")
            registerPayload(serverbound, V2Payload.TYPE, V2Payload.CODEC)
            registerPayload(serverbound, Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC)
            registerPayload(serverbound, Packets.RequestSync.PACKET_ID, Packets.RequestSync.PACKET_CODEC)
            registerPayload(serverbound, Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC)
            registerPayload(serverbound, Packets.Report.PACKET_ID, Packets.Report.PACKET_CODEC)
            registerPayload(serverbound, Packets.Version.PACKET_ID, Packets.Version.PACKET_CODEC)
            registerPayload(serverbound, Packets.SetVideo.PACKET_ID, Packets.SetVideo.PACKET_CODEC)
            registerPayload(serverbound, Packets.SetLocked.PACKET_ID, Packets.SetLocked.PACKET_CODEC)
            registerPayload(serverbound, Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC)
        } catch (e: Exception) {
            logger.error("Failed to register payload types.", e)
            throw e
        }
    }

    private fun payloadRegistry(vararg methodNames: String): Any {
        val type = PayloadTypeRegistry::class.java
        val method = methodNames.firstNotNullOfOrNull { name ->
            runCatching { type.getMethod(name) }.getOrNull()
        } ?: error("No compatible Fabric payload registry method found: ${methodNames.joinToString()}.")
        return method.invoke(null)
    }

    private fun registerPayload(registry: Any, packetId: Any, packetCodec: Any) {
        val register = registry.javaClass.methods.first {
            it.name == "register" && it.parameterCount == 2
        }
        register.invoke(registry, packetId, packetCodec)
    }

    /** Starts repeating coroutines for display updates and update checking on [ServerCoroutines.io]. */
    private fun startRepeatingTasks(server: MinecraftServer) {
        val settings = configInstance.settings

        ServerCoroutines.io.launch {
            while (!server.isStopped) {
                delay(1000L.milliseconds)
                runCatching {
                    server.execute {
                        DisplayManager.updateAllDisplays(server)
                        StateManager.tickBroadcast(server)
                        TimelineManager.tick()
                        WatchPartyManager.tick()
                    }
                }
            }
        }

        if (settings.updatesEnabled) {
            ServerCoroutines.io.launch {
                delay(1000L.milliseconds)
                runCatching { FabricUpdater.checkForUpdates(settings.repoOwner, settings.repoName) }
                while (!server.isStopped) {
                    delay((60L * 60L * 1000L).milliseconds)
                    runCatching { FabricUpdater.checkForUpdates(settings.repoOwner, settings.repoName) }
                }
            }
        }
    }

    companion object {
        /** Logger. */
        val logger = LoggerFactory.getLogger("DreamDisplays")

        /** The mod version string, resolved from the `Fabric` mod container. */
        val serverVersion: String? by lazy {
            runCatching {
                FabricLoader.getInstance()
                    .getModContainer("dreamdisplays")
                    .orElse(null)
                    ?.metadata
                    ?.version
                    ?.friendlyString
            }.getOrNull()
        }

        /** Latest mod version from GitHub (populated by updater). */
        @Volatile
        var modLatestVersion: Semver? = null

        /** Latest plugin version string from GitHub (populated by updater). */
        @Volatile
        var pluginLatestVersion: String? = null

        private lateinit var configInstance: FabricConfig
        private var serverInstance: MinecraftServer? = null
        private var storageInstance: StorageManager? = null

        val config: FabricConfig; get() = configInstance
        val server: MinecraftServer?; get() = serverInstance
        val storage: StorageManager?; get() = storageInstance

        /** Copies the pre-1.8.1 global `SQLite DB` into [worldDataDir] on first startup for this world. */
        private fun migrateGlobalDb(worldDataDir: File) {
            val oldDb = FabricLoader.getInstance().configDir
                .resolve("dreamdisplays").resolve("dreamdisplays.db").toFile()
            val newDb = File(worldDataDir, "dreamdisplays.db")
            if (!oldDb.exists() || newDb.exists()) return
            runCatching { oldDb.copyTo(newDb) }
                .onSuccess {
                    logger.info(
                        "Migrated displays from legacy global DB to per-world DB at ${newDb.absolutePath}. " +
                                "The old file at ${oldDb.absolutePath} can be deleted once all worlds have been started at least once."
                    )
                }
                .onFailure { logger.error("Failed to migrate global DB to ${newDb.absolutePath}.", it) }
        }
    }
}
