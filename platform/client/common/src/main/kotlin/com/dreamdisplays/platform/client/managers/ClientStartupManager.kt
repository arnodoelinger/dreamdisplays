package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.runtime.DreamDisplaysModule
import com.dreamdisplays.platform.client.Config
import com.dreamdisplays.platform.client.Focuser
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.core.ClientApplication
import com.dreamdisplays.platform.client.core.DefaultClientApplication
import com.dreamdisplays.platform.client.core.DefaultClientContext
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.modules.ClientCapabilityModule
import com.dreamdisplays.platform.client.core.modules.ClientInputModule
import com.dreamdisplays.platform.client.core.modules.ClientOverlayModule
import com.dreamdisplays.platform.client.core.modules.ClientRenderModule
import com.dreamdisplays.platform.client.core.modules.ClientStorageModule
import com.dreamdisplays.platform.client.core.modules.CoreDisplayModule
import com.dreamdisplays.platform.client.core.modules.CorePlaybackModule
import com.dreamdisplays.platform.client.core.modules.MediaResolverModule
import com.dreamdisplays.api.runtime.register
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.api.platform.PlatformServices
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.source.ytdlp.FormatDiskCache
import com.dreamdisplays.media.source.ytdlp.YtDlp
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
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/ClientStartupManager")

    /** The client configuration, loaded from the mod's config directory. */
    val config: Config = Config(File("./config/${Initializer.MOD_ID}"))

    /** How often the background loop re-pushes each display's quality setting. */
    private val qualityRefreshInterval = 2500.milliseconds

    /**
     * Owns every background maintenance coroutine. Runs on [Dispatchers.IO] (a pool of daemon
     * threads), so a hung refresh / sweep can never block JVM shutdown; [stop] still cancels it
     * cleanly on a normal exit. [SupervisorJob] keeps the quality-refresh loop and the cache sweep
     * independent, so one failing doesn't cancel the other.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The default module set installed into every [ClientApplication] built by [start]. */
    private val defaultModules: List<DreamDisplaysModule> = listOf(
        ClientStorageModule,
        CoreDisplayModule,
        CorePlaybackModule,
        MediaResolverModule,
        ClientOverlayModule,
        ClientInputModule,
        ClientRenderModule,
        ClientCapabilityModule,
    )

    /** Loads config, wires services, hosts the application, prewarms backends, and launches maintenance loops. */
    fun start() {
        config.reload()
        ClientSettingsStore.load()

        val platform = DreamServices.registry.get(PlatformServices.PLATFORM)
        val application = DefaultClientApplication(DefaultClientContext(platform))
        DreamServices.registry.register<ClientApplication>(application)
        defaultModules.forEach(application::registerModule)
        application.start()

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
