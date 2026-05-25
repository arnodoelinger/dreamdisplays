package com.dreamdisplays

import com.dreamdisplays.net.Packets
import com.dreamdisplays.server.Config
import com.dreamdisplays.server.listeners.PlayerListener
import com.dreamdisplays.server.listeners.ProtectionListener
import com.dreamdisplays.server.listeners.SelectionListener
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.StateManager
import com.dreamdisplays.server.managers.StorageManager
import com.dreamdisplays.server.meta.Updater
import com.dreamdisplays.server.utils.net.ServerPacketHandler
import com.dreamdisplays.server.registrar.CommandRegistrar
import com.github.zafarkhaja.semver.Version
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Different from `Paper` implementation.
 *
 * Main entrypoint for the `Dream Displays` server-side `Fabric` mod.
 *
 * `Fabric server` implements [ModInitializer] instead of extending plugin;
 * lifecycle hooks via [ServerLifecycleEvents].
 *
 * Runs on both integrated (singleplayer) and dedicated servers.
 *
 * `Fabric server` implementation.
 */
@Suppress("UNUSED")
class Server : ModInitializer {
    override fun onInitialize() {
        // Paper start
        logger.info("[Dream Displays] Initializing server-side mod...")

        configInstance = Config()

        // Fabric server start
        registerPayloadTypes()
        // Fabric server end

        ServerPacketHandler.registerReceivers()
        CommandRegistrar.register()
        PlayerListener.register()
        ProtectionListener.register()
        SelectionListener.register()

        // Fabric server start
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            serverInstance = server
            storageInstance = StorageManager(configInstance)
            logger.info("[Dream Displays] Server started. Storage connected.")
            startRepeatingTasks(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            logger.info("[Dream Displays] Server stopping. Saving displays...")
            storageInstance?.onDisable()
        }
        // Fabric server end

        logger.info("[Dream Displays] Server-side initialization complete.")
    }

    private fun registerPayloadTypes() {
        // Fabric server start
        // PayloadTypeRegistry replaces messenger.registerIncomingPluginChannel /
        // registerOutgoingPluginChannel. The Client also registers these; runCatching
        // silently ignores the "already registered" error when both entrypoints run (integrated server).
        with(PayloadTypeRegistry.clientboundPlay()) {
            runCatching { register(Packets.Info.PACKET_ID, Packets.Info.PACKET_CODEC) }
            runCatching { register(Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC) }
            runCatching { register(Packets.Premium.PACKET_ID, Packets.Premium.PACKET_CODEC) }
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
        // Fabric server end
    }

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
                runCatching { Updater.checkForUpdates(settings.repoOwner, settings.repoName) }
                while (!server.isStopped) {
                    try { Thread.sleep(60L * 60L * 1000L) } catch (_: InterruptedException) { break }
                    runCatching { Updater.checkForUpdates(settings.repoOwner, settings.repoName) }
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

        private lateinit var configInstance: Config
        private var serverInstance: MinecraftServer? = null
        private var storageInstance: StorageManager? = null

        val config: Config
            get() = configInstance

        val server: MinecraftServer?
            get() = serverInstance

        val storage: StorageManager?
            get() = storageInstance
    }
    // Paper end
}
