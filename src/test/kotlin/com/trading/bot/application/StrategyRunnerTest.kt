package com.trading.bot.application

import com.trading.bot.application.strategy.LlmSignalStrategy
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.risk.MarketEvent
import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDirection
import com.trading.bot.domain.risk.RegimeLiquidity
import com.trading.bot.domain.risk.RegimeVolatility
import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class StrategyRunnerTest {
    private class FakeStrategy(
        override val id: String,
        private val decision: StrategyDecision,
    ) : Strategy {
        override suspend fun evaluate(context: StrategyContext): StrategyDecision = decision
    }

    private class FailingStrategy(
        override val id: String = "FAILING",
    ) : Strategy {
        override suspend fun evaluate(context: StrategyContext): StrategyDecision = error("boom")
    }

    private val context =
        StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(ticker = "SBER", currentPrice = BigDecimal("100.0")),
            candles = emptyList(),
            indicators = null,
            cycleId = "cycle-1",
        )

    private fun runner(
        strategies: List<Strategy>,
        tradingConfig: TradingConfig = TradingConfig(),
    ): StrategyRunner =
        StrategyRunner(
            strategies = strategies,
            strategySelector = StrategySelector(),
            meterRegistry = SimpleMeterRegistry(),
            tradingConfig = tradingConfig,
        )

    private fun regime(
        direction: RegimeDirection,
        volatility: RegimeVolatility = RegimeVolatility.NORMAL,
        liquidity: RegimeLiquidity = RegimeLiquidity.NORMAL,
        event: MarketEvent = MarketEvent.NONE,
    ) = PerTickerRegime(direction, volatility, liquidity, event)

    @Test
    fun `winner is the strategy with maximum signalStrength`() {
        val runner =
            runner(
                listOf(
                    FakeStrategy("LOW", StrategyDecision(StrategyAction.HOLD, BigDecimal("100.0"), 0.3, "low")),
                    FakeStrategy("HIGH", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "high")),
                    FakeStrategy("MID", StrategyDecision(StrategyAction.SELL, BigDecimal("100.0"), 0.5, "mid")),
                ),
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals("HIGH", result.winnerId)
        assertEquals(StrategyAction.BUY, result.decision.action)
        assertEquals(3, result.all.size)
    }

    @Test
    fun `failing strategy becomes HOLD and does not break the cycle`() {
        val runner =
            runner(
                listOf(
                    FailingStrategy("FAILING"),
                    FakeStrategy("GOOD", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.6, "good")),
                ),
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals("GOOD", result.winnerId)
        assertEquals(StrategyAction.HOLD, result.all["FAILING"]?.action)
        assertEquals(0.0, result.all["FAILING"]?.signalStrength)
    }

    @Test
    fun `empty strategy list yields HOLD with NONE winner`() {
        val runner = runner(emptyList())
        val result = runBlocking { runner.runAll(context) }
        assertEquals("NONE", result.winnerId)
        assertEquals(StrategyAction.HOLD, result.decision.action)
        assertEquals(emptyMap<String, StrategyDecision>(), result.all)
    }

    @Test
    fun `regime that blocks entry yields HOLD without evaluating strategies`() {
        val runner =
            runner(
                listOf(
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "buy")),
                ),
            )
        val crashContext = context.copy(regime = regime(RegimeDirection.TREND_DOWN, event = MarketEvent.CRASH))
        val result = runBlocking { runner.runAll(crashContext) }
        assertEquals("NONE", result.winnerId)
        assertEquals(StrategyAction.HOLD, result.decision.action)
        assertEquals(emptyMap<String, StrategyDecision>(), result.all)
    }

    @Test
    fun `UNKNOWN regime blocks entry (fail-closed)`() {
        val runner =
            runner(
                listOf(
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "buy")),
                ),
            )
        val unknownContext = context.copy(regime = PerTickerRegime.UNKNOWN)
        val result = runBlocking { runner.runAll(unknownContext) }
        assertEquals("NONE", result.winnerId)
        assertEquals(StrategyAction.HOLD, result.decision.action)
        assertEquals(emptyMap<String, StrategyDecision>(), result.all)
    }

    @Test
    fun `regime filters incompatible strategies from evaluation`() {
        val runner =
            runner(
                listOf(
                    FakeStrategy("GRID", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.9, "grid")),
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.6, "trend")),
                ),
            )
        val trendContext = context.copy(regime = regime(RegimeDirection.TREND_UP))
        val result = runBlocking { runner.runAll(trendContext) }
        assertFalse("GRID" in result.all)
        assertEquals("TREND_FOLLOWING", result.winnerId)
    }

    @Test
    fun `fit score weights signalStrength among eligible strategies`() {
        val runner =
            runner(
                listOf(
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "trend")),
                    FakeStrategy("BREAKOUT", StrategyDecision(StrategyAction.SELL, BigDecimal("100.0"), 0.85, "breakout")),
                ),
            )
        val trendContext = context.copy(regime = regime(RegimeDirection.TREND_UP))
        val result = runBlocking { runner.runAll(trendContext) }
        // BREAKOUT весится 0.85 * 0.8 = 0.68 < 0.8 (TREND_FOLLOWING) -> побеждает TREND_FOLLOWING.
        assertEquals("TREND_FOLLOWING", result.winnerId)
        assertEquals(0.68, result.all["BREAKOUT"]?.signalStrength)
        assertTrue(result.all["TREND_FOLLOWING"]!!.signalStrength > result.all["BREAKOUT"]!!.signalStrength)
    }

    @Test
    fun `regime is ignored when context has no regime`() {
        val runner =
            runner(
                listOf(
                    FakeStrategy("GRID", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "grid")),
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.6, "trend")),
                ),
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals("GRID", result.winnerId)
        assertEquals(2, result.all.size)
    }

    @Test
    fun `llm signal strategy is excluded while llm signal source is disabled`() {
        val runner =
            runner(
                listOf(
                    FakeStrategy(LlmSignalStrategy.ID, StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.95, "llm")),
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.6, "trend")),
                ),
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals("TREND_FOLLOWING", result.winnerId)
        assertFalse(LlmSignalStrategy.ID in result.all)
    }

    @Test
    fun `llm signal strategy participates when signal source enabled`() {
        val tradingConfig = TradingConfig().apply { llmSignalSourceEnabled = true }
        val runner =
            runner(
                listOf(
                    FakeStrategy(LlmSignalStrategy.ID, StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "llm")),
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.6, "trend")),
                ),
                tradingConfig,
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals(LlmSignalStrategy.ID, result.winnerId)
        assertEquals(2, result.all.size)
    }

    @Test
    fun `llm signal only keeps single llm strategy`() {
        val tradingConfig =
            TradingConfig().apply {
                llmSignalSourceEnabled = true
                llmSignalOnly = true
            }
        val runner =
            runner(
                listOf(
                    FakeStrategy(LlmSignalStrategy.ID, StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.7, "llm")),
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.9, "trend")),
                ),
                tradingConfig,
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals(LlmSignalStrategy.ID, result.winnerId)
        assertEquals(listOf(LlmSignalStrategy.ID), result.all.keys.toList())
    }

    @Test
    fun `llm signal only without enable flag falls back to ordinary competition`() {
        val tradingConfig = TradingConfig().apply { llmSignalOnly = true }
        val runner =
            runner(
                listOf(
                    FakeStrategy(LlmSignalStrategy.ID, StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.95, "llm")),
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.6, "trend")),
                ),
                tradingConfig,
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals("TREND_FOLLOWING", result.winnerId)
        assertFalse(LlmSignalStrategy.ID in result.all)
    }

    @Test
    fun `llm shadow keeps deterministic winner executable when llm wins research`() {
        val tradingConfig =
            TradingConfig().apply {
                llmSignalSourceEnabled = true
                llmSignalShadow = true
            }
        val runner =
            runner(
                listOf(
                    FakeStrategy(LlmSignalStrategy.ID, StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "llm")),
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.SELL, BigDecimal("100.0"), 0.6, "trend")),
                ),
                tradingConfig,
            )
        val result = runBlocking { runner.runAll(context) }
        // research выиграла LLM, но исполняется детерминированный победитель.
        assertTrue(result.shadowed)
        assertEquals("TREND_FOLLOWING", result.winnerId)
        assertEquals(StrategyAction.SELL, result.decision.action)
        assertTrue(LlmSignalStrategy.ID in result.all)
        assertEquals("llm", result.shadowedDecision?.reasoning)
    }

    @Test
    fun `llm shadow without shadow flag leaves llm winner executable`() {
        val tradingConfig = TradingConfig().apply { llmSignalSourceEnabled = true }
        val runner =
            runner(
                listOf(
                    FakeStrategy(LlmSignalStrategy.ID, StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "llm")),
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.6, "trend")),
                ),
                tradingConfig,
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals(LlmSignalStrategy.ID, result.winnerId)
        assertFalse(result.shadowed)
        assertEquals(null, result.shadowedDecision)
    }

    @Test
    fun `llm shadow does not shadow a deterministic winner`() {
        val tradingConfig =
            TradingConfig().apply {
                llmSignalSourceEnabled = true
                llmSignalShadow = true
            }
        val runner =
            runner(
                listOf(
                    FakeStrategy(LlmSignalStrategy.ID, StrategyDecision(StrategyAction.HOLD, BigDecimal("100.0"), 0.3, "llm")),
                    FakeStrategy("TREND_FOLLOWING", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "trend")),
                ),
                tradingConfig,
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals("TREND_FOLLOWING", result.winnerId)
        assertFalse(result.shadowed)
        assertEquals(null, result.shadowedDecision)
    }

    @Test
    fun `llm shadow with signal-only yields HOLD with no executable strategy`() {
        val tradingConfig =
            TradingConfig().apply {
                llmSignalSourceEnabled = true
                llmSignalOnly = true
                llmSignalShadow = true
            }
        val runner =
            runner(
                listOf(
                    FakeStrategy(LlmSignalStrategy.ID, StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "llm")),
                ),
                tradingConfig,
            )
        val result = runBlocking { runner.runAll(context) }
        assertTrue(result.shadowed)
        assertEquals("NONE", result.winnerId)
        assertEquals(StrategyAction.HOLD, result.decision.action)
        assertEquals(LlmSignalStrategy.ID, result.all.keys.single())
    }
}
