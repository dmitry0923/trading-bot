package com.trading.bot.service

import com.trading.bot.infrastructure.tracing.LlmTrace
import com.trading.bot.infrastructure.tracing.TraceStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class TraceQueryServiceTest {
    private fun trace(
        traceId: String,
        agent: String,
    ) = LlmTrace(
        traceId = traceId,
        ticker = "SBER",
        agent = agent,
        provider = "KIMI",
        model = "kimi-k3",
        fingerprint = null,
        systemPrompt = "s",
        userPrompt = "u",
        responseContent = "{}",
        tokensUsed = 10,
        latencyMs = 100,
        isFallback = false,
        fromCache = false,
        createdAt = Instant.parse("2026-08-07T10:00:00Z"),
    )

    private class FakeStorage(
        private val entries: Map<String, LlmTrace>,
    ) : TraceStorage {
        override suspend fun save(
            trace: LlmTrace,
            key: String?,
        ): String? = key

        override suspend fun list(limit: Int): List<String> = entries.keys.take(limit)

        override suspend fun listByTraceId(
            traceId: String,
            limit: Int,
        ): List<String> = entries.filterKeys { it.startsWith("$traceId/") }.keys.take(limit)

        override suspend fun read(key: String): LlmTrace? = entries[key]
    }

    @Test
    fun `getByStorageKey returns the trace`() {
        val t = trace("cycle-1", "strategy")
        val service = TraceQueryService(FakeStorage(mapOf("cycle-1/strategy/key.json" to t)))

        val found = runBlocking { service.getByStorageKey("cycle-1/strategy/key.json") }

        assertEquals(t, found)
    }

    @Test
    fun `getByStorageKey returns null for missing key`() {
        val service = TraceQueryService(FakeStorage(emptyMap()))
        assertNull(runBlocking { service.getByStorageKey("missing") })
    }

    @Test
    fun `getByStorageKey rejects blank key`() {
        val service = TraceQueryService(FakeStorage(emptyMap()))
        assertThrows<IllegalArgumentException> { runBlocking { service.getByStorageKey("") } }
    }

    @Test
    fun `listByCycleId returns only traces of that cycle`() {
        val storage =
            FakeStorage(
                mapOf(
                    "cycle-1/strategy/a.json" to trace("cycle-1", "strategy"),
                    "cycle-1/technical/b.json" to trace("cycle-1", "technical"),
                    "cycle-2/strategy/c.json" to trace("cycle-2", "strategy"),
                ),
            )
        val service = TraceQueryService(storage)

        val traces = runBlocking { service.listByCycleId("cycle-1", 10) }

        assertEquals(2, traces.size)
        assertEquals(setOf("strategy", "technical"), traces.map { it.agent }.toSet())
    }

    @Test
    fun `listByCycleId rejects blank cycleId`() {
        val service = TraceQueryService(FakeStorage(emptyMap()))
        assertThrows<IllegalArgumentException> { runBlocking { service.listByCycleId(" ", 10) } }
    }

    @Test
    fun `listRecent returns latest traces and clamps limit`() {
        val storage =
            FakeStorage(
                mapOf(
                    "cycle-1/a.json" to trace("cycle-1", "a"),
                    "cycle-1/b.json" to trace("cycle-1", "b"),
                    "cycle-1/c.json" to trace("cycle-1", "c"),
                ),
            )
        val service = TraceQueryService(storage)

        assertEquals(2, runBlocking { service.listRecent(2) }.size)
        assertEquals(3, runBlocking { service.listRecent(500) }.size)
    }
}
