package com.dreamdisplays.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AsyncMemoTest {
    @Test
    fun cachesSuccessfulLoads() {
        val scope = testScope()
        try {
            val memo = AsyncMemo<String, String>(maxSize = 10, ttlMs = 60_000, scope = scope, tag = "test")
            var loads = 0

            val first = memo.getBlocking("video") { "value-${++loads}" }
            val second = memo.getBlocking("video") { "value-${++loads}" }

            assertEquals("value-1", first)
            assertEquals("value-1", second)
            assertEquals(1, loads)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disabledCacheStillDeduplicatesInFlightLoads() = runBlocking {
        val scope = testScope()
        try {
            val memo = AsyncMemo<String, String>(maxSize = 0, ttlMs = 60_000, scope = scope, tag = "test")
            val gate = CompletableDeferred<Unit>()
            var loads = 0

            val first = memo.load("video") {
                loads++
                gate.await()
                "value"
            }
            val second = memo.load("video") { "other" }

            assertSame(first, second)
            gate.complete(Unit)
            assertEquals("value", first.await())
            assertEquals(1, loads)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disabledCacheDoesNotRetainCompletedValues() {
        val scope = testScope()
        try {
            val memo = AsyncMemo<String, String>(maxSize = 0, ttlMs = 60_000, scope = scope, tag = "test")
            var loads = 0

            val first = memo.getBlocking("video") { "value-${++loads}" }
            val second = memo.getBlocking("video") { "value-${++loads}" }

            assertEquals("value-1", first)
            assertEquals("value-2", second)
            assertEquals(2, loads)
        } finally {
            scope.cancel()
        }
    }

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
