package com.dreamdisplays.server

import com.dreamdisplays.net.Packets
import com.dreamdisplays.server.listeners.FabricPlayerListener
import com.dreamdisplays.server.listeners.FabricProtectionListener
import com.dreamdisplays.server.listeners.FabricSelectionListener
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.StateManager
import com.dreamdisplays.server.managers.StorageManager
import com.dreamdisplays.server.meta.FabricUpdater
import com.dreamdisplays.server.meta.Scheduler
import com.dreamdisplays.server.registrar.ChannelRegistrar
import com.dreamdisplays.server.registrar.CommandRegistrar
import com.dreamdisplays.server.registrar.FabricCommandRegistrar
import com.dreamdisplays.server.registrar.ListenerRegistrar
import com.dreamdisplays.server.registrar.SchedulerRegistrar
import com.dreamdisplays.server.utils.net.ServerPacketHandler
import io.github.arsmotorin.ofrat.*
import com.github.zafarkhaja.semver.Version
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import me.inotsleep.utils.logging.LoggingManager
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.bstats.bukkit.Metrics
import org.bukkit.plugin.java.JavaPlugin
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory

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
@Suppress("UnstableApiUsage")
@PaperOnly @NullMarked class Main : JavaPlugin() {
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

    /** Standard `Bukkit` hook, delegates to [doDisable]. */
    override fun onDisable() {
        doDisable()
    }

    /** Initializes scheduler, storage, listeners, channels, and metrics. Safe to call from a reload. */
    fun doEnable() {
        @Suppress("DEPRECATION")
        LoggingManager.log("[Dream Displays] Enabling Dream Displays ${description.version}...")

        Scheduler.init(this)

        storage = StorageManager(this)

        ListenerRegistrar.registerListeners(this)
        ChannelRegistrar.registerChannels(this)
        SchedulerRegistrar.runRepeatingTasks(this)

        Metrics(this, 26488)
    }

    /** Persists state and tears down resources. Safe to call from a reload. */
    fun doDisable() {
        LoggingManager.log("[Dream Displays] Disabling Dream Displays ${pluginMeta.version}...")
        storage.onDisable()
    }

    companion object {
        lateinit var config: Config
        var modVersion: Version? = null
        var pluginLatestVersion: String? = null

        /** Returns the singleton plugin instance. */
        fun getInstance(): Main = instance

        /** Forces `Bukkit` to disable this plugin (used when fatal startup errors occur). */
        fun disablePlugin() {
            instance.server.pluginManager.disablePlugin(instance)
        }

        private lateinit var instance: Main
    }
}

/**
 * `Fabric`-specific implementation of [Main].
 */
@Suppress("UNUSED")
@FabricOnly class Server : ModInitializer {
    /**
     * Initializes the server-side mod. It registers all necessary event listeners, packet handlers, and commands.
     * Also sets up repeating tasks for display updates and update checking.
     *
     * This method is called by the `Fabric` loader when the mod is loaded.
     */
    override fun onInitialize() {
        logger.info("[Dream Displays] Initializing server-side mod...")

        configInstance = FabricConfig()

        registerPayloadTypes()

        ServerPacketHandler.registerReceivers()
        FabricCommandRegistrar.register()
        FabricPlayerListener.register()
        FabricProtectionListener.register()
        FabricSelectionListener.register()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            serverInstance = server
            storageInstance = StorageManager(configInstance)
            logger.info("[Dream Displays] Server started. Storage connected.")
            startRepeatingTasks(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            logger.info("[Dream Displays] Server stopping. Saving displays...")
            storageInstance?.onDisable(Unit)
        }

        logger.info("[Dream Displays] Server-side initialization complete.")
    }

    /** Registers all custom payload types for clientbound and serverbound play channels. */
    private fun registerPayloadTypes() {
        with(PayloadTypeRegistry.clientboundPlay()) {
            runCatching { register(Packets.Info.PACKET_ID, Packets.Info.PACKET_CODEC) }
            runCatching { register(Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC) }
            runCatching { register(Packets.Premium.PACKET_ID, Packets.Premium.PACKET_CODEC) }
            runCatching { register(Packets.IsAdmin.PACKET_ID, Packets.IsAdmin.PACKET_CODEC) }
            runCatching { register(Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC) }
            runCatching { register(Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC) }
            runCatching { register(Packets.ReportEnabled.PACKET_ID, Packets.ReportEnabled.PACKET_CODEC) }
            runCatching { register(Packets.ClearCache.PACKET_ID, Packets.ClearCache.PACKET_CODEC) }
        }

        with(PayloadTypeRegistry.serverboundPlay()) {
            runCatching { register(Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC) }
            runCatching { register(Packets.RequestSync.PACKET_ID, Packets.RequestSync.PACKET_CODEC) }
            runCatching { register(Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC) }
            runCatching { register(Packets.Report.PACKET_ID, Packets.Report.PACKET_CODEC) }
            runCatching { register(Packets.Version.PACKET_ID, Packets.Version.PACKET_CODEC) }
            runCatching { register(Packets.SetVideo.PACKET_ID, Packets.SetVideo.PACKET_CODEC) }
            runCatching { register(Packets.SetLocked.PACKET_ID, Packets.SetLocked.PACKET_CODEC) }
            runCatching { register(Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC) }
        }
    }

    /** Starts repeating tasks for display updates and update checking. */
    private fun startRepeatingTasks(server: MinecraftServer) {
        val settings = configInstance.settings

        Thread({
            while (!server.isStopped) {
                try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
                runCatching {
                    server.execute {
                        DisplayManager.updateAllDisplays(server)
                        StateManager.tickBroadcast(server)
                    }
                }
            }
        }, "dreamdisplays-display-updater").also { it.isDaemon = true }.start()

        if (settings.updatesEnabled) {
            Thread({
                try { Thread.sleep(1000L) } catch (_: InterruptedException) { return@Thread }
                runCatching { FabricUpdater.checkForUpdates(settings.repoOwner, settings.repoName) }
                while (!server.isStopped) {
                    try { Thread.sleep(60L * 60L * 1000L) } catch (_: InterruptedException) { break }
                    runCatching { FabricUpdater.checkForUpdates(settings.repoOwner, settings.repoName) }
                }
            }, "dreamdisplays-updater").also { it.isDaemon = true }.start()
        }
    }

    companion object {
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
        @Volatile var modLatestVersion: Version? = null

        /** Latest plugin version string from GitHub (populated by updater). */
        @Volatile var pluginLatestVersion: String? = null

        private lateinit var configInstance: FabricConfig
        private var serverInstance: MinecraftServer? = null
        private var storageInstance: StorageManager? = null

        val config: FabricConfig
            get() = configInstance

        val server: MinecraftServer?
            get() = serverInstance

        val storage: StorageManager?
            get() = storageInstance
    }
}
