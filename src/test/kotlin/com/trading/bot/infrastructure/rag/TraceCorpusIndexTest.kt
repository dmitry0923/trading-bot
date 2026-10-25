package com.trading.bot.infrastructure.rag

import com.trading.bot.infrastructure.tracing.LlmTrace
import com.trading.bot.infrastructure.tracing.TraceStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [TraceCorpusIndex]: локальный TF-IDF-корпус LLM-трейсов для RAG.
 *
 * Хранилище подменяется in-memory фейком [TraceStorage] — сеть/S3 не нужна.
 * Проверяются: пустой корпус (снимок сбрасывается), переиндексация с частично
 * недоступными объектами (best-effort: `read` вернул null), ранжирование с порогом
 * и лимитом `k`, вырожденный запрос без токенов.
 */
class TraceCorpusIndexTest {
    private val embedder = TraceEmbedder()
    private val storage = FakeTraceStorage()
    private val index = TraceCorpusIndex(storage, embedder)

    private fun trace(
        agent: String = "strategy",
        ticker: String? = "SBER",
        provider: String = "ROUTER_AI",
        errorMessage: String? = null,
        isFallback: Boolean = false,
        fromCache: Boolean = false,
        systemPrompt: String = "system prompt about risk",
        userPrompt: String = "user prompt about volatility",
        responseContent: String = "{\"conclusion\":\"NEUTRAL\"}",
    ): LlmTrace =
        LlmTrace(
            traceId = "trace-1",
            ticker = ticker,
            agent = agent,
            provider = provider,
            model = "model-a",
            fingerprint = null,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            responseContent = responseContent,
            tokensUsed = 10,
            latencyMs = 42,
            isFallback = isFallback,
            fromCache = fromCache,
            errorMessage = errorMessage,
            createdAt = Instant.parse("2026-10-25T10:00:00Z"),
        )

    @Test
    fun `refresh on empty storage clears snapshot and records refresh time`() {
        storage.keys = emptyList()

        val indexed = runBlocking { index.refresh(10) }

        assertEquals(0, indexed)
        assertEquals(0, index.size)
        assertNotNull(index.lastRefresh)
        assertTrue(index.search("volatility", 5, 0.0).isEmpty())
    }

    @Test
    fun `refresh indexes readable traces and tolerates missing objects`() {
        storage.keys = listOf("a/strategy/1.json", "a/strategy/2.json", "a/strategy/3.json")
        storage.objects["a/strategy/1.json"] = trace(errorMessage = "timeout after 30s")
        // второй ключ «не читается» (удалён/недоступен) — корпус не должен падать
        storage.objects["a/strategy/3.json"] = trace(agent = "technical", errorMessage = "rsi out of range")

        val indexed = runBlocking { index.refresh(10) }

        assertEquals(2, indexed)
        assertEquals(2, index.size)
        assertNotNull(index.lastRefresh)
    }

    @Test
    fun `refresh with two keys keeps best match first and honours k`() {
        storage.keys = listOf("risk.json", "other.json")
        storage.objects["risk.json"] =
            trace(errorMessage = "risk limit breached volatility")
        storage.objects["other.json"] =
            trace(agent = "fundamental", errorMessage = "dividend calendar")

        runBlocking { index.refresh(10) }

        val top = index.search("risk volatility breach", 1, 0.0)

        assertEquals(1, top.size)
        assertEquals("risk.json", top.first().key)
        assertTrue(top.first().score > 0.0)
    }

    @Test
    fun `search before any refresh returns empty result`() {
        assertTrue(index.search("risk", 5, 0.0).isEmpty())
        assertNull(index.lastRefresh)
    }

    @Test
    fun `search ignores results below similarity threshold`() {
        storage.keys = listOf("risk.json")
        storage.objects["risk.json"] = trace(errorMessage = "risk limit breached volatility")
        runBlocking { index.refresh(10) }

        // Запрос не пересекается с документом по токенам → score = 0 < threshold
        assertTrue(index.search("dividend calendar", 5, 0.5).isEmpty())
    }

    @Test
    fun `search with stopword-only query returns empty`() {
        storage.keys = listOf("risk.json")
        storage.objects["risk.json"] = trace(errorMessage = "risk limit breached volatility")
        runBlocking { index.refresh(10) }

        // «the» — стоп-слово, «x» — короче 2 символов: вектор запроса пуст
        assertTrue(index.search("the x", 5, 0.0).isEmpty())
    }

    @Test
    fun `searchSimilar finds traces similar to the given one`() {
        storage.keys = listOf("risk.json", "other.json")
        storage.objects["risk.json"] = trace(errorMessage = "risk limit breached volatility")
        storage.objects["other.json"] = trace(agent = "fundamental", errorMessage = "dividend calendar")
        runBlocking { index.refresh(10) }

        val similar = index.searchSimilar(trace(errorMessage = "risk limit breached volatility"), 2, 0.0)

        assertEquals(setOf("risk.json", "other.json"), similar.map { it.key }.toSet())
    }

    @Test
    fun `indexing handles traces with empty optional fields`() {
        storage.keys = listOf("bare.json")
        storage.objects["bare.json"] =
            trace(
                ticker = null,
                errorMessage = null,
                systemPrompt = "",
                userPrompt = "",
                responseContent = "",
            )

        val indexed = runBlocking { index.refresh(10) }

        assertEquals(1, indexed)
    }

    /** In-memory [TraceStorage]: `list` отдаёт предзаданные ключи, `read` — предзаданные объекты. */
    private class FakeTraceStorage : TraceStorage {
        var keys: List<String> = emptyList()
        val objects: MutableMap<String, LlmTrace> = mutableMapOf()

        override suspend fun save(
            trace: LlmTrace,
            key: String?,
        ): String? = null

        override suspend fun list(limit: Int): List<String> = keys.take(limit)

        override suspend fun listByTraceId(
            traceId: String,
            limit: Int,
        ): List<String> = emptyList()

        override suspend fun read(key: String): LlmTrace? = objects[key]
    }
}
