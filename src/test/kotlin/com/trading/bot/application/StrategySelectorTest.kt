package com.trading.bot.application

import com.trading.bot.domain.risk.MarketEvent
import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDirection
import com.trading.bot.domain.risk.RegimeLiquidity
import com.trading.bot.domain.risk.RegimeVolatility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrategySelectorTest {
    private val selector = StrategySelector()

    private fun regime(
        direction: RegimeDirection,
        volatility: RegimeVolatility = RegimeVolatility.NORMAL,
        liquidity: RegimeLiquidity = RegimeLiquidity.NORMAL,
        event: MarketEvent = MarketEvent.NONE,
    ) = PerTickerRegime(direction, volatility, liquidity, event)

    @Test
    fun `trend regime allows trend and breakout strategies but blocks range ones`() {
        val eligible = selector.eligibleStrategyIds(regime(RegimeDirection.TREND_UP))
        assertTrue("TREND_FOLLOWING" in eligible)
        assertTrue("BREAKOUT" in eligible)
        assertTrue("SCALPING" in eligible)
        assertTrue("DISCRETIONARY" in eligible)
        assertFalse("GRID" in eligible)
        assertFalse("MEAN_REVERSION" in eligible)
    }

    @Test
    fun `range regime allows grid and mean reversion but blocks trend following`() {
        val eligible = selector.eligibleStrategyIds(regime(RegimeDirection.RANGE))
        assertTrue("GRID" in eligible)
        assertTrue("MEAN_REVERSION" in eligible)
        assertTrue("ARBITRAGE" in eligible)
        assertFalse("TREND_FOLLOWING" in eligible)
    }

    @Test
    fun `crash regime blocks all strategies`() {
        val eligible = selector.eligibleStrategyIds(regime(RegimeDirection.TREND_DOWN, event = MarketEvent.CRASH))
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `pump regime blocks all strategies`() {
        val eligible = selector.eligibleStrategyIds(regime(RegimeDirection.TREND_UP, event = MarketEvent.PUMP))
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `thin liquidity blocks all strategies`() {
        val eligible = selector.eligibleStrategyIds(regime(RegimeDirection.RANGE, liquidity = RegimeLiquidity.THIN))
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `extreme volatility blocks all strategies`() {
        val eligible = selector.eligibleStrategyIds(regime(RegimeDirection.TREND_UP, volatility = RegimeVolatility.EXTREME))
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `high volatility restricts to momentum and discretionary strategies`() {
        val eligible = selector.eligibleStrategyIds(regime(RegimeDirection.TREND_UP, volatility = RegimeVolatility.HIGH))
        assertTrue("SCALPING" in eligible)
        assertTrue("DISCRETIONARY" in eligible)
        assertTrue("ARBITRAGE" in eligible)
        assertFalse("TREND_FOLLOWING" in eligible)
        assertFalse("BREAKOUT" in eligible)
    }

    @Test
    fun `fit score reflects direction compatibility`() {
        val trendUp = regime(RegimeDirection.TREND_UP)
        val range = regime(RegimeDirection.RANGE)
        assertEquals(1.0, selector.fitScore("TREND_FOLLOWING", trendUp))
        assertEquals(0.0, selector.fitScore("GRID", trendUp))
        assertEquals(1.0, selector.fitScore("GRID", range))
        assertEquals(0.0, selector.fitScore("TREND_FOLLOWING", range))
    }

    @Test
    fun `fit score reduced under high volatility`() {
        val highVolTrend = regime(RegimeDirection.TREND_UP, volatility = RegimeVolatility.HIGH)
        val base = selector.fitScore("SCALPING", regime(RegimeDirection.TREND_UP))
        val reduced = selector.fitScore("SCALPING", highVolTrend)
        assertEquals(base * 0.7, reduced, 1e-9)
    }

    @Test
    fun `fit score is zero when regime blocks entry`() {
        val crash = regime(RegimeDirection.TREND_UP, event = MarketEvent.CRASH)
        assertEquals(0.0, selector.fitScore("TREND_FOLLOWING", crash))
    }
}
