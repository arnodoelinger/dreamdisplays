package com.dreamdisplays.platform.client.player.platform

import com.dreamdisplays.core.services.keys.MediaServices
import com.dreamdisplays.api.media.player.*
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.stream.StreamSelector
import com.dreamdisplays.core.runtime.get
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.source.bilibili.BilibiliResolver
import com.dreamdisplays.media.source.bytedance.ByteDanceMetadataCache
import com.dreamdisplays.media.source.direct.DirectStreamResolver
import com.dreamdisplays.media.source.kick.KickResolver
import com.dreamdisplays.media.source.twitch.TwitchResolver
import com.dreamdisplays.media.source.vimeo.VimeoResolver
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
    override val renderExecutor: RenderExecutor =
        RenderExecutor { task -> Minecraft.getInstance().execute(task) }

    /** Creates per-player GPU frame uploaders. */
    override val uploaderFactory: FrameUploaderFactory = FrameUploaderFactory { GpuFrameUploader() as FrameUploader }

    /**
     * Invalidates every resolved-URL cache for a stream (`NewPipe`, `yt-dlp`, Twitch, Vimeo, Kick,
     * Bilibili, TikTok/Douyin metadata, direct),
     * forcing a fresh resolve on next play.
     */
    override val cacheInvalidator: CacheInvalidator = CacheInvalidator { url ->
        NewPipeResolver.invalidate(url)
        YtDlp.invalidateCache(url)
        val source = MediaSource.from(url)
        if (source is MediaSource.TikTok || source is MediaSource.Douyin) {
            source.toResolvableUrl()?.takeIf { it != url }?.let(YtDlp::invalidateCache)
        }
        TwitchResolver.invalidate(url)
        VimeoResolver.invalidate(url)
        KickResolver.invalidate(url)
        BilibiliResolver.invalidate(url)
        ByteDanceMetadataCache.invalidate(url)
        DirectStreamResolver.invalidate(url)
    }

    /** The registered media resolver chain. */
    override fun resolverChain(): MediaResolverRegistry = DreamServices.registry.get(MediaServices.RESOLVER_REGISTRY)

    /** The registered stream selector. */
    override fun streamSelector(): StreamSelector = DreamServices.registry.get<StreamSelector>()
}
