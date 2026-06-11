package com.dreamdisplays.client.core

import com.dreamdisplays.api.DefaultDisplayService
import com.dreamdisplays.api.DefaultPlaybackService
import com.dreamdisplays.api.DisplayService
import com.dreamdisplays.api.PlaybackService
import com.dreamdisplays.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.client.capabilities.ClientCapabilityDetector
import com.dreamdisplays.client.capabilities.DefaultCapabilityNegotiationService
import com.dreamdisplays.client.capabilities.MinecraftClientCapabilityDetector
import com.dreamdisplays.client.input.CompositeInputHandler
import com.dreamdisplays.client.input.DefaultKeyBindingRegistry
import com.dreamdisplays.client.input.DisplayInteractionService
import com.dreamdisplays.client.input.DisplayMenuInputHandler
import com.dreamdisplays.client.input.InputHandler
import com.dreamdisplays.client.input.KeyBindingRegistry
import com.dreamdisplays.client.input.MinecraftDisplayInteractionService
import com.dreamdisplays.client.overlay.CrosshairPolicy
import com.dreamdisplays.client.overlay.OverlayManager
import com.dreamdisplays.client.popout.DefaultPopoutManager
import com.dreamdisplays.client.popout.PopoutManager
import com.dreamdisplays.client.render.ClientRenderService
import com.dreamdisplays.client.render.RenderHook
import com.dreamdisplays.client.ui.PipOverlayManager
import com.dreamdisplays.managers.ClientStateManager
import com.dreamdisplays.media.DefaultMediaResolverChain
import com.dreamdisplays.media.DefaultMediaSessionManager
import com.dreamdisplays.media.DefaultStreamSelector
import com.dreamdisplays.media.YtDlpSearchService
import com.dreamdisplays.media.api.MediaResolverChain
import com.dreamdisplays.media.api.MediaSearchService
import com.dreamdisplays.media.api.MediaSessionManager
import com.dreamdisplays.media.api.StreamSelector
import com.dreamdisplays.render.AsyncTextureUploader
import com.dreamdisplays.render.DefaultDisplayRenderer
import com.dreamdisplays.render.ScreenRenderer
import com.dreamdisplays.render.api.DisplayRenderer
import com.dreamdisplays.render.api.TextureUploaderFactory
import com.dreamdisplays.ytdlp.NewPipeResolver
import com.dreamdisplays.ytdlp.YtDlpResolver

/**
 * Process-wide [ServiceRegistry] holder and bootstrap entry point.
 *
 * Replaces scattered `object` singletons accessed by name with contract-typed lookups:
 * call [registry].`get<MediaResolverChain>()` instead of touching the concrete resolver objects.
 * [bootstrap] wires the default service graph and is idempotent, so it is safe to call from each
 * platform's startup path.
 *
 * @since 1.8.0
 */
object DreamServices {
    /** The shared registry. Services are populated by [bootstrap]. */
    val registry: ServiceRegistry = DefaultServiceRegistry()

    /**
     * Whether [bootstrap] has been called already. Guards against double registration of services, which can cause
     * issues if services have internal state or are expected to be singletons.
     */
    @Volatile private var bootstrapped = false

    /**
     * Registers the default service graph exactly once.
     *
     * Media pipeline: [MediaResolverChain] ([NewPipeResolver] fast path at priority 10,
     * [YtDlpResolver] subprocess fallback at 0), [MediaSearchService], [MediaSessionManager],
     * and [StreamSelector].
     *
     * Displays and playback: [DisplayService], [PlaybackService], [OverlayManager],
     * [PopoutManager], and [DisplayInteractionService].
     *
     * Client integration: [ClientRenderService] ([ScreenRenderer]), [ClientCapabilityDetector],
     * and [CapabilityNegotiationService].
     *
     * Render: [DisplayRenderer] (orchestrator for API-registered surfaces) and
     * [TextureUploaderFactory] (per-GL-context [AsyncTextureUploader] instances).
     *
     * The loader entrypoint additionally registers a [com.dreamdisplays.platform.api.Platform];
     * when present, [com.dreamdisplays.managers.ClientStartupManager] hosts a
     * [ClientApplication] on top of it.
     */
    @Synchronized fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true

        val resolverChain = DefaultMediaResolverChain().apply {
            register(NewPipeResolver)
            register(YtDlpResolver)
        }

        registry.register<MediaResolverChain>(resolverChain)
        registry.register<MediaSearchService>(YtDlpSearchService())
        registry.register<MediaSessionManager>(DefaultMediaSessionManager())
        registry.register<StreamSelector>(DefaultStreamSelector())
        registry.register<OverlayManager>(PipOverlayManager)
        registry.register<CrosshairPolicy>(CrosshairPolicy { ClientStateManager.isOnScreen })
        registry.register<DisplayInteractionService>(MinecraftDisplayInteractionService)
        registry.register<KeyBindingRegistry>(DefaultKeyBindingRegistry().apply { register(DisplayMenuInputHandler.OPEN_MENU_BINDING) })
        registry.register<InputHandler>(CompositeInputHandler().apply { register(DisplayMenuInputHandler()) })
        registry.register<ClientRenderService>(ScreenRenderer)
        registry.register<PopoutManager>(DefaultPopoutManager())
        registry.register<DisplayService>(DefaultDisplayService())
        registry.register<PlaybackService>(DefaultPlaybackService())
        registry.register<ClientCapabilityDetector>(MinecraftClientCapabilityDetector)
        registry.register<CapabilityNegotiationService>(DefaultCapabilityNegotiationService(MinecraftClientCapabilityDetector))
        registry.register<DisplayRenderer>(DefaultDisplayRenderer())
        registry.register<TextureUploaderFactory>(TextureUploaderFactory { AsyncTextureUploader(stateCache = it) })
        registry.register<RenderHook>(RenderHook { context -> registry.getOrNull<DisplayRenderer>()?.takeIf { it.registeredCount > 0 }?.renderAll(context)
        })
    }
}
