package com.trading.bot.service

import com.trading.bot.config.RagConfig
import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.rag.ScoredTrace
import com.trading.bot.infrastructure.rag.TraceCorpusIndex
import com.trading.bot.infrastructure.tracing.LlmTrace
import com.trading.bot.infrastructure.tracing.TraceStorage
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [RagErrorAnalyzer]: RAG-разбор ошибок LLM-агентов.
 *
 * Корпус и хранилище подменяются фейками — S3/MinIO и сеть не нужны. Покрываются:
 * индексация корпуса (успех/сбой), оба режима отчёта (rule-based и LLM),
 * деградация при недоступном LLM (fallback → rule-based), разбор конкретного
 * трейса (найден/не найден/без похожих), лимит `k` и `status()`.
 */
class RagErrorAnalyzerTest {
    private val storage = FakeTraceStorage()
    private var searchResults: List<ScoredTrace> = emptyList()
    private val refreshResult = AtomicInteger(3)
    private val refreshCalls = AtomicInteger(0)
    private var refreshFailure: RuntimeException? = null
    private val searchedK = AtomicReference<Int?>(null)
    private val refreshDone = CountDownLatch(1)

    private val corpus: TraceCorpusIndex =
        Mockito.mock(TraceCorpusIndex::class.java) { invocation ->
            when (invocation.method.name) {
                "refresh" -> {
                    refreshCalls.incrementAndGet()
                    refreshDone.countDown()
                    refreshFailure?.let { throw it }
                    refreshResult.get()
                }

                "search", "searchSimilar" -> {
                    searchedK.set(invocation.getArgument(1))
                    searchResults
                }

                "getSize" -> {
                    searchResults.size
                }

                "getLastRefresh" -> {
                    null
                }

                else -> {
                    Mockito.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        }

    private var llmAnswer: LlmResponse = LlmResponse(content = "LLM report")
    private val llmClient: ResilientLlmClient =
        Mockito.mock(ResilientLlmClient::class.java) { invocation ->
            if (invocation.method.name == "complete") llmAnswer else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    private val template = PromptTemplate("rag-analyzer", "v1", "system", "user {{query}}")
    private val promptRegistry = Mockito.mock(PromptRegistry::class.java)

    private val meterRegistry = SimpleMeterRegistry()
    private var analyzerHolder: RagErrorAnalyzer? = null

    private fun analyzer(config: RagConfig = config()): RagErrorAnalyzer {
        Mockito.`when`(promptRegistry.getTemplate(anyOrNull(), anyOrNull())).thenReturn(template)
        return RagErrorAnalyzer(config, corpus, storage, llmClient, promptRegistry, meterRegistry)
    }

    private fun config(
        enabled: Boolean = true,
        llmEnabled: Boolean = true,
        maxResults: Int = 5,
    ): RagConfig =
        RagConfig().apply {
            this.enabled = enabled
            this.llmEnabled = llmEnabled
            this.maxResults = maxResults
            this.corpusLimit = 500
            this.similarityThreshold = 0.02
        }

    private fun trace(
        agent: String = "strategy",
        ticker: String? = "SBER",
        fallback: Boolean = false,
        fromCache: Boolean = false,
        errorMessage: String? = "timeout",
        response: String = "{\"conclusion\":\"NEUTRAL\"}",
    ): LlmTrace =
        LlmTrace(
            traceId = "trace-1",
            ticker = ticker,
            agent = agent,
            provider = "ROUTER_AI",
            model = "model-a",
            fingerprint = null,
            systemPrompt = "system",
            userPrompt = "user",
            responseContent = response,
            tokensUsed = 5,
            latencyMs = 120,
            isFallback = fallback,
            fromCache = fromCache,
            errorMessage = errorMessage,
            createdAt = Instant.parse("2026-10-25T10:00:00Z"),
        )

    private fun scored(
        key: String,
        trace: LlmTrace = trace(),
        score: Double = 0.7,
    ): ScoredTrace = ScoredTrace(key, trace, score)

    @AfterEach
    fun tearDown() {
        // scope анализатора (Dispatchers.Default) освобождается в @PreDestroy
        analyzerHolder?.close()
        analyzerHolder = null
    }

    // ─── refresh ──────────────────────────────────────────────────────

    @Test
    fun `refresh returns indexed count and records gauge`() {
        analyzerHolder = analyzer()

        val index = runBlocking { analyzerHolder!!.refresh() }

        assertEquals(3, index)
        assertEquals(1, refreshCalls.get())
        assertEquals(1.0, meterRegistry.counter("rag.refresh", "status", "ok").count())
    }

    @Test
    fun `refresh swallows corpus failure and reports error metric`() {
        refreshFailure = IllegalStateException("storage unavailable")
        analyzerHolder = analyzer()

        val index = runBlocking { analyzerHolder!!.refresh() }

        assertEquals(0, index)
        assertEquals(1.0, meterRegistry.counter("rag.refresh", "status", "error").count())
    }

    @Test
    fun `init refreshes corpus when rag enabled`() {
        analyzerHolder = analyzer()

        analyzerHolder!!.init()

        assertTrue(refreshDone.await(2, TimeUnit.SECONDS), "corpus refresh must be triggered on startup")
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `init and scheduled refresh do nothing when rag disabled`() {
        analyzerHolder = analyzer(config(enabled = false))

        analyzerHolder!!.init()
        analyzerHolder!!.scheduledRefresh()

        assertFalse(refreshDone.await(300, TimeUnit.MILLISECONDS))
        assertEquals(0, refreshCalls.get())
    }

    @Test
    fun `scheduled refresh reindexes corpus when rag enabled`() {
        analyzerHolder = analyzer()

        analyzerHolder!!.scheduledRefresh()

        assertTrue(refreshDone.await(2, TimeUnit.SECONDS))
        assertEquals(1, refreshCalls.get())
    }

    // ─── analyze ──────────────────────────────────────────────────────

    @Test
    fun `analyze reports disabled mode without touching corpus`() {
        analyzerHolder = analyzer(config(enabled = false))

        val analysis = runBlocking { analyzerHolder!!.analyze("boom") }

        assertEquals("DISABLED", analysis.mode)
        assertTrue(analysis.report.contains("rag.enabled=false"))
        assertTrue(analysis.retrievedTraces.isEmpty())
    }

    @Test
    fun `analyze without similar traces returns rule based hint to refresh corpus`() {
        analyzerHolder = analyzer()
        searchResults = emptyList()

        val analysis = runBlocking { analyzerHolder!!.analyze("boom") }

        assertEquals("RULE_BASED", analysis.mode)
        assertTrue(analysis.report.contains("No similar traces found"))
        assertTrue(analysis.retrievedTraces.isEmpty())
        assertEquals(1.0, meterRegistry.counter("rag.search").count())
    }

    @Test
    fun `analyze builds rule based report and clamps k to maximum`() {
        analyzerHolder = analyzer(config(llmEnabled = false))
        searchResults =
            listOf(
                scored("a", trace(agent = "technical", errorMessage = "rsi out of range")),
                scored("b", trace(agent = "technical", errorMessage = "rsi out of range", fallback = true)),
                scored("c", trace(agent = "strategy", errorMessage = "timeout", fromCache = true), 0.4),
            )

        val analysis = runBlocking { analyzerHolder!!.analyze("why did it fail", ticker = "SBER", k = 100) }

        assertEquals("RULE_BASED", analysis.mode)
        assertTrue(analysis.report.contains("Top agent: technical"))
        assertTrue(analysis.report.contains("fallback=1/3"))
        assertTrue(analysis.report.contains("fromCache=1/3"))
        assertTrue(analysis.report.contains("rsi out of range"))
        assertEquals(3, analysis.retrievedTraces.size)
        assertEquals(20, searchedK.get(), "k must be clamped to 20")
        assertEquals("a", analysis.retrievedTraces.first().key)
    }

    @Test
    fun `rule based report omits error section when traces have no error message`() {
        analyzerHolder = analyzer(config(llmEnabled = false))
        searchResults = listOf(scored("a", trace(errorMessage = null)))

        val analysis = runBlocking { analyzerHolder!!.analyze("boom") }

        assertEquals("RULE_BASED", analysis.mode)
        assertFalse(analysis.report.contains("Distinct error messages"))
        assertTrue(analysis.report.contains("avg latency=120ms"))
    }

    @Test
    fun `analyze uses llm report when provider answers`() {
        analyzerHolder = analyzer()
        llmAnswer = LlmResponse(content = "Root cause: rate limiter", latencyMs = 120)
        searchResults = listOf(scored("a", trace(response = "x".repeat(700))))

        val analysis = runBlocking { analyzerHolder!!.analyze("boom", ticker = "SBER") }

        assertEquals("LLM", analysis.mode)
        assertEquals("Root cause: rate limiter", analysis.report)
        // preview длинного ответа обрезается
        val preview = analysis.retrievedTraces.single().responsePreview
        assertTrue(preview.endsWith("...[truncated]"))
    }

    @Test
    fun `analyze degrades to rule based when llm falls back`() {
        analyzerHolder = analyzer()
        llmAnswer = LlmResponse.fallback("NO_API_KEY")
        searchResults = listOf(scored("a", trace()))

        val analysis = runBlocking { analyzerHolder!!.analyze("boom") }

        assertEquals("RULE_BASED", analysis.mode)
        assertTrue(analysis.report.contains("[LLM unavailable, fallback to rule-based]"))
        assertEquals(1.0, meterRegistry.counter("rag.llm.error").count())
    }

    @Test
    fun `analyze degrades to rule based when llm returns blank content`() {
        analyzerHolder = analyzer()
        llmAnswer = LlmResponse(content = "   ")
        searchResults = listOf(scored("a", trace()))

        val analysis = runBlocking { analyzerHolder!!.analyze("boom") }

        assertEquals("RULE_BASED", analysis.mode)
        assertEquals(1.0, meterRegistry.counter("rag.llm.error").count())
    }

    // ─── analyzeTrace ─────────────────────────────────────────────────

    @Test
    fun `analyzeTrace reports missing trace without corpus search`() {
        analyzerHolder = analyzer()
        storage.objects.clear()

        val analysis = runBlocking { analyzerHolder!!.analyzeTrace("missing.json") }

        assertEquals("RULE_BASED", analysis.mode)
        assertTrue(analysis.report.contains("Trace not found: missing.json"))
        assertTrue(analysis.retrievedTraces.isEmpty())
    }

    @Test
    fun `analyzeTrace with no similar traces returns the trace itself`() {
        analyzerHolder = analyzer()
        val stored = trace(errorMessage = "timeout after 30s")
        storage.objects["k1.json"] = stored
        searchResults = listOf(scored("k1.json", stored, 1.0))

        val analysis = runBlocking { analyzerHolder!!.analyzeTrace("k1.json") }

        assertEquals("RULE_BASED", analysis.mode)
        assertTrue(analysis.report.contains("No similar traces in corpus"))
        assertEquals("k1.json", analysis.retrievedTraces.single().key)
        assertEquals(
            "agent=strategy ticker=SBER error=timeout after 30s",
            analysis.query,
        )
    }

    @Test
    fun `analyzeTrace excludes itself and builds report from similar traces`() {
        analyzerHolder = analyzer(config(llmEnabled = false))
        val stored = trace(errorMessage = "timeout after 30s")
        storage.objects["k1.json"] = stored
        searchResults =
            listOf(
                scored("k1.json", stored, 1.0),
                scored("k2.json", trace(agent = "arbitrator", errorMessage = "veto"), 0.5),
            )

        val analysis = runBlocking { analyzerHolder!!.analyzeTrace("k1.json", k = 3) }

        assertEquals("RULE_BASED", analysis.mode)
        assertEquals(listOf("k2.json"), analysis.retrievedTraces.map { it.key })
        assertEquals(4, searchedK.get(), "k+1 запрашивается, чтобы отфильтровать сам трейс")
    }

    @Test
    fun `analyzeTrace falls back to none marker when trace has no error message`() {
        analyzerHolder = analyzer(config(llmEnabled = false))
        storage.objects["k1.json"] = trace(errorMessage = null)
        searchResults = listOf(scored("k2.json", trace(errorMessage = null)))

        val analysis = runBlocking { analyzerHolder!!.analyzeTrace("k1.json") }

        assertTrue(analysis.query.endsWith("error=none"))
    }

    // ─── status ───────────────────────────────────────────────────────

    @Test
    fun `status exposes config and corpus state`() {
        analyzerHolder = analyzer(config(enabled = true, llmEnabled = false, maxResults = 7))
        searchResults = listOf(scored("a"), scored("b"))

        val status = analyzerHolder!!.status()

        assertEquals(true, status["enabled"])
        assertEquals(2, status["indexSize"])
        assertEquals(false, status["llmEnabled"])
        assertEquals(500, status["corpusLimit"])
        assertNull(status["lastRefresh"])
    }

    /** In-memory [TraceStorage] для analyzeTrace. */
    private class FakeTraceStorage : TraceStorage {
        val objects: MutableMap<String, LlmTrace> = mutableMapOf()

        override suspend fun save(
            trace: LlmTrace,
            key: String?,
        ): String? = null

        override suspend fun list(limit: Int): List<String> = objects.keys.take(limit)

        override suspend fun listByTraceId(
            traceId: String,
            limit: Int,
        ): List<String> = emptyList()

        override suspend fun read(key: String): LlmTrace? = objects[key]
    }
}
