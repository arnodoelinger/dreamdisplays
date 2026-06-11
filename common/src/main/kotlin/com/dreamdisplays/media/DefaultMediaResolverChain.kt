package com.dreamdisplays.media

import com.dreamdisplays.media.api.DreamMediaException
import com.dreamdisplays.media.api.MediaResolver
import com.dreamdisplays.media.api.MediaResolverChain
import com.dreamdisplays.media.api.MediaSource
import com.dreamdisplays.media.api.ResolvedMedia
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default [MediaResolverChain]: tries registered [MediaResolver]s highest-[MediaResolver.priority]
 * first, skipping any whose [MediaResolver.canResolve] returns false. A resolver that throws is
 * treated as a soft failure and the chain falls through to the next candidate; the last error is
 * rethrown only if every candidate fails.
 *
 * Registration is backed by a [CopyOnWriteArrayList], so [register] / [unregister] are safe to call
 * concurrently with [resolve].
 */
class DefaultMediaResolverChain : MediaResolverChain {

    private val backing = CopyOnWriteArrayList<MediaResolver>()

    override val resolvers: List<MediaResolver>
        get() = backing.sortedByDescending { it.priority }

    /** Adds [resolver] to the chain (btw resolver instance is never registered twice). */
    override fun register(resolver: MediaResolver) {
        if (resolver !in backing) backing.add(resolver)
    }

    /** Removes [resolver] from the chain; no-op if it was never registered. */
    override fun unregister(resolver: MediaResolver) {
        backing.remove(resolver)
    }

    /** Calls [MediaResolver.prefetch] on every capable resolver for [source]. */
    override fun prefetch(source: MediaSource) {
        for (resolver in resolvers) {
            if (resolver.canResolve(source)) resolver.prefetch(source)
        }
    }

    /**
     * Resolves [source] against each capable resolver in priority order, returning the first success.
     * @throws DreamMediaException.Unknown if no resolver is registered for [source].
     * @throws Throwable the last resolver's failure if every capable resolver threw.
     */
    override fun resolve(source: MediaSource): ResolvedMedia {
        var lastError: Throwable? = null
        var attempted = false
        for (resolver in resolvers) {
            if (!resolver.canResolve(source)) continue
            attempted = true
            try {
                return resolver.resolve(source)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        if (!attempted) throw DreamMediaException.Unknown("No resolver registered for source: $source", isFatal = true)
        throw lastError ?: DreamMediaException.Unknown("All resolvers failed for source: $source")
    }
}
