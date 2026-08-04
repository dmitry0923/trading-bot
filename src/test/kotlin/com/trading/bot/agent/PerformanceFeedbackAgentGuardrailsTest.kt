package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.StrategyAdjustmentRepository
import com.trading.bot.service.RedisCacheService
import com.trading.bot.service.TradeAnalysisService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * Guardrails для параметров PerformanceFeedbackAgent: LLM-ответы не могут
 * выйти за жёсткие рамки (confidence/SL/TP adjustments), NaN и Infinity
 * приравниваются к 0.
 */
class PerformanceFeedbackAgentGuardrailsTest {
    private val llmClient = Mockito.mock(ResilientLlmClient::class.java)
    private val tradeAnalysis = Mockito.mock(TradeAnalysisService::class.java)
    private val agentLogRepo = Mockito.mock(AgentLogRepository::class.java)
    private val adjustmentRepo = Mockito.mock(StrategyAdjustmentRepository::class.java)
    private val redisCache = Mockito.mock(RedisCacheService::class.java)
    private val promptRegistry = Mockito.mock(PromptRegistry::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val agent =
        PerformanceFeedbackAgent(
            llmClient,
            tradeAnalysis,
            agentLogRepo,
            adjustmentRepo,
            redisCache,
            promptRegistry,
            meterRegistry,
            objectMapper,
        )

    @Test
    fun `confidence adjustment is clamped to plus-minus 0-20`() {
        assertEquals(
            PerformanceFeedbackAgent.CONFIDENCE_ADJUSTMENT_MAX,
            agent.clamp(0.99, PerformanceFeedbackAgent.CONFIDENCE_ADJUSTMENT_MIN, PerformanceFeedbackAgent.CONFIDENCE_ADJUSTMENT_MAX),
        )
        assertEquals(
            PerformanceFeedbackAgent.CONFIDENCE_ADJUSTMENT_MIN,
            agent.clamp(-5.0, PerformanceFeedbackAgent.CONFIDENCE_ADJUSTMENT_MIN, PerformanceFeedbackAgent.CONFIDENCE_ADJUSTMENT_MAX),
        )
    }

    @Test
    fun `sl and tp adjustments are clamped to plus-minus 0-30`() {
        assertEquals(
            PerformanceFeedbackAgent.SL_ADJUSTMENT_PERCENT_MAX,
            agent.clamp(3.0, PerformanceFeedbackAgent.SL_ADJUSTMENT_PERCENT_MIN, PerformanceFeedbackAgent.SL_ADJUSTMENT_PERCENT_MAX),
        )
        assertEquals(
            PerformanceFeedbackAgent.TP_ADJUSTMENT_PERCENT_MIN,
            agent.clamp(-1.0, PerformanceFeedbackAgent.TP_ADJUSTMENT_PERCENT_MIN, PerformanceFeedbackAgent.TP_ADJUSTMENT_PERCENT_MAX),
        )
    }

    @Test
    fun `nan and infinity collapse to zero`() {
        assertEquals(0.0, agent.clamp(Double.NaN, -1.0, 1.0))
        assertEquals(0.0, agent.clamp(Double.POSITIVE_INFINITY, -1.0, 1.0))
        assertEquals(0.0, agent.clamp(Double.NEGATIVE_INFINITY, -1.0, 1.0))
    }

    @Test
    fun `values within bounds are unchanged`() {
        assertEquals(0.1, agent.clamp(0.1, -0.2, 0.2))
        assertEquals(0.0, agent.clamp(0.0, -0.3, 0.3))
        assertTrue(agent.clamp(0.25, -0.3, 0.3) > 0.0)
    }
}
