package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.getOrNull
import com.dreamdisplays.media.api.MediaResolverChain
import com.dreamdisplays.media.api.MediaSource
import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.platform.client.player.platform.DisplayPlaybackHost
import com.dreamdisplays.platform.client.player.platform.DreamPlaybackEnvironment
import net.minecraft.client.Minecraft
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the media-player lifecycle for a single [DisplayScreen]: swapping in a fresh [MediaPlayer] on
 * URL change, generation-guarding async init callbacks against stale players, applying the screen's
 * saved state on start, and teardown.
 *
 * Pulled out of [DisplayScreen] (the same way as [DisplaySyncController]) so the screen no longer
 * interleaves player creation and swap bookkeeping with settings and packet handling. The controller
 * drives the screen back through a small set of `internal` hooks.
 */
internal class DisplayMediaController(private val screen: DisplayScreen) {
    private val generation = AtomicLong()

    /** The active media player, or null between videos and after [shutdown]. */
    @Volatile var player: MediaPlayer? = null
        private set

    /** True once [start] has applied the screen's initial state to the current player. */
    var videoStarted: Boolean = false
        private set

    /** Current player generation; bumped on every swap so stale async callbacks can be detected. */
    val generationNow: Long get() = generation.get()

    /**
     * Stops any current player, creates a fresh [MediaPlayer] for [videoUrl], wires the texture and
     * popout sinks, and defers [start] until the player reports initialized. When
     * [preservePausedState] is true the screen's current paused state is reapplied after start.
     */
    fun load(videoUrl: String, lang: String, preservePausedState: Boolean) {
        if (videoUrl == "") return

        DreamServices.registry.getOrNull<MediaResolverChain>()?.prefetch(MediaSource.from(videoUrl))

        val expected = generation.incrementAndGet()
        val oldPlayer = player
        player = null
        videoStarted = false
        screen.mediaError = null
        screen.timelineFollower.reset()
        oldPlayer?.stop()

        screen.onVideoSwapped(videoUrl, lang)
        DisplayRegistry.recordScreen(screen)
        val shouldBePaused = preservePausedState && screen.paused
        val newPlayer = MediaPlayer(
            videoUrl, lang, DisplayPlaybackHost(screen), DreamPlaybackEnvironment,
            screen.takeReplayBootstrap(videoUrl),
        )
        player = newPlayer
        screen.timelineFollower.onPlayerCreated()
        // Set the effective volume (incl. distance) now, before the bridge prelude (which starts at
        // construction) becomes audible — otherwise its first moment plays at the un-attenuated level.
        screen.primeNewPlayerVolume(newPlayer)
        screen.prepareTextureDimensions()

        screen.attachPopout(newPlayer)

        whenInitialized(expected) {
            start()
            if (shouldBePaused) {
                screen.paused = true
                player?.pause()
            }
        }

        Minecraft.getInstance().execute { screen.reloadTexture() }
    }

    /** Applies volume, brightness, and paused state to the player, then seeks to the saved position. */
    fun start() {
        val mp = player ?: return
        videoStarted = true
        screen.applyEffectiveVolume()
        mp.setBrightness(screen.brightness)
        if (screen.paused) mp.pause() else {
            mp.play()
            screen.paused = false
        }
        // A replay-bootstrap player already resumes at the saved position; restoreSavedTime()'s
        // corrective seek would cold-restart the session and destroy the seamless replay -> live bridge.
        if (!mp.isResumingFromReplay()) screen.restoreSavedTime()
        // No bootstrap needed for sync since 1.8.0.
        DisplayRegistry.recordScreen(screen)
    }

    /** Runs [action] once the current player is initialized; guards against stale generations. */
    fun whenInitialized(action: () -> Unit) = whenInitialized(generation.get(), action)

    /** Runs [action] when the player is initialized, only if [expectedGeneration] still matches (i.e. video hasn't changed). */
    private fun whenInitialized(expectedGeneration: Long, action: () -> Unit) {
        val mp = player ?: return
        mp.whenInitialized {
            if (expectedGeneration != generation.get()) return@whenInitialized
            if (mp !== player) return@whenInitialized
            if (screen.errored) return@whenInitialized
            action()
        }
    }

    /** Detaches the current player and invalidates pending callbacks; returns it for final teardown. */
    fun shutdown(): MediaPlayer? {
        generation.incrementAndGet()
        videoStarted = false
        val current = player
        player = null
        return current
    }
}
