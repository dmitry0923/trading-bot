package com.trading.bot.infrastructure.tracing

import com.trading.bot.config.TraceStorageConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AsyncTraceStorageTest {
    private val meterRegistry = SimpleMeterRegistry()

    private fun trace(
        traceId: String = "cycle-1",
        agent: String = "strategy",
    ) = LlmTrace(
        traceId = traceId,
        ticker = "SBER",
        agent = agent,
        provider = "KIMI",
        model = "kimi-k3",
        fingerprint = "fp",
        systemPrompt = "system",
        userPrompt = "user",
        responseContent = "{}",
        tokensUsed = 10,
        latencyMs = 100,
        isFallback = false,
        fromCache = false,
        createdAt = Instant.parse("2026-08-07T10:00:00Z"),
    )

    private fun config(bufferSize: Int = 16): TraceStorageConfig =
        TraceStorageConfig().apply {
            enabled = true
            asyncBufferSize = bufferSize
        }

    private suspend fun awaitUntil(
        timeoutMs: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met within ${timeoutMs}ms")
            delay(10)
        }
    }

    private open class InMemoryStorage : TraceStorage {
        val saved = mutableListOf<Pair<String, LlmTrace>>()

        override suspend fun save(
            trace: LlmTrace,
            key: String?,
        ): String? {
            saved.add((key ?: traceObjectKey(trace)) to trace)
            return key
        }

        override suspend fun list(limit: Int): List<String> = saved.map { it.first }.take(limit)

        override suspend fun listByTraceId(
            traceId: String,
            limit: Int,
        ): List<String> = saved.filter { it.first.startsWith("$traceId/") }.map { it.first }.take(limit)

        override suspend fun read(key: String): LlmTrace? = saved.firstOrNull { it.first == key }?.second
    }

    @Test
    fun `save returns key immediately and persists in background`() =
        runBlocking {
            val delegate = InMemoryStorage()
            val storage = AsyncTraceStorage(config(), delegate, meterRegistry)
            val t = trace()

            val key = storage.save(t)

            assertNotNull(key)
            assertTrue(key!!.startsWith("cycle-1/strategy/"))
            awaitUntil { delegate.saved.isNotEmpty() }
            assertEquals(t, delegate.saved.first().second)
        }

    @Test
    fun `disabled storage returns null and does not enqueue`() =
        runBlocking {
            val delegate = InMemoryStorage()
            val cfg =
                TraceStorageConfig().apply {
                    enabled = false
                    asyncBufferSize = 4
                }
            val storage = AsyncTraceStorage(cfg, delegate, meterRegistry)

            assertNull(storage.save(trace()))
            delay(50)
            assertTrue(delegate.saved.isEmpty())
        }

    @Test
    fun `buffer overflow falls back to synchronous save`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val delegate =
                object : InMemoryStorage() {
                    override suspend fun save(
                        trace: LlmTrace,
                        key: String?,
                    ): String? {
                        gate.await()
                        return super.save(trace, key)
                    }
                }
            val storage = AsyncTraceStorage(config(bufferSize = 1), delegate, meterRegistry)

            coroutineScope {
                val a = async { storage.save(trace(traceId = "a")) }
                val b = async { storage.save(trace(traceId = "b")) }
                val c = async { storage.save(trace(traceId = "c")) }
                // Даём фоновому консюмеру подхватить первую запись и заблокироваться,
                // чтобы буфер гарантированно переполнился для третьей.
                delay(50)
                gate.complete(Unit)
                val keys = listOf(a, b, c).awaitAll()
                assertTrue(keys.all { it != null }, "all saves must return a key")
            }

            val fallback = meterRegistry.counter("trace.write.async", "result", "sync_fallback").count()
            assertTrue(fallback >= 1, "expected at least one sync fallback, got $fallback")
            awaitUntil { delegate.saved.size == 3 }
        }

    @Test
    fun `list read and listByTraceId delegate to storage`() =
        runBlocking {
            val delegate = InMemoryStorage()
            val storage = AsyncTraceStorage(config(), delegate, meterRegistry)
            val t = trace(traceId = "cycle-9", agent = "technical")
            storage.save(t)
            awaitUntil { delegate.saved.isNotEmpty() }

            val key = delegate.saved.first().first
            assertEquals(t, storage.read(key))
            assertEquals(listOf(key), storage.listByTraceId("cycle-9", 10))
            assertEquals(listOf(key), storage.list(10))
            assertEquals(emptyList<String>(), storage.listByTraceId("other-cycle", 10))
        }
}
