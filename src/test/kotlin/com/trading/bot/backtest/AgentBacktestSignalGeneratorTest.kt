package com.trading.bot.backtest

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import com.trading.bot.model.entity.Candle
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Юнит-тесты агентного генератора сигналов (roadmap 13.8.1).
 *
 * Проверяются: warm-up до minBars, сэмплирование каждые N баров, полная цепочка
 * tech → fund (параллельно) → strategy → contrarian → arbitrator и передача
 * temperature=0.0 + изолированного cacheNamespace в агенты.
 *
 * Стабы — с реальными значениями (не matcher'ами): matcher-стаб на 12-аргументном
 * suspend-методе (arbitrator.adjudicate) не совпадает в этой версии mockito-kotlin,
 * тогда как real-value стабы работают и точнее фиксируют ожидаемую связку аргументов.
 */
class AgentBacktestSignalGeneratorTest {
    private val techAgent = Mockito.mock(TechnicalAnalysisAgent::class.java)
    private val fundAgent = Mockito.mock(FundamentalAnalysisAgent::class.java)
    private val stratAgent = Mockito.mock(StrategyAgent::class.java)
    private val contrAgent = Mockito.mock(ContrarianAgent::class.java)
    private val arbAgent = Mockito.mock(ArbitratorAgent::class.java)

    private val config =
        BacktestAgentConfig().apply {
            sampleEvery = 20
            temperature = 0.0
            cacheNamespace = "backtest"
        }

    private val generator =
        AgentBacktestSignalGenerator(
            techAgent,
            fundAgent,
            stratAgent,
            contrAgent,
            arbAgent,
            config,
            SimpleMeterRegistry(),
        )

    private val cycleId = "cycle-1"
    private val index = 40
    private val minBars = 10
    private val list = (0 until 41).map { i -> candle(100.0 + i * 0.1, i) }
    private val bar = list[index]
    private val snapshot =
        MarketSnapshot(
            ticker = "SBER",
            currentPrice = bar.closePrice,
            volume = bar.volume,
            timestamp = bar.time.atZone(ZoneId.systemDefault()).toInstant(),
        )
    private val window = list.subList(0, index + 1)

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

    private fun candle(
        price: Double,
        i: Int,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(price),
            highPrice = BigDecimal(price * 1.01),
            lowPrice = BigDecimal(price * 0.99),
            closePrice = BigDecimal(price),
            volume = 1000L,
            time = LocalDateTime.of(2026, 1, 5, 10, 0).plusMinutes(10L * i),
        )

    private suspend fun stubChain() {
        whenever(
            techAgent.analyze("SBER", window, snapshot, cycleId, PromptRegistry.DEFAULT_VERSION, 0.0, "backtest"),
        ).thenReturn(tech)
        whenever(
            fundAgent.analyze("SBER", cycleId, PromptRegistry.DEFAULT_VERSION, 0.0, "backtest"),
        ).thenReturn(fund)
        whenever(
            stratAgent.formulate("SBER", tech, fund, snapshot, cycleId, 0.5, PromptRegistry.DEFAULT_VERSION, 0.0, "backtest"),
        ).thenReturn(draft)
        whenever(
            contrAgent.challenge(draft, tech, fund, snapshot, cycleId, PromptRegistry.DEFAULT_VERSION, 0.0, "backtest"),
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
                0.60,
                PromptRegistry.DEFAULT_VERSION,
                false,
                0.0,
                "backtest",
            ),
        ).thenReturn(final)
    }

    @Test
    fun `warm-up bars produce HOLD without agent calls`() {
        val signal = runBlocking { generator.signal("SBER", list, index = 9, minBars = 10, cycleId = "c1") }
        assertEquals(StrategyAction.HOLD, signal)
        Mockito.verifyNoInteractions(techAgent, fundAgent, stratAgent, contrAgent, arbAgent)
    }

    @Test
    fun `bars between samples produce HOLD without agent calls`() {
        val signal = runBlocking { generator.signal("SBER", list, index = 25, minBars = 10, cycleId = "c1") }
        assertEquals(StrategyAction.HOLD, signal)
        Mockito.verifyNoInteractions(techAgent, fundAgent, stratAgent, contrAgent, arbAgent)
    }

    @Test
    fun `sampled bar runs full agent chain and returns final action`() {
        runBlocking { stubChain() }

        val signal = runBlocking { generator.signal("SBER", list, index = index, minBars = minBars, cycleId = cycleId) }
        assertEquals(StrategyAction.BUY, signal)

        runBlocking {
            Mockito.verify(techAgent).analyze("SBER", window, snapshot, cycleId, PromptRegistry.DEFAULT_VERSION, 0.0, "backtest")
            Mockito.verify(fundAgent).analyze("SBER", cycleId, PromptRegistry.DEFAULT_VERSION, 0.0, "backtest")
            Mockito
                .verify(stratAgent)
                .formulate("SBER", tech, fund, snapshot, cycleId, 0.5, PromptRegistry.DEFAULT_VERSION, 0.0, "backtest")
            Mockito
                .verify(contrAgent)
                .challenge(draft, tech, fund, snapshot, cycleId, PromptRegistry.DEFAULT_VERSION, 0.0, "backtest")
            Mockito.verify(arbAgent).adjudicate(
                draft,
                challenge,
                tech,
                fund,
                snapshot,
                cycleId,
                null,
                0.60,
                PromptRegistry.DEFAULT_VERSION,
                false,
                0.0,
                "backtest",
            )
        }
    }
}
