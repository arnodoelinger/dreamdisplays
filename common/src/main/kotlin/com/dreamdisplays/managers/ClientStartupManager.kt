package com.dreamdisplays.managers

import com.dreamdisplays.Config
import com.dreamdisplays.Focuser
import com.dreamdisplays.Initializer
import com.dreamdisplays.client.core.ClientApplication
import com.dreamdisplays.client.core.DefaultClientApplication
import com.dreamdisplays.client.core.DefaultClientContext
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.core.register
import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.platform.api.Platform
import com.dreamdisplays.display.DisplayScreen
import com.dreamdisplays.display.DisplaySettings
import com.dreamdisplays.ffmpeg.FFmpegBinary
import com.dreamdisplays.ytdlp.FormatDiskCache
import com.dreamdisplays.ytdlp.YtDlp
import java.io.File

/**
 * Handles client bootstrapping and background maintenance threads.
 */
object ClientStartupManager {
    val config: Config = Config(File("./config/${Initializer.MOD_ID}"))

    // TODO: this is ugly, but it works
    val qualityRefreshThread: Thread = Thread({
        var running = true
        while (running) {
            DisplayManager.getScreens().forEach(DisplayScreen::reloadQuality)
            try {
                Thread.sleep(2500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                running = false
            }
        }
    }, "dreamdisplays-quality-refresh")

    fun start() {
        config.reload()
        DisplaySettings.load()

        // Wire the contract-typed service graph (media resolver chain, ...) before any
        // background prewarm touches it.
        DreamServices.bootstrap()

        // If the loader entrypoint registered a Platform, host the module system on top of it.
        DreamServices.registry.getOrNull<Platform>()?.let { platform ->
            val application = DefaultClientApplication(DefaultClientContext(platform))
            DreamServices.registry.register<ClientApplication>(application)
            application.start()
        }

        YtDlp.prewarmAsync()
        FFmpegBinary.prewarmAsync()

        Thread({ FormatDiskCache.sweepExpired() }, "dreamdisplays-cache-sweep").start()
        Focuser().start()
        qualityRefreshThread.start()
    }
}
