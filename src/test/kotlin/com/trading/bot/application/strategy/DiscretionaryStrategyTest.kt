package com.trading.bot.application.strategy

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.config.LlmConfig
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.infrastructure.llm.DeltaPromptStore
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import com.trading.bot.model.entity.Candle
import com.trading.bot.service.AdaptiveRiskService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Юнит-тесты дискреционной (LLM) стратегии (roadmap 13.8).
 *
 * Проверяется: полная цепочка tech+fund → strategy → contrarian → arbitrator,
 * ПАРАЛЛЕЛЬНОСТЬ независимых вызовов (tech/fund/adaptive-порог), и дельта-промпты:
 *  - при llm.delta-prompts-enabled=true дельты берутся из DeltaPromptStore,
 *    передаются стратегу/контрариану и состояние обновляется после цикла;
 *  - при выключенной фиче — полный reasoning (techDelta=null) без обращений к хранилищу.
 */
class DiscretionaryStrategyTest {
    private val techAgent = Mockito.mock(TechnicalAnalysisAgent::class.java)
    private val fundAgent = Mockito.mock(FundamentalAnalysisAgent::class.java)
    private val stratAgent = Mockito.mock(StrategyAgent::class.java)
    private val contrAgent = Mockito.mock(ContrarianAgent::class.java)
    private val arbAgent = Mockito.mock(ArbitratorAgent::class.java)
    private val adaptiveRisk = Mockito.mock(AdaptiveRiskService::class.java)
    private val deltaStore = Mockito.mock(DeltaPromptStore::class.java)
    private val llmConfig = LlmConfig()
    private val meterRegistry = SimpleMeterRegistry()

    private val strategy =
        DiscretionaryStrategy(
            techAgent,
            fundAgent,
            stratAgent,
            contrAgent,
            arbAgent,
            adaptiveRisk,
            deltaStore,
            llmConfig,
            meterRegistry,
        )

    private val candles = listOf(candle())
    private val snapshot =
        MarketSnapshot(
            ticker = "SBER",
            currentPrice = BigDecimal("100"),
            timestamp = LocalDateTime.of(2026, 1, 5, 10, 0).atZone(ZoneId.systemDefault()).toInstant(),
        )
    private val context =
        StrategyContext(
            ticker = "SBER",
            snapshot = snapshot,
            candles = candles,
            indicators = null,
            cycleId = "cycle-1",
            contextPrompt = null,
        )

    private val tech =
        TechnicalReport(
            trend = "UP",
            rsi = 45.0,
            atr = 1.0,
            conclusion = "BULLISH",
            confidence = 0.7,
            reasoning = "uptrend",
        )

    private val fund = FundamentalReport(conclusion = "NEUTRAL", confidence = 0.5, reasoning = "macro ok")

    private val draft = StrategyAgent.Draft(StrategyAction.BUY, BigDecimal("102"), 0.65, "buy")

    private val challenge =
        ContrarianAgent.ChallengeReport(
            isValid = true,
            riskLevel = "LOW",
            critique = "looks fine",
            confidence = 0.8,
        )

    private val final =
        ArbitratorAgent.Final(StrategyAction.BUY, BigDecimal("102"), 0.7, "go long", null)

    private fun candle(): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal("100"),
            highPrice = BigDecimal("101"),
            lowPrice = BigDecimal("99"),
            closePrice = BigDecimal("100"),
            volume = 1000L,
            time = LocalDateTime.of(2026, 1, 5, 10, 0),
        )

    private suspend fun stubChain() {
        whenever(
            techAgent.analyze("SBER", candles, snapshot, "cycle-1"),
        ).thenReturn(tech)
        whenever(
            fundAgent.analyze("SBER", "cycle-1"),
        ).thenReturn(fund)
        whenever(
            adaptiveRisk.getAdaptiveConfidenceThreshold("SBER"),
        ).thenReturn(0.5)
        whenever(
            stratAgent.formulate("SBER", tech, fund, snapshot, "cycle-1", 0.5, PromptRegistry.DEFAULT_VERSION, 0.15, null, null, null),
        ).thenReturn(draft)
        whenever(
            contrAgent.challenge(draft, tech, fund, snapshot, "cycle-1", PromptRegistry.DEFAULT_VERSION, 0.1, null, null),
        ).thenReturn(challenge)
        whenever(
            arbAgent.adjudicate(
                draft,
                challenge,
                tech,
                fund,
                snapshot,
                "cycle-1",
                null,
                0.5,
                PromptRegistry.DEFAULT_VERSION,
                false,
                0.1,
                null,
            ),
        ).thenReturn(final)
    }

    @Test
    fun `evaluate runs the full chain and returns the final decision`() {
        runBlocking { stubChain() }

        val decision = runBlocking { strategy.evaluate(context) }

        assertEquals(StrategyAction.BUY, decision.action)
        assertEquals(BigDecimal("102"), decision.targetPrice)
        assertEquals(0.7, decision.confidence)

        runBlocking {
            Mockito.verify(techAgent).analyze("SBER", candles, snapshot, "cycle-1")
            Mockito.verify(fundAgent).analyze("SBER", "cycle-1")
            Mockito.verify(adaptiveRisk).getAdaptiveConfidenceThreshold("SBER")
            Mockito.verify(stratAgent).formulate(
                "SBER",
                tech,
                fund,
                snapshot,
                "cycle-1",
                0.5,
                PromptRegistry.DEFAULT_VERSION,
                0.15,
                null,
                null,
                null,
            )
            Mockito.verify(contrAgent).challenge(draft, tech, fund, snapshot, "cycle-1", PromptRegistry.DEFAULT_VERSION, 0.1, null, null)
            Mockito.verify(arbAgent).adjudicate(
                draft,
                challenge,
                tech,
                fund,
                snapshot,
                "cycle-1",
                null,
                0.5,
                PromptRegistry.DEFAULT_VERSION,
                false,
                0.1,
                null,
            )
        }
    }

    @Test
    fun `tech fund and adaptive threshold run in parallel`() {
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val started = AtomicInteger(0)

        runBlocking {
            whenever(
                techAgent.analyze("SBER", candles, snapshot, "cycle-1"),
            ).thenAnswer {
                started.incrementAndGet()
                val now = active.incrementAndGet()
                maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(100)
                active.decrementAndGet()
                tech
            }
            whenever(
                fundAgent.analyze("SBER", "cycle-1"),
            ).thenAnswer {
                started.incrementAndGet()
                val now = active.incrementAndGet()
                maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(100)
                active.decrementAndGet()
                fund
            }
            whenever(
                adaptiveRisk.getAdaptiveConfidenceThreshold("SBER"),
            ).thenAnswer {
                started.incrementAndGet()
                val now = active.incrementAndGet()
                maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(100)
                active.decrementAndGet()
                0.5
            }
            whenever(
                stratAgent.formulate("SBER", tech, fund, snapshot, "cycle-1", 0.5, PromptRegistry.DEFAULT_VERSION, 0.15, null, null, null),
            ).thenReturn(draft)
            whenever(
                contrAgent.challenge(draft, tech, fund, snapshot, "cycle-1", PromptRegistry.DEFAULT_VERSION, 0.1, null, null),
            ).thenReturn(challenge)
            whenever(
                arbAgent.adjudicate(
                    draft,
                    challenge,
                    tech,
                    fund,
                    snapshot,
                    "cycle-1",
                    null,
                    0.5,
                    PromptRegistry.DEFAULT_VERSION,
                    false,
                    0.1,
                    null,
                ),
            ).thenReturn(final)
        }

        // Dispatchers.Default: async внутри цепочки наследует многопоточный диспетчер.
        // В runBlocking без диспетчера все coroutine выполнялись бы на одном потоке,
        // и Thread.sleep в thenAnswer сериализовал бы вызовы.
        runBlocking(Dispatchers.Default) { strategy.evaluate(context) }

        assertEquals(3, started.get())
        // Последовательно было бы 1: каждый вызов стартует и завершается до начала следующего.
        assertTrue(maxConcurrent.get() >= 2, "ожидалась параллельность, maxConcurrent=${maxConcurrent.get()}")
    }

    @Test
    fun `delta prompts passed to strategy and contrarian when enabled`() {
        llmConfig.deltaPromptsEnabled = true
        runBlocking {
            whenever(
                techAgent.analyze("SBER", candles, snapshot, "cycle-1"),
            ).thenReturn(tech)
            whenever(
                fundAgent.analyze("SBER", "cycle-1"),
            ).thenReturn(fund)
            whenever(
                adaptiveRisk.getAdaptiveConfidenceThreshold("SBER"),
            ).thenReturn(0.5)
            whenever(
                deltaStore.techDelta("SBER", tech),
            ).thenReturn("rsi: 45.00→46.20")
            whenever(
                deltaStore.fundDelta("SBER", fund),
            ).thenReturn("NO_CHANGE")
            whenever(
                stratAgent.formulate(
                    "SBER",
                    tech,
                    fund,
                    snapshot,
                    "cycle-1",
                    0.5,
                    PromptRegistry.DEFAULT_VERSION,
                    0.15,
                    null,
                    "rsi: 45.00→46.20",
                    "NO_CHANGE",
                ),
            ).thenReturn(draft)
            whenever(
                contrAgent.challenge(draft, tech, fund, snapshot, "cycle-1", PromptRegistry.DEFAULT_VERSION, 0.1, null, "rsi: 45.00→46.20"),
            ).thenReturn(challenge)
            whenever(
                arbAgent.adjudicate(
                    draft,
                    challenge,
                    tech,
                    fund,
                    snapshot,
                    "cycle-1",
                    null,
                    0.5,
                    PromptRegistry.DEFAULT_VERSION,
                    false,
                    0.1,
                    null,
                ),
            ).thenReturn(final)
        }

        runBlocking { strategy.evaluate(context) }

        runBlocking {
            Mockito.verify(stratAgent).formulate(
                "SBER",
                tech,
                fund,
                snapshot,
                "cycle-1",
                0.5,
                PromptRegistry.DEFAULT_VERSION,
                0.15,
                null,
                "rsi: 45.00→46.20",
                "NO_CHANGE",
            )
            Mockito.verify(contrAgent).challenge(
                draft,
                tech,
                fund,
                snapshot,
                "cycle-1",
                PromptRegistry.DEFAULT_VERSION,
                0.1,
                null,
                "rsi: 45.00→46.20",
            )
            Mockito.verify(deltaStore).update("SBER", tech, fund)
        }
        assertEquals(1.0, meterRegistry.counter("agent.delta.prompts", "agent", "discretionary-chain", "mode", "DELTA").count())
    }

    @Test
    fun `full reasoning used when delta prompts disabled`() {
        llmConfig.deltaPromptsEnabled = false

        runBlocking { stubChain() }

        runBlocking { strategy.evaluate(context) }

        runBlocking {
            Mockito.verify(stratAgent).formulate(
                "SBER",
                tech,
                fund,
                snapshot,
                "cycle-1",
                0.5,
                PromptRegistry.DEFAULT_VERSION,
                0.15,
                null,
                null,
                null,
            )
            Mockito.verify(contrAgent).challenge(draft, tech, fund, snapshot, "cycle-1", PromptRegistry.DEFAULT_VERSION, 0.1, null, null)
        }
        Mockito.verify(deltaStore, never()).techDelta("SBER", tech)
        Mockito.verify(deltaStore, never()).fundDelta("SBER", fund)
        Mockito.verify(deltaStore, never()).update("SBER", tech, fund)
        assertEquals(0.0, meterRegistry.counter("agent.delta.prompts", "agent", "discretionary-chain", "mode", "FULL").count())
    }
}
