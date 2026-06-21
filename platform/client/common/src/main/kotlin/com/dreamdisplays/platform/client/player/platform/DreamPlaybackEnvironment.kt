package com.dreamdisplays.platform.client.player.platform

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.get
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.media.api.MediaResolverChain
import com.dreamdisplays.media.api.StreamSelector
import com.dreamdisplays.media.player.api.CacheInvalidator
import com.dreamdisplays.media.player.api.FrameUploader
import com.dreamdisplays.media.player.api.FrameUploaderFactory
import com.dreamdisplays.media.player.api.PlaybackConfig
import com.dreamdisplays.media.player.api.PlaybackEnvironment
import com.dreamdisplays.media.player.api.RenderThreadExecutor
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

    override val config: PlaybackConfig = object : PlaybackConfig {
        override val defaultDisplayVolume: Double get() = ClientStateManager.config.defaultDisplayVolume
        override val useHwAccel: Boolean get() = ClientStateManager.config.useHwAccel
        override val isPremium: Boolean get() = ClientStateManager.isPremium
        override val gpuYuvActive: Boolean get() = DisplayYuvRenderTypes.active
    }

    override val renderExecutor: RenderThreadExecutor =
        RenderThreadExecutor { task -> Minecraft.getInstance().execute(task) }

    override val uploaderFactory: FrameUploaderFactory =
        FrameUploaderFactory { GpuFrameUploader() as FrameUploader }

    override val cacheInvalidator: CacheInvalidator =
        CacheInvalidator { url -> YtDlp.invalidateCache(url) }

    override fun resolverChain(): MediaResolverChain = DreamServices.registry.get<MediaResolverChain>()

    override fun streamSelector(): StreamSelector = DreamServices.registry.get<StreamSelector>()
}
