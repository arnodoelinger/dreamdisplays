package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.platform.client.Config
import com.dreamdisplays.platform.client.Focuser
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.core.ClientApplication
import com.dreamdisplays.platform.client.core.DefaultClientApplication
import com.dreamdisplays.platform.client.core.DefaultClientContext
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.getOrNull
import com.dreamdisplays.platform.client.core.register
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.api.Platform
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.storage.DisplayStorage
import com.dreamdisplays.player.nativebridge.NativeMedia
import com.dreamdisplays.player.process.FFmpegBinary
import com.dreamdisplays.ytdlp.FormatDiskCache
import com.dreamdisplays.ytdlp.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles client bootstrapping and background maintenance coroutines.
 */
object ClientStartupManager {
    private val logger = LoggerFactory.getLogger("DreamDisplays/ClientStartupManager")
    val config: Config = Config(File("./config/${Initializer.MOD_ID}"))

    private val qualityRefreshInterval = 2500.milliseconds

    /**
     * Owns every background maintenance coroutine. Runs on [Dispatchers.IO] (a pool of daemon
     * threads), so a hung refresh / sweep can never block JVM shutdown; [stop] still cancels it
     * cleanly on a normal exit. [SupervisorJob] keeps the quality-refresh loop and the cache sweep
     * independent, so one failing doesn't cancel the other.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        config.reload()
        DisplayStorage.load()

        // Wire the contract-typed service graph (media resolver chain, ...) before any
        // background prewarm touches it.
        DreamServices.bootstrap()

        // If the loader entrypoint registered a Platform, host the module system on top of it
        DreamServices.registry.getOrNull<Platform>()?.let { platform ->
            val application = DefaultClientApplication(DefaultClientContext(platform))
            DreamServices.registry.register<ClientApplication>(application)
            application.start()
        }

        YtDlp.prewarmAsync()
        FFmpegBinary.prewarmAsync()
        NativeMedia.prewarmAsync()

        scope.launch {
            runCatching { FormatDiskCache.sweepExpired() }
                .onFailure { e -> logger.warn("Cache sweep failed.", e) }
        }
        Focuser().start()
        scope.launch {
            while (isActive) {
                runCatching { DisplayRegistry.getScreens().forEach(DisplayScreen::reloadQuality) }
                    .onFailure { e -> logger.warn("Quality refresh failed.", e) }
                delay(qualityRefreshInterval)
            }
        }
    }

    /** Cancels the background refresh / sweep coroutines. Safe to call multiple times. */
    fun stop() {
        scope.cancel()
    }
}
