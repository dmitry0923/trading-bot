package com.trading.bot.application.advisor

import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TraceStorageConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.advisor.AdvisorVerdictType
import com.trading.bot.domain.risk.MarketEvent
import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDirection
import com.trading.bot.domain.risk.RegimeLiquidity
import com.trading.bot.domain.risk.RegimeVolatility
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Instant

/**
 * [LlmAdvisor] — жёсткий дедлайн `advisorBudgetMs` (fail-open: NEUTRAL без
 * поправки) и полный контекст (режим + индикаторы + contextPrompt) в промпте.
 */
class LlmAdvisorTimeoutBranchesTest {
    private val objectMapper = jacksonObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()
    private val promptRegistry = Mockito.mock(PromptRegistry::class.java)
    private val semanticCache = Mockito.mock(com.trading.bot.infrastructure.llm.SemanticCache::class.java)
    private val agentLogRepository = Mockito.mock(com.trading.bot.repository.AgentLogRepository::class.java)

    /** Медленный LLM: задержка заведомо больше бюджета советника. */
    private class SlowLlmClient :
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
        override suspend fun complete(
            agent: String,
            ticker: String,
            prompt: PromptTemplate,
            variables: Map<String, Any>,
            fingerprint: String?,
            temperature: Double,
            cacheNamespace: String?,
        ): LlmResponse {
            delay(5_000)
            return LlmResponse(content = """{"verdict":"AGREE","riskLevel":"LOW","confidenceAdjustment":0.1}""")
        }
    }

    private fun advisor(
        client: ResilientLlmClient,
        budgetMs: Long = 50,
    ): LlmAdvisor =
        LlmAdvisor(
            client,
            promptRegistry,
            semanticCache,
            agentLogRepository,
            meterRegistry,
            objectMapper,
            TradingConfig().apply { advisorBudgetMs = budgetMs },
        )

    private val template =
        PromptTemplate(
            name = "advisor",
            version = PromptRegistry.DEFAULT_VERSION,
            system = "system",
            userTemplate = "user {{ticker}}",
        )

    private val decision =
        StrategyDecision(
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("100"),
            signalStrength = 0.7,
            reasoning = "signal",
        )

    private fun fullContext(): StrategyContext =
        StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(ticker = "SBER", currentPrice = BigDecimal("100"), timestamp = Instant.now()),
            candles = emptyList(),
            indicators =
                IndicatorCalculator.Indicators(
                    rsi = 55.0,
                    atr = 1.0,
                    macdLine = 0.5,
                    macdSignal = 0.4,
                    macdHistogram = 0.1,
                    bbUpper = BigDecimal("101"),
                    bbMiddle = BigDecimal("100"),
                    bbLower = BigDecimal("99"),
                    trend = "UP",
                    conclusion = "BULLISH",
                ),
            cycleId = "cycle-1",
            contextPrompt = "macro context",
            regime =
                PerTickerRegime(
                    direction = RegimeDirection.TREND_UP,
                    volatility = RegimeVolatility.NORMAL,
                    liquidity = RegimeLiquidity.NORMAL,
                    event = MarketEvent.NONE,
                ),
        )

    @Test
    fun `llm exceeding the advisor budget yields a neutral fallback`(): Unit =
        runBlocking {
            Mockito.`when`(promptRegistry.getTemplate("advisor", PromptRegistry.DEFAULT_VERSION)).thenReturn(template)
            val verdict = advisor(SlowLlmClient(), budgetMs = 50).advise(fullContext(), decision, 0.6)

            assertEquals(AdvisorVerdictType.NEUTRAL, verdict.verdict)
            assertTrue(verdict.explanation.contains("ADVISOR_TIMEOUT"), verdict.explanation)
            assertFalse(verdict.blocksEntry)
            assertEquals(1.0, meterRegistry.counter("advisor.timeout", "ticker", "SBER").count(), 1e-9)
        }

    @Test
    fun `full context reaches the advisor prompt and verdict`(): Unit =
        runBlocking {
            Mockito.`when`(promptRegistry.getTemplate("advisor", PromptRegistry.DEFAULT_VERSION)).thenReturn(template)
            val client: ResilientLlmClient =
                Mockito.mock(ResilientLlmClient::class.java) { invocation ->
                    if (invocation.method.name == "complete") {
                        LlmResponse(content = """{"verdict":"AGREE","riskLevel":"NORMAL","confidenceAdjustment":0.05}""")
                    } else {
                        Mockito.RETURNS_DEFAULTS.answer(invocation)
                    }
                }

            val verdict = advisor(client, budgetMs = 1_000).advise(fullContext(), decision, 0.6)

            assertEquals(AdvisorVerdictType.AGREE, verdict.verdict)
            assertEquals(0.05, verdict.confidenceAdjustment, 1e-9)
        }
}
