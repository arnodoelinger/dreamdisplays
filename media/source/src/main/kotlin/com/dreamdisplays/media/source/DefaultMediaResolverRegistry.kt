package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.DreamMediaException
import com.dreamdisplays.api.media.source.MediaResolver
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.media.runtime.MediaHostGuard
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default [MediaResolverRegistry]: tries registered [MediaResolver]s highest-[MediaResolver.priority]
 * first, skipping any whose [MediaResolver.canResolve] returns false. A resolver that throws is
 * treated as a soft failure and the chain falls through to the next candidate; the last error is
 * rethrown only if every candidate fails.
 *
 * Registration is backed by a [CopyOnWriteArrayList], so [register] / [unregister] are safe to call
 * concurrently with [resolve].
 */
class DefaultMediaResolverRegistry : MediaResolverRegistry {

    private val backing = CopyOnWriteArrayList<MediaResolver>()

    /**
     * Prefetch is a best-effort hint that opens with a blocking DNS lookup (the SSRF guard), so it
     * must never run on the caller's thread - [prefetch] is invoked from the client / render thread on
     * every URL change. A coroutine permit serializes the hints off that path.
     */
    private val prefetchPermit = Semaphore(1)

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

    /**
     * Calls [MediaResolver.prefetch] on every capable resolver for [source]. The SSRF host check and
     * the dispatch run on [DreamCoroutines.clientIo] so the blocking DNS lookup never stalls the caller.
     */
    override fun prefetch(source: MediaSource) {
        DreamCoroutines.clientIo.launch {
            prefetchPermit.withPermit {
                if (isBlockedHost(source)) return@withPermit
                for (resolver in resolvers) {
                    if (resolver.canResolve(source)) runCatching { resolver.prefetch(source) }
                }
            }
        }
    }

    /**
     * Resolves [source] against each capable resolver in priority order, returning the first success.
     * @throws DreamMediaException.Unknown if no resolver is registered for [source].
     * @throws DreamMediaException.Unknown if [source] targets a non-public host (SSRF guard).
     * @throws Throwable the last resolver's failure if every capable resolver threw.
     */
    override fun resolve(source: MediaSource): ResolvedMedia {
        if (isBlockedHost(source)) {
            throw DreamMediaException.Unknown("Refusing to resolve a media URL on a non-public host.", isFatal = true)
        }
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

    /**
     * SSRF guard: true when [source] carries a client-supplied URL whose host resolves to a
     * non-public address. Only [MediaSource.Remote] / [MediaSource.DirectStream] are checked;
     * [MediaSource.YouTube] is rewritten to a fixed, trusted host, and [MediaSource.Twitch] is
     * constrained to the `twitch.tv` / `clips.twitch.tv` hosts by [MediaSource.from] itself.
     */
    private fun isBlockedHost(source: MediaSource): Boolean {
        val url = when (source) {
            is MediaSource.Remote -> source.url
            is MediaSource.DirectStream -> source.streamUrl
            else -> return false
        }
        return !MediaHostGuard.isAllowed(url)
    }
}
