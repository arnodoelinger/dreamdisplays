package com.dreamdisplays.platform.client.player.platform

import com.dreamdisplays.core.services.keys.MediaServices
import com.dreamdisplays.api.media.player.*
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.stream.StreamSelector
import com.dreamdisplays.core.runtime.get
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.source.direct.DirectStreamResolver
import com.dreamdisplays.media.source.platform.Platforms
import com.dreamdisplays.media.source.youtube.NewPipeResolver
import com.dreamdisplays.media.source.youtube.YtDlp
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.render.DisplayYuvRenderTypes
import com.dreamdisplays.platform.client.render.GpuFrameUploader
import net.minecraft.client.Minecraft

/**
 * Minecraft-client implementation of [PlaybackEnvironment]: bridges the platform-agnostic media
 * player to the live client configuration, the render thread, the GPU uploader, the URL cache, and
 * the service registry. One shared instance is passed to every [MediaPlayer].
 */
object DreamPlaybackEnvironment : PlaybackEnvironment {
    override val config: PlaybackConfig = object : PlaybackConfig {
        override val defaultDisplayVolume: Double get() = ClientStateManager.config.defaultDisplayVolume

        override val useHwAccel: Boolean get() = ClientStateManager.config.useHwAccel

        override val isPremium: Boolean get() = ClientStateManager.isPremium

        override val gpuYuvActive: Boolean get() = DisplayYuvRenderTypes.active
    }

    override val renderExecutor: RenderExecutor =
        RenderExecutor { task -> Minecraft.getInstance().execute(task) }

    override val uploaderFactory: FrameUploaderFactory = FrameUploaderFactory { GpuFrameUploader() as FrameUploader }

    override val cacheInvalidator: CacheInvalidator = CacheInvalidator { url ->
        NewPipeResolver.invalidate(url)
        YtDlp.invalidateCache(url)
        Platforms.invalidate(url)
        DirectStreamResolver.invalidate(url)
    }

    override fun resolverChain(): MediaResolverRegistry = DreamServices.registry.get(MediaServices.RESOLVER_REGISTRY)

    override fun streamSelector(): StreamSelector = DreamServices.registry.get<StreamSelector>()
}
