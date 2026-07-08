package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.managers.StorageManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.meta.Updater
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.VanillaPlaybackTransport
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.storage.StorageBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared `Fabric` / `NeoForge` server-lifecycle bootstrap: storage bring-up, display registration,
 * playback transport bind, and the repeating display-update / update-check coroutines. `Server`
 * (see `FabricServer.kt`) and `NeoForgeServer` (see `NeoForgeServerMod.kt`) only adapt their
 * loader's lifecycle-event API and hand off here.
 */
object VanillaBootstrap {
    /** Connects storage, loads displays, binds playback, and starts the repeating tasks. */
    fun onServerStarted(server: MinecraftServer, dataDir: File) {
        val s = VanillaServerState.config.storage
        val storage = StorageManager(
            backend = StorageBackend.fromConfig(s.type), dataDir = dataDir,
            tablePrefix = s.tablePrefix,
            host = s.host, port = s.port, database = s.database,
            username = s.username, password = s.password, useSSL = s.useSSL,
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
