package com.trading.bot.application.strategy

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.infrastructure.llm.DeltaPromptStore
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import com.trading.bot.service.AdaptiveRiskService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * LlmSignalStrategy: стратегия с LLM как источником сигнала (research/llm-signal-source).
 *
 * Покрытие:
 *  - HOLD при отключённом флаге llm-signal-source (fail-closed) + метрика llm.signal.disabled;
 *  - при включённом флаге — полная цепочка (tech+fund -> strategist -> contrarian ->
 *    arbitrator), результат проходит как StrategyDecision(action, targetPrice, signalStrength,
 *    reasoning) БЕЗ риск-полей (qty/SL/TP назначает только общий pipeline ниже — этап 3, R1);
 *  - R2: превышение [TradingConfig.llmSignalBudgetMs] -> fail-closed HOLD + метрика
 *    llm.signal.timeout (LLM не задерживает order-execution);
 *  - вся цепочка на реальных значениях (не matcher'ах): matcher-стаб на многоаргументном
 *    suspend-методе не совпадает в этой версии mockito-kotlin (см. AgentBacktestSignalGeneratorTest).
 */
class LlmSignalStrategyTest {
    private val techAgent = Mockito.mock(TechnicalAnalysisAgent::class.java)
    private val fundAgent = Mockito.mock(FundamentalAnalysisAgent::class.java)
    private val stratAgent = Mockito.mock(StrategyAgent::class.java)
    private val contrAgent = Mockito.mock(ContrarianAgent::class.java)
    private val arbAgent = Mockito.mock(ArbitratorAgent::class.java)
    private val adaptiveRisk = Mockito.mock(AdaptiveRiskService::class.java)
    private val deltaStore = Mockito.mock(DeltaPromptStore::class.java)

    private val candles = emptyList<com.trading.bot.model.entity.Candle>()
    private val snapshot = MarketSnapshot(ticker = "SBER", currentPrice = BigDecimal("100.0"))
    private val cycleId = "cycle-1"
    private val context =
        StrategyContext(
            ticker = "SBER",
            snapshot = snapshot,
            candles = candles,
            indicators = null,
            cycleId = cycleId,
        )

    private val tech =
        TechnicalReport(trend = "UP", rsi = 45.0, atr = 1.0, conclusion = "BULLISH", signalStrength = 0.7, reasoning = "uptrend")
    private val fund = FundamentalReport(conclusion = "NEUTRAL", signalStrength = 0.5, reasoning = "macro ok")
    private val draft = StrategyAgent.Draft(StrategyAction.BUY, BigDecimal("102"), 0.65, "buy")
    private val challenge =
        ContrarianAgent.ChallengeReport(
            isValid = true,
            riskLevel = "LOW",
            critique = "looks fine",
            signalStrength = 0.8,
        )
    private val final = ArbitratorAgent.Final(StrategyAction.BUY, BigDecimal("102"), 0.7, "go long", null)

    private fun strategy(
        tradingConfig: TradingConfig = TradingConfig(),
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ) = LlmSignalStrategy(
        techAgent = techAgent,
        fundAgent = fundAgent,
        stratAgent = stratAgent,
        contrAgent = contrAgent,
        arbAgent = arbAgent,
        adaptiveRisk = adaptiveRisk,
        deltaStore = deltaStore,
        llmConfig = LlmConfig(),
        tradingConfig = tradingConfig,
        meterRegistry = registry,
    )

    private suspend fun stubChain(adaptive: Double = 0.6) {
        whenever(adaptiveRisk.getAdaptiveConfidenceThreshold("SBER")).thenReturn(adaptive)
        whenever(techAgent.analyze("SBER", candles, snapshot, cycleId)).thenReturn(tech)
        whenever(fundAgent.analyze("SBER", cycleId)).thenReturn(fund)
        whenever(
            stratAgent.formulate("SBER", tech, fund, snapshot, cycleId, adaptive, PromptRegistry.DEFAULT_VERSION, 0.15, null, null, null),
        ).thenReturn(draft)
        whenever(
            contrAgent.challenge(draft, tech, fund, snapshot, cycleId, PromptRegistry.DEFAULT_VERSION, 0.1, null, null),
        ).thenReturn(challenge)
        whenever(
            arbAgent.adjudicate(
                draft,
                challenge,
                tech,
                fund,
                snapshot,
                cycleId,
                null,
                adaptive,
                PromptRegistry.DEFAULT_VERSION,
                false,
                0.1,
                null,
            ),
        ).thenReturn(final)
    }

    @Test
    fun `evaluate returns HOLD when llm signal source is disabled`() =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val decision = strategy(registry = registry).evaluate(context)

            assertEquals(StrategyAction.HOLD, decision.action)
            assertEquals(0.0, decision.signalStrength)
            assertEquals(
                1.0,
                registry
                    .counter("llm.signal.disabled", "ticker", "SBER")
                    .count(),
            )
        }

    @Test
    fun `id matches expected constant`() {
        assertEquals("LLM_SIGNAL", LlmSignalStrategy.ID)
    }

    @Test
    fun `evaluate delegates to full llm chain and passes only direction and target through`() =
        runBlocking {
            stubChain()
            val cfg = TradingConfig().apply { llmSignalSourceEnabled = true }
            val decision = strategy(tradingConfig = cfg).evaluate(context)

            // Риск-паритет (этап 3, R1): стратегия передаёт только action/targetPrice/
            // signalStrength/reasoning; qty/SL/TP назначаются ниже в общем риск-пайплайне
            // (DecisionEngine/PositionSizer) для ЛЮБОГО победителя. У StrategyDecision нет
            // риск-полей по типу, а signalStrength == решению арбитра (не усилено).
            assertEquals(StrategyAction.BUY, decision.action)
            assertEquals(BigDecimal("102"), decision.targetPrice)
            assertEquals(0.7, decision.signalStrength)
            assertEquals(final.reasoning, decision.reasoning)

            runBlocking {
                Mockito
                    .verify(arbAgent)
                    .adjudicate(
                        draft,
                        challenge,
                        tech,
                        fund,
                        snapshot,
                        cycleId,
                        null,
                        0.6,
                        PromptRegistry.DEFAULT_VERSION,
                        false,
                        0.1,
                        null,
                    )
            }
        }

    @Test
    fun `evaluate fails closed with HOLD when chain exceeds budget`() =
        runBlocking {
            stubChain()
            val cfg =
                TradingConfig().apply {
                    llmSignalSourceEnabled = true
                    llmSignalBudgetMs = 30
                }
            val registry = SimpleMeterRegistry()

            // Тех-агент "висит" дольше бюджета: ответ приходит позже дедлайна -> withTimeout
            // отменяет цепочку, стратегия возвращает fail-closed HOLD (R2), а не висит в цикле.
            whenever(techAgent.analyze("SBER", candles, snapshot, cycleId)).thenAnswer {
                Thread.sleep(800)
                tech
            }

            val decision = strategy(tradingConfig = cfg, registry = registry).evaluate(context)

            assertEquals(StrategyAction.HOLD, decision.action)
            assertEquals(
                1.0,
                registry
                    .counter("llm.signal.timeout", "ticker", "SBER")
                    .count(),
            )
            assertTrue(decision.reasoning.contains("budget"))
        }
}
