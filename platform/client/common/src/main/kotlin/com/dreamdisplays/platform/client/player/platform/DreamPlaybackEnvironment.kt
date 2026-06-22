package com.dreamdisplays.platform.client.player.platform

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.get
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.media.player.stream.StreamSelector
import com.dreamdisplays.media.player.cache.CacheInvalidator
import com.dreamdisplays.api.media.player.FrameUploader
import com.dreamdisplays.api.media.player.FrameUploaderFactory
import com.dreamdisplays.media.player.PlaybackConfig
import com.dreamdisplays.media.player.PlaybackEnvironment
import com.dreamdisplays.api.media.player.RenderThreadExecutor
import com.dreamdisplays.platform.client.render.DisplayYuvRenderTypes
import com.dreamdisplays.platform.client.render.GpuFrameUploader
import com.dreamdisplays.media.source.ytdlp.YtDlp
import net.minecraft.client.Minecraft

/**
 * Minecraft-client implementation of [PlaybackEnvironment]: bridges the platform-agnostic media
 * player to the live client configuration, the render thread, the GPU uploader, the URL cache, and
 * the service registry. One shared instance is passed to every [com.dreamdisplays.media.player.MediaPlayer].
 */
object DreamPlaybackEnvironment : PlaybackEnvironment {
    /** Live playback config backed by the client config and state. */
    override val config: PlaybackConfig = object : PlaybackConfig {
        /** Default volume for new displays. */
        override val defaultDisplayVolume: Double get() = ClientStateManager.config.defaultDisplayVolume

        /** Whether to use hardware acceleration. */
        override val useHwAccel: Boolean get() = ClientStateManager.config.useHwAccel

        /** Is the client premium? */
        override val isPremium: Boolean get() = ClientStateManager.isPremium

        /** Active GPU YUV render type. */
        override val gpuYuvActive: Boolean get() = DisplayYuvRenderTypes.active
    }

    /** Runs tasks on Minecraft's render/main thread. */
    override val renderExecutor: RenderThreadExecutor = RenderThreadExecutor { task -> Minecraft.getInstance().execute(task) }

    /** Creates per-player GPU frame uploaders. */
    override val uploaderFactory: FrameUploaderFactory = FrameUploaderFactory { GpuFrameUploader() as FrameUploader }

    /** Invalidates the `yt-dlp` URL cache for a stream. */
    override val cacheInvalidator: CacheInvalidator = CacheInvalidator { url -> YtDlp.invalidateCache(url) }

    /** The registered media resolver chain. */
    override fun resolverChain(): MediaResolverRegistry = DreamServices.registry.get<MediaResolverRegistry>()

    /** The registered stream selector. */
    override fun streamSelector(): StreamSelector = DreamServices.registry.get<StreamSelector>()
}
