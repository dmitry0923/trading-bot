package com.trading.bot.agent

import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TraceStorageConfig
import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.model.dto.TradeStats
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.StrategyAdjustmentRepository
import com.trading.bot.service.RedisCacheService
import com.trading.bot.service.TradeAnalysisService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * P1 (roadmap 13.17): PerformanceFeedbackAgent — feedback-парсинг LLM-ответа
 * с клампингом границ, rule-based fallback (мало сделок, отсутствие LLM,
 * битый JSON, isFallback), кэш и сохранение корректировок.
 *
 * LLM-клиент заменяется реальным стабом (subclass ResilientLlmClient),
 * т.к. matcher-стабы suspend-методов не совпадают в mockito-kotlin
 * (см. AgentResponseParsingTest).
 */
class PerformanceFeedbackAgentTest {
    private val objectMapper = jacksonObjectMapper()
    private val tradeAnalysis = Mockito.mock(TradeAnalysisService::class.java)
    private val agentLogRepo = Mockito.mock(AgentLogRepository::class.java)
    private val adjustmentRepo = Mockito.mock(StrategyAdjustmentRepository::class.java)
    private val redisCache = Mockito.mock(RedisCacheService::class.java)
    private val promptRegistry = Mockito.mock(PromptRegistry::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val stub = StubLlmClient()

    private val agent =
        PerformanceFeedbackAgent(
            stub,
            tradeAnalysis,
            agentLogRepo,
            adjustmentRepo,
            redisCache,
            promptRegistry,
            meterRegistry,
            objectMapper,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(tradeAnalysis, agentLogRepo, adjustmentRepo, redisCache, promptRegistry)
        stub.calls = 0
        stub.failOnCall = false
        stub.responses.clear()
    }

    @Test
    fun `rule based feedback defaults when stats missing`() {
        val fb = agent.ruleBasedFeedback("SBER", null)

        assertFalse(fb.shouldPauseTrading)
        assertEquals(0.0, fb.confidenceAdjustment)
        assertEquals(0.0, fb.slAdjustmentPercent)
        assertEquals(0.0, fb.tpAdjustmentPercent)
        assertTrue(fb.agentSpecificNotes.getValue("reason").contains("RULE_BASED"))
    }

    @Test
    fun `rule based feedback pauses after 3 consecutive losses`() {
        val fb = agent.ruleBasedFeedback("SBER", stats(totalTrades = 10, winRate = 0.5, maxConsecutiveLosses = 3))

        assertTrue(fb.shouldPauseTrading)
    }

    @Test
    fun `rule based feedback raises confidence threshold on low win rate`() {
        val fb = agent.ruleBasedFeedback("SBER", stats(totalTrades = 10, winRate = 0.3))

        assertEquals(0.15, fb.confidenceAdjustment)
    }

    @Test
    fun `rule based feedback widens stop when sl hit rate is high`() {
        val fb = agent.ruleBasedFeedback("SBER", stats(totalTrades = 10, winRate = 0.6, slHitRate = 0.7))

        assertEquals(0.20, fb.slAdjustmentPercent)
    }

    @Test
    fun `generateFeedback is rule based without llm when too few trades`() {
        runBlocking { Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(mapOf("SBER" to stats(totalTrades = 4))) }

        val fb = runBlocking { agent.generateFeedback("SBER") }

        assertEquals(0, stub.calls, "LLM не должен вызываться при < 5 сделках")
        assertTrue(fb.agentSpecificNotes.getValue("reason").contains("RULE_BASED"))
        assertTrue(ruleBasedCount("LOW_TRADES") > 0)
        runBlocking { verify(agentLogRepo, never()).save(any()) }
    }

    @Test
    fun `generateFeedback is rule based without llm when no stats at all`() {
        runBlocking { Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(emptyMap()) }

        val fb = runBlocking { agent.generateFeedback("SBER") }

        assertEquals(0, stub.calls)
        assertTrue(fb.agentSpecificNotes.getValue("reason").contains("RULE_BASED"))
    }

    @Test
    fun `generateFeedback parses llm response and clamps out of bounds values`() {
        stub.responses +=
            LlmResponse(
                content =
                    """{"confidenceAdjustment":0.99,"slAdjustmentPercent":-1.0,"tpAdjustmentPercent":0.1,"contextPrompt":"ctx","shouldPauseTrading":true}""",
            )
        runBlocking { Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(mapOf("SBER" to stats())) }

        val fb = runBlocking { agent.generateFeedback("SBER") }

        assertEquals(1, stub.calls)
        assertEquals(0.20, fb.confidenceAdjustment, "усечение до CONFIDENCE_ADJUSTMENT_MAX")
        assertEquals(-0.30, fb.slAdjustmentPercent, "усечение до SL_ADJUSTMENT_PERCENT_MIN")
        assertEquals(0.10, fb.tpAdjustmentPercent)
        assertEquals("ctx", fb.contextPrompt)
        assertTrue(fb.shouldPauseTrading)
        verify(redisCache).saveFeedback(any(), any(), any())
        runBlocking { verify(agentLogRepo).save(any()) }
    }

    @Test
    fun `generateFeedback saves only non-zero adjustments`() {
        stub.responses +=
            LlmResponse(
                content =
                    """{"confidenceAdjustment":0.05,"slAdjustmentPercent":0.0,"tpAdjustmentPercent":0.0,"contextPrompt":"","shouldPauseTrading":false}""",
            )
        runBlocking { Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(mapOf("SBER" to stats())) }

        runBlocking { agent.generateFeedback("SBER") }

        runBlocking { verify(adjustmentRepo, times(1)).save(any()) }
    }

    @Test
    fun `generateFeedback falls back to rule based when llm throws`() {
        stub.failOnCall = true
        runBlocking { Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(mapOf("SBER" to stats())) }

        val fb = runBlocking { agent.generateFeedback("SBER") }

        assertEquals(1, stub.calls)
        assertTrue(fb.agentSpecificNotes.getValue("reason").contains("RULE_BASED"))
        assertTrue(meterRegistry.counter("feedback.llm.error", "ticker", "SBER").count() > 0)
        runBlocking { verify(agentLogRepo).save(any()) }
    }

    @Test
    fun `generateFeedback falls back to rule based when llm returns fallback`() {
        stub.responses += LlmResponse.fallback("NO_API_KEY")
        runBlocking { Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(mapOf("SBER" to stats())) }

        val fb = runBlocking { agent.generateFeedback("SBER") }

        assertTrue(fb.agentSpecificNotes.getValue("reason").contains("RULE_BASED"))
    }

    @Test
    fun `generateFeedback falls back to rule based on unparseable llm content`() {
        stub.responses += LlmResponse(content = "this is not json")
        runBlocking { Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(mapOf("SBER" to stats())) }

        val fb = runBlocking { agent.generateFeedback("SBER") }

        assertTrue(fb.agentSpecificNotes.getValue("reason").contains("RULE_BASED"))
        runBlocking { verify(adjustmentRepo, never()).save(any()) }
    }

    @Test
    fun `generateFeedback uses cached feedback without calling llm or saving adjustments`() {
        runBlocking { Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(mapOf("SBER" to stats())) }
        whenever(redisCache.getFeedback(any(), any())).thenReturn(
            """{"confidenceAdjustment":0.1,"slAdjustmentPercent":0.05,"tpAdjustmentPercent":0.0,"contextPrompt":"cached","shouldPauseTrading":false}""",
        )

        val fb = runBlocking { agent.generateFeedback("SBER") }

        assertEquals(0, stub.calls, "кэш-хит не должен звать LLM")
        assertEquals(0.1, fb.confidenceAdjustment)
        assertEquals(0.05, fb.slAdjustmentPercent)
        assertEquals("cached", fb.contextPrompt)
        runBlocking { verify(adjustmentRepo, never()).save(any()) }
        assertTrue(meterRegistry.counter("feedback.cache.hit", "ticker", "SBER").count() > 0)
    }

    private fun ruleBasedCount(reason: String): Double =
        meterRegistry
            .find("feedback.rule_based")
            .tag("reason", reason)
            .counter()
            ?.count()
            ?: 0.0

    private fun stats(
        totalTrades: Int = 10,
        winRate: Double = 0.5,
        slHitRate: Double = 0.2,
        maxConsecutiveLosses: Int = 0,
    ): TradeStats =
        TradeStats(
            ticker = "SBER",
            totalTrades = totalTrades,
            winningTrades = (totalTrades * winRate).toInt(),
            losingTrades = totalTrades - (totalTrades * winRate).toInt(),
            winRate = winRate,
            avgWin = BigDecimal("1000"),
            avgLoss = BigDecimal("500"),
            profitFactor = 2.0,
            maxConsecutiveLosses = maxConsecutiveLosses,
            avgHoldTimeMinutes = 120,
            slHitRate = slHitRate,
            tpHitRate = 0.3,
            strategyCloseRate = 0.5,
            bestEntryHour = 14,
            worstEntryHour = 9,
            blindSpots = emptyList(),
        )

    private class StubLlmClient :
        ResilientLlmClient(
            llmConfig = LlmConfig(),
            semanticCache = mock(),
            objectMapper = jacksonObjectMapper(),
            meterRegistry = SimpleMeterRegistry(),
            circuitBreakerRegistry = mock(),
            rateLimiterRegistry = mock(),
            retryRegistry = mock(),
            settingsService = mock(),
            traceStorage = mock(),
            traceStorageConfig = TraceStorageConfig(),
        ) {
        var calls: Int = 0
        var failOnCall: Boolean = false
        val responses: ArrayDeque<LlmResponse> = ArrayDeque()

        override suspend fun complete(
            agent: String,
            ticker: String,
            prompt: PromptTemplate,
            variables: Map<String, Any>,
            fingerprint: String?,
            temperature: Double,
            cacheNamespace: String?,
        ): LlmResponse {
            calls++
            if (failOnCall) throw RuntimeException("llm down")
            return responses.removeFirst()
        }
    }
}
