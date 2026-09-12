package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StorageManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.metrics.TelemetryMetrics
import com.dreamdisplays.platform.server.playback.*
import com.dreamdisplays.platform.server.proxy.ProxyBridge
import com.dreamdisplays.platform.server.registrar.ChannelRegistrar
import com.dreamdisplays.platform.server.registrar.CommandRegistrar
import com.dreamdisplays.platform.server.registrar.ListenerRegistrar
import com.dreamdisplays.platform.server.storage.StorageBackend
import io.github.arnodoelinger.platformweaver.PaperOnly
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bstats.bukkit.Metrics
import org.bukkit.plugin.java.JavaPlugin
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory

/**
 * Entry point of `Dream Displays` server-side plugin. `@PaperOnly` annotation is used when code relies on `Paper`-specific
 * API not present on vanilla loaders.
 */
@PaperOnly
@NullMarked
class PaperServer : JavaPlugin() {
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
        ServerCoroutines.warmUp()
        TelemetryMetrics.warmUp()

        Scheduler.init(this)

        val s = Companion.config.storage
        val backend = StorageBackend.fromConfig(s.type)
        storage = StorageManager(
            backend = backend, dataDir = dataFolder, tablePrefix = s.tablePrefix,
            host = s.host, port = s.port, database = s.database,
            username = s.username, password = s.password, useSSL = s.useSSL,
        )
        storage.createSchema()
        DisplayManager.register(storage.loadAllPaperDisplays())

        WatchPartyManager.init(PaperPlaybackTransport)
        TimelineManager.init(PaperPlaybackTransport)
        FullscreenBroadcastManager.init(PaperPlaybackTransport)
        FullscreenBroadcastManager.restore()
        PipPinManager.init(PaperPlaybackTransport)
        PipPinManager.restore()
        ScheduledPlaybackManager.init(PaperPlaybackTransport)
        ScheduledPlaybackManager.restoreOnStartup()

        ProxyBridge.init(Companion.config.proxy.enabled, Companion.config.proxy.clock_sync_interval)

        ListenerRegistrar.registerListeners(this)
        ChannelRegistrar.registerChannels(this)
        runRepeatingTasks()

        TelemetryMetrics.register(this, Metrics(this, 26488))
    }

    /** Calls the Paper-only scheduler registrar without requiring its symbol in Fabric compilation. */
    private fun runRepeatingTasks() {
        val registrarClass = Class.forName("com.dreamdisplays.platform.server.registrar.SchedulerRegistrar")
        val registrar = registrarClass.getField("INSTANCE").get(null)
        registrarClass.getMethod("runRepeatingTasks", PaperServer::class.java).invoke(registrar, this)
    }

    /**
     * Persists state and tears down resources. Safe to call from a reload.
     *
     * Each step is isolated.
     */
    fun doDisable() {
        if (!::storage.isInitialized) return
        disableStep("save displays") { DisplayManager.save { data: PaperDisplayData -> storage.saveDisplay(data) } }
        disableStep("stop background IO") { ServerCoroutines.shutdown() }
        disableStep("close storage") { storage.disconnect() }
    }

    /** Runs one teardown [step], logging and swallowing whatever it throws. */
    private fun disableStep(what: String, step: () -> Unit) {
        runCatching(step).onFailure { Companion.logger.error("Failed to $what while disabling.", it) }
    }

    companion object {
        /** Logger. */
        val logger = LoggerFactory.getLogger(javaClass)

        /** Mod config (`Fabric` server included). */
        lateinit var config: Config

        /** Returns the singleton plugin instance. */
        fun getInstance(): PaperServer = instance

        /** Forces `Bukkit` to disable this plugin (used when fatal startup errors occur). */
        fun disablePlugin() {
            instance.server.pluginManager.disablePlugin(instance)
        }

        /** The plugin instance. */
        private lateinit var instance: PaperServer
    }
}
