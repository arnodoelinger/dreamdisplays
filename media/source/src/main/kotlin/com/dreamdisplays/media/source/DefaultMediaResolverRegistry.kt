package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.service.MediaResolverService
import com.dreamdisplays.api.media.source.service.MediaResolverRegistry
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.source.model.ResolvedMedia
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/** Default [MediaResolverRegistry]: tries registered resolvers in priority order, returning the first success. */
class DefaultMediaResolverRegistry : MediaResolverRegistry {
    /** Backing list of resolvers, sorted by priority on every access. */
    private val backing = CopyOnWriteArrayList<MediaResolverService>()

    /** Limits concurrent prefetch hints to avoid network/process flooding. */
    private val prefetchPermit = Semaphore(PREFETCH_CONCURRENCY)

    /**
     * Sources with a hint already in flight. The client fires [prefetch] on every URL change and on
     * every display load, so without this a wall of screens showing the same video queues one
     * identical warm-up per screen.
     */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Returns the registered resolvers in priority order (highest first). */
    override val resolvers: List<MediaResolverService>
        get() = backing.sortedByDescending { it.priority }

    /** Adds [resolver] to the chain (btw resolver instance is never registered twice). */
    override fun register(resolver: MediaResolverService) {
        if (resolver !in backing) backing.add(resolver)
    }

    /** Removes [resolver] from the chain; no-op if it was never registered. */
    override fun unregister(resolver: MediaResolverService) {
        backing.remove(resolver)
    }

    /** Prefetches [source] through capable resolvers, stopping at the first success (best-effort). */
    override fun prefetch(source: MediaSource) {
        val key = source.toResolvableUrl() ?: source.toString()
        if (!inFlight.add(key)) return
        DreamCoroutines.clientIo.launch {
            try {
                prefetchPermit.withPermit {
                    if (isBlockedHost(source)) return@withPermit
                    for (resolver in resolvers) {
                        if (!resolver.canResolve(source)) continue
                        if (runCatching { resolver.prefetch(source) }.getOrDefault(false)) break
                    }
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    override fun resolve(source: MediaSource): ResolvedMedia {
        if (isBlockedHost(source)) {
            throw DreamMediaException.Unknown("Refusing to resolve a media URL on a non-public host.", isFatal = true)
        }
        val candidates = resolvers.filter { it.canResolve(source) }
        if (candidates.isEmpty()) {
            throw DreamMediaException.Unknown("No resolver registered for source: $source", isFatal = true)
        }
        if (candidates.size == 1) return candidates[0].resolve(source)
        return runBlocking { raceResolvers(source, candidates) }
    }

    private suspend fun raceResolvers(source: MediaSource, candidates: List<MediaResolverService>): ResolvedMedia {
        val winner = CompletableDeferred<ResolvedMedia>()
        val errors = arrayOfNulls<Throwable>(candidates.size)
        val remaining = AtomicInteger(candidates.size)

        candidates.forEachIndexed { index, resolver ->
            DreamCoroutines.clientIo.launch {
                if (index > 0) delay(RACE_HEAD_START * index)
                if (!winner.isCompleted) {
                    runCatching { resolver.resolve(source) }
                        .onSuccess { winner.complete(it) }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            errors[index] = e
                        }
                }
                if (remaining.decrementAndGet() == 0 && !winner.isCompleted) {
                    winner.completeExceptionally(
                        errors.filterNotNull().firstOrNull()
                            ?: DreamMediaException.Unknown("All resolvers failed for source: $source")
                    )
                }
            }
        }
        return winner.await()
    }

    /** SSRF guard: blocks non-public addresses like localhost, 192.168.*, etc. */
    private fun isBlockedHost(source: MediaSource): Boolean {
        val url = when (source) {
            is MediaSource.Remote -> source.url
            is MediaSource.DirectStream -> source.streamUrl
            else -> return false
        }
        return !MediaHostGuard.isAllowed(url)
    }

    private companion object {
        /**
         * Hints warmed at once. Enough to cover a room of screens, few enough not to flood the
         * network (or the `yt-dlp` subprocess budget) with speculative work.
         */
        const val PREFETCH_CONCURRENCY = 3

        val RACE_HEAD_START = 400.milliseconds
    }
}
