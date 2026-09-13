package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.source.model.MediaMetadata
import com.dreamdisplays.api.media.source.service.MediaResolverService
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.source.model.ResolvedMedia
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultMediaResolverRegistryTest {
    private val source = MediaSource.YouTube("dQw4w9WgXcQ")

    private class FakeResolver(
        override val priority: Int,
        private val warms: Boolean,
        val calls: AtomicInteger = AtomicInteger(),
        private val onPrefetch: () -> Unit = {},
    ) : MediaResolverService {
        override fun canResolve(source: MediaSource): Boolean = true

        override fun resolve(source: MediaSource): ResolvedMedia =
            ResolvedMedia(emptyList(), MediaMetadata.UNKNOWN, isLive = false, isSeekable = false)

        override fun prefetch(source: MediaSource): Boolean {
            calls.incrementAndGet()
            onPrefetch()
            return warms
        }
    }

    /** Waits for the registry's background hint to settle, polling so a fast run finishes fast. */
    private fun awaitCalls(deadlineMs: Long = 2_000, check: () -> Boolean) {
        val until = System.nanoTime() + deadlineMs * 1_000_000
        while (System.nanoTime() < until) {
            if (check()) return
            Thread.sleep(5)
        }
    }

    @Test
    fun `prefetch stops at the first resolver that warms something`() {
        val fast = FakeResolver(priority = 10, warms = true)
        val fallback = FakeResolver(priority = 0, warms = true)
        val registry = DefaultMediaResolverRegistry().apply {
            register(fallback)
            register(fast)
        }

        registry.prefetch(source)
        awaitCalls { fast.calls.get() == 1 }

        assertEquals(1, fast.calls.get(), "The highest-priority resolver should have been warmed.")
        Thread.sleep(100)
        assertEquals(
            0, fallback.calls.get(),
            "The universal fallback must not be warmed once a dedicated resolver already has been — " +
                    "that is what used to spawn a doomed yt-dlp for every Twitch / Vimeo / direct link.",
        )
    }

    @Test
    fun `prefetch falls through a resolver that warmed nothing`() {
        val declines = FakeResolver(priority = 10, warms = false)
        val fallback = FakeResolver(priority = 0, warms = true)
        val registry = DefaultMediaResolverRegistry().apply {
            register(declines)
            register(fallback)
        }

        registry.prefetch(source)
        awaitCalls { fallback.calls.get() == 1 }

        assertEquals(1, declines.calls.get())
        assertEquals(1, fallback.calls.get(), "A resolver that warmed nothing must not end the chain.")
    }

    @Test
    fun `a hint already in flight is not queued again`() {
        val gate = CountDownLatch(1)
        val slow = FakeResolver(priority = 10, warms = true, onPrefetch = { gate.await(2, TimeUnit.SECONDS) })
        val registry = DefaultMediaResolverRegistry().apply { register(slow) }

        // The client fires this on every URL change and every display load, so a wall of screens
        // showing one video used to queue an identical warm-up per screen.
        repeat(8) { registry.prefetch(source) }
        awaitCalls { slow.calls.get() >= 1 }
        assertEquals(1, slow.calls.get(), "Duplicate hints for the same source must collapse into one.")

        gate.countDown()
        // Once it has settled a later hint is allowed through again (caches decide from there)
        awaitCalls { slow.calls.get() == 1 }
        registry.prefetch(source)
        awaitCalls { slow.calls.get() == 2 }
        assertTrue(slow.calls.get() <= 2, "Expected at most one further warm-up, got ${slow.calls.get()}.")
    }

    @Test
    fun `resolve returns the first successful resolver in priority order`() {
        val failing = object : MediaResolverService {
            override val priority: Int = 20
            override fun canResolve(source: MediaSource): Boolean = true
            override fun resolve(source: MediaSource): ResolvedMedia = error("nope")
        }
        val working = FakeResolver(priority = 5, warms = true)
        val registry = DefaultMediaResolverRegistry().apply {
            register(failing)
            register(working)
        }
        assertEquals(0, registry.resolve(source).streams.size)
    }
}
