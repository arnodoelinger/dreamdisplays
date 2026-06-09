package com.dreamdisplays.client.core

import com.dreamdisplays.client.input.DisplayInteractionService
import com.dreamdisplays.client.input.MinecraftDisplayInteractionService
import com.dreamdisplays.client.overlay.OverlayManager
import com.dreamdisplays.client.ui.PipOverlayManager
import com.dreamdisplays.media.DefaultMediaResolverChain
import com.dreamdisplays.media.api.MediaResolverChain
import com.dreamdisplays.ytdlp.NewPipeResolver
import com.dreamdisplays.ytdlp.YtDlpResolver

/**
 * Process-wide [ServiceRegistry] holder and bootstrap entry point.
 *
 * Replaces scattered `object` singletons accessed by name with contract-typed lookups:
 * call [registry].`get<MediaResolverChain>()` instead of touching the concrete resolver objects.
 * [bootstrap] wires the default service graph and is idempotent, so it is safe to call from each
 * platform's startup path.
 */
object DreamServices {

    /** The shared registry. Services are populated by [bootstrap]. */
    val registry: ServiceRegistry = DefaultServiceRegistry()

    @Volatile
    private var bootstrapped = false

    /**
     * Registers the default service graph exactly once:
     *  - a [MediaResolverChain] containing the in-process [NewPipeResolver] (fast path, priority 10)
     *    and the [YtDlpResolver] subprocess fallback (priority 0);
     *  - the [OverlayManager] backed by [PipOverlayManager];
     *  - the [DisplayInteractionService] backed by [MinecraftDisplayInteractionService].
     */
    @Synchronized
    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true

        val resolverChain = DefaultMediaResolverChain().apply {
            register(NewPipeResolver)
            register(YtDlpResolver)
        }
        registry.register<MediaResolverChain>(resolverChain)
        registry.register<OverlayManager>(PipOverlayManager)
        registry.register<DisplayInteractionService>(MinecraftDisplayInteractionService)
    }
}
