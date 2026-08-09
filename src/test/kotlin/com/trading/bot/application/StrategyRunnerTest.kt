package com.trading.bot.application

import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `winner is the strategy with maximum confidence`() {
        val runner =
            StrategyRunner(
                strategies =
                    listOf(
                        FakeStrategy("LOW", StrategyDecision(StrategyAction.HOLD, BigDecimal("100.0"), 0.3, "low")),
                        FakeStrategy("HIGH", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.8, "high")),
                        FakeStrategy("MID", StrategyDecision(StrategyAction.SELL, BigDecimal("100.0"), 0.5, "mid")),
                    ),
                meterRegistry = SimpleMeterRegistry(),
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals("HIGH", result.winnerId)
        assertEquals(StrategyAction.BUY, result.decision.action)
        assertEquals(3, result.all.size)
    }

    @Test
    fun `failing strategy becomes HOLD and does not break the cycle`() {
        val runner =
            StrategyRunner(
                strategies =
                    listOf(
                        FailingStrategy("FAILING"),
                        FakeStrategy("GOOD", StrategyDecision(StrategyAction.BUY, BigDecimal("100.0"), 0.6, "good")),
                    ),
                meterRegistry = SimpleMeterRegistry(),
            )
        val result = runBlocking { runner.runAll(context) }
        assertEquals("GOOD", result.winnerId)
        assertEquals(StrategyAction.HOLD, result.all["FAILING"]?.action)
        assertEquals(0.0, result.all["FAILING"]?.confidence)
    }

    @Test
    fun `empty strategy list yields HOLD with NONE winner`() {
        val runner = StrategyRunner(strategies = emptyList(), meterRegistry = SimpleMeterRegistry())
        val result = runBlocking { runner.runAll(context) }
        assertEquals("NONE", result.winnerId)
        assertEquals(StrategyAction.HOLD, result.decision.action)
        assertEquals(emptyMap<String, StrategyDecision>(), result.all)
    }
}
