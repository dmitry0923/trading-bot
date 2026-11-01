package com.trading.bot.domain.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [PerTickerRegime] — множитель размера и причина блокировки для каждого сценария.
 */
class PerTickerRegimeTest {
    private fun regime(
        direction: RegimeDirection = RegimeDirection.RANGE,
        volatility: RegimeVolatility = RegimeVolatility.NORMAL,
        liquidity: RegimeLiquidity = RegimeLiquidity.NORMAL,
        event: MarketEvent = MarketEvent.NONE,
        unknown: Boolean = false,
    ): PerTickerRegime = PerTickerRegime(direction, volatility, liquidity, event, unknown)

    @Test
    fun `size multiplier zeroes blocked regimes halves high volatility and keeps the base`() {
        assertEquals(0.0, regime(event = MarketEvent.CRASH).sizeMultiplier())
        assertEquals(0.0, regime(liquidity = RegimeLiquidity.THIN).sizeMultiplier())
        assertEquals(0.5, regime(volatility = RegimeVolatility.HIGH).sizeMultiplier())
        assertEquals(1.0, regime().sizeMultiplier())
    }

    @Test
    fun `block reason covers every blocking factor`() {
        assertEquals("UNKNOWN", PerTickerRegime.UNKNOWN.blockReason())
        assertEquals("CRASH", regime(event = MarketEvent.CRASH).blockReason())
        assertEquals("PUMP", regime(event = MarketEvent.PUMP).blockReason())
        assertEquals("LOW_LIQUIDITY", regime(liquidity = RegimeLiquidity.THIN).blockReason())
        assertEquals("HIGH_VOLATILITY", regime(volatility = RegimeVolatility.EXTREME).blockReason())
        assertNull(regime().blockReason())
    }

    @Test
    fun `encoded level combines event volatility direction and liquidity`() {
        assertEquals(120.0, regime().encodedLevel())
        assertEquals(
            1000.0 + 300.0 + 10.0 + 1.0,
            regime(
                direction = RegimeDirection.TREND_DOWN,
                volatility = RegimeVolatility.EXTREME,
                liquidity = RegimeLiquidity.THIN,
                event = MarketEvent.CRASH,
            ).encodedLevel(),
        )
    }
}
