package com.trading.bot.infrastructure.tracing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class TraceObjectKeyTest {
    private fun trace(
        traceId: String?,
        agent: String = "strategy",
        createdAt: Instant = Instant.parse("2026-08-07T10:00:00Z"),
    ) = LlmTrace(
        traceId = traceId,
        ticker = "SBER",
        agent = agent,
        provider = "KIMI",
        model = "kimi-k3",
        fingerprint = "fp",
        systemPrompt = "s",
        userPrompt = "u",
        responseContent = "{}",
        tokensUsed = 10,
        latencyMs = 100,
        isFallback = false,
        fromCache = false,
        createdAt = createdAt,
    )

    @Test
    fun `key uses traceId agent and createdAt as prefix`() {
        val key = traceObjectKey(trace("cycle-1", agent = "arbitrator"))
        assertTrue(key.startsWith("cycle-1/arbitrator/2026-08-07T10:00:00Z-"), "key=$key")
        assertTrue(key.endsWith(".json"), "key=$key")
    }

    @Test
    fun `key falls back to no-trace directory when traceId is blank`() {
        assertTrue(traceObjectKey(trace(null)).startsWith("no-trace/"))
        assertTrue(traceObjectKey(trace("")).startsWith("no-trace/"))
    }

    @Test
    fun `keys are unique across calls`() {
        val a = traceObjectKey(trace("cycle-1"))
        val b = traceObjectKey(trace("cycle-1"))
        assertNotEquals(a, b)
        assertEquals(2, setOf(a, b).size)
    }
}
