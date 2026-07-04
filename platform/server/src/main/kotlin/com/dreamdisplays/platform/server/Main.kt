package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.net.Packets
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.platform.server.datatypes.PaperDisplayData
import com.dreamdisplays.platform.server.listeners.FabricPlayerListener
import com.dreamdisplays.platform.server.listeners.FabricProtectionListener
import com.dreamdisplays.platform.server.listeners.FabricSelectionListener
import com.dreamdisplays.platform.server.listeners.NeoForgePlayerListener
import com.dreamdisplays.platform.server.listeners.NeoForgeProtectionListener
import com.dreamdisplays.platform.server.listeners.NeoForgeSelectionListener
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.managers.StorageManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.meta.Updater
import com.dreamdisplays.platform.server.metrics.TelemetryMetrics
import com.dreamdisplays.platform.server.playback.PaperPlaybackTransport
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.VanillaPlaybackTransport
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.registrar.ChannelRegistrar
import com.dreamdisplays.platform.server.registrar.CommandRegistrar
import com.dreamdisplays.platform.server.registrar.FabricCommandRegistrar
import com.dreamdisplays.platform.server.registrar.ListenerRegistrar
import com.dreamdisplays.platform.server.registrar.NeoForgeCommandRegistrar
import com.dreamdisplays.platform.server.storage.StorageBackend
import com.dreamdisplays.platform.server.utils.net.FabricNetworkingAdapter
import com.dreamdisplays.platform.server.utils.net.FabricV2Networking
import com.dreamdisplays.platform.server.utils.net.NeoForgeNetworkingAdapter
import com.dreamdisplays.platform.server.utils.net.NeoForgeV2Networking
import com.dreamdisplays.platform.server.utils.net.VanillaNetworking
import com.dreamdisplays.platform.server.utils.net.VanillaServerPacketHandler
import io.github.arnodoelinger.platformweaver.*
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
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
 * @see <a href="https://github.com/arnodoelinger/PlatformWeaver">Platform Weaver</a>
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
        runRepeatingTasks()

        TelemetryMetrics.register(this, Metrics(this, 26488))
    }

    /** Calls the Paper-only scheduler registrar without requiring its symbol in Fabric compilation. */
    private fun runRepeatingTasks() {
        val registrarClass = Class.forName("com.dreamdisplays.platform.server.registrar.SchedulerRegistrar")
        val registrar = registrarClass.getField("INSTANCE").get(null)
        registrarClass.getMethod("runRepeatingTasks", Main::class.java).invoke(registrar, this)
    }

    /** Persists state and tears down resources. Safe to call from a reload. */
    fun doDisable() {
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

        configInstance = VanillaConfig(FabricLoader.getInstance().configDir.resolve("dreamdisplays").toFile())
        VanillaServerState.config = configInstance
        VanillaServerState.serverVersion = serverVersion
        VanillaNetworking.adapter = FabricNetworkingAdapter

        registerPayloadTypes()

        VanillaServerPacketHandler.registerReceivers()
        FabricV2Networking.registerReceivers()
        FabricCommandRegistrar.register()
        FabricPlayerListener.register()
        FabricProtectionListener.register()
        FabricSelectionListener.register()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            VanillaServerState.server = server
            val dataDir = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).parent
                .resolve("dreamdisplays").toFile().also { it.mkdirs() }
            if (StorageBackend.fromConfig(configInstance.storage.type) == StorageBackend.SQLITE) {
                migrateGlobalDb(dataDir)
            }
            VanillaBootstrap.onServerStarted(server, dataDir)
            logger.info("Server started. Storage connected.")
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            logger.info("Server stopping. Saving displays...")
            VanillaBootstrap.onServerStopping()
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

        private lateinit var configInstance: VanillaConfig

        val config: VanillaConfig; get() = configInstance
        val server: MinecraftServer?; get() = VanillaServerState.server
        val storage: StorageManager?; get() = VanillaServerState.storage

        /** Copies the pre-1.8.1 global `SQLite DB` into [worldDataDir] on first startup for this world. */
        @Deprecated("Will be removed in 1.9.0")
        // TODO: remove
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

/**
 * `NeoForge`-specific implementation of [Main].
 */
@Suppress("UNUSED")
@NeoForgeOnly
@Mod(Initializer.MOD_ID)
class NeoForgeServer(modEventBus: IEventBus) {
    init {
        logger.info("Initializing server-side mod...")
        configInstance = VanillaConfig(FMLPaths.CONFIGDIR.get().resolve("dreamdisplays").toFile())
        VanillaServerState.config = configInstance
        VanillaServerState.serverVersion = serverVersion
        VanillaNetworking.adapter = NeoForgeNetworkingAdapter

        modEventBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.register(this)
        NeoForge.EVENT_BUS.register(NeoForgePlayerListener)
        NeoForge.EVENT_BUS.register(NeoForgeProtectionListener)
        NeoForge.EVENT_BUS.register(NeoForgeSelectionListener)
        NeoForge.EVENT_BUS.addListener(NeoForgeCommandRegistrar::register)

        logger.info("Server-side initialization complete.")
    }

    /** Registers all custom payload types for clientbound and serverbound play channels. */
    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(Initializer.MOD_ID).optional().versioned("1")
        NeoForgeV2Networking.registerReceivers(registrar)
        VanillaServerPacketHandler.registerReceivers(registrar)
    }

    /** Storage bring-up, display registration, and repeating tasks; covers dedicated + integrated servers alike. */
    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        val server = event.server
        VanillaServerState.server = server
        val dataDir = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).parent
            .resolve("dreamdisplays").toFile().also { it.mkdirs() }
        VanillaBootstrap.onServerStarted(server, dataDir)
        logger.info("Server started. Storage connected.")
    }

    /** Persists state and tears down resources. */
    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        logger.info("Server stopping. Saving displays...")
        VanillaBootstrap.onServerStopping()
    }

    companion object {
        /** Logger. */
        val logger = LoggerFactory.getLogger("DreamDisplays")

        /** The mod version string, read from the bundled, Gradle-templated `version.txt` resource. */
        val serverVersion: String? by lazy {
            runCatching {
                NeoForgeServer::class.java.classLoader
                    .getResourceAsStream("assets/dreamdisplays/version.txt")
                    ?.use { it.readBytes().decodeToString().trim() }
            }.getOrNull()
        }

        private lateinit var configInstance: VanillaConfig

        val config: VanillaConfig; get() = configInstance
        val server: MinecraftServer?; get() = VanillaServerState.server
        val storage: StorageManager?; get() = VanillaServerState.storage
    }
}

/**
 * Shared `Fabric` / `NeoForge` server-lifecycle bootstrap: storage bring-up, display registration,
 * playback transport bind, and the repeating display-update / update-check coroutines. [Server]
 * and [NeoForgeServer] only adapt their loader's lifecycle-event API and hand off here.
 */
object VanillaBootstrap {
    /** Connects storage, loads displays, binds playback, and starts the repeating tasks. */
    fun onServerStarted(server: MinecraftServer, dataDir: File) {
        val s = VanillaServerState.config.storage
        val storage = StorageManager(
            backend = StorageBackend.fromConfig(s.type), dataDir = dataDir,
            tablePrefix = s.tablePrefix,
            host = s.host, port = s.port, database = s.database,
            username = s.username, password = s.password,
        )
        VanillaServerState.storage = storage
        storage.createSchema()
        DisplayManager.register(storage.loadAllVanillaDisplays())
        VanillaPlaybackTransport.bind(server)
        WatchPartyManager.init(VanillaPlaybackTransport)
        TimelineManager.init(VanillaPlaybackTransport)
        startRepeatingTasks(server)
    }

    /** Persists all displays and disconnects storage. */
    fun onServerStopping() {
        DisplayManager.save { VanillaServerState.storage?.saveDisplay(it) }
        ServerCoroutines.shutdown()
        VanillaServerState.storage?.disconnect()
    }

    /** Starts repeating coroutines for display updates and update checking on [ServerCoroutines.io]. */
    private fun startRepeatingTasks(server: MinecraftServer) {
        val settings = VanillaServerState.config.settings

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
                runCatching { Updater.checkForUpdates(settings.repoOwner, settings.repoName) }
                while (!server.isStopped) {
                    delay((60L * 60L * 1000L).milliseconds)
                    runCatching { Updater.checkForUpdates(settings.repoOwner, settings.repoName) }
                }
            }
        }
    }
}
