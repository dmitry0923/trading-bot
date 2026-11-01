package com.trading.bot.domain.risk

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [RegimeDetector] — ветви, недостижимые с дефолтным `minBars`: короткая серия
 * (направление/волатильность/ликвидность), нулевые цены, пустое окно события,
 * нулевой ATR и нулевая стартовая цена.
 */
class RegimeDetectorEdgeBranchesTest {
    private fun candle(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        index: Int,
        volume: Long = 1_000L,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(open),
            highPrice = BigDecimal(high),
            lowPrice = BigDecimal(low),
            closePrice = BigDecimal(close),
            volume = volume,
            time = LocalDateTime.of(2026, 1, 1, 10, 0).plusMinutes(10L * index),
        )

    private fun series(
        count: Int,
        close: (Int) -> Double = { 100.0 + it * 0.1 },
    ): List<Candle> =
        (0 until count).map { i ->
            val price = close(i)
            candle(price, price + 0.5, price - 0.5, price, i, volume = 1_000L + i)
        }

    @Test
    fun `direction falls back to RANGE when there are fewer than 26 bars`() {
        val regime = RegimeDetector.detect(series(22), RegimeDetectionConfig())

        assertEquals(RegimeDirection.RANGE, regime.direction)
    }

    @Test
    fun `short series skip volatility and liquidity and give a neutral event`() {
        val regime =
            RegimeDetector.detect(
                series(10),
                RegimeDetectionConfig(minBars = 10, moveWindowBars = 12),
            )

        assertEquals(RegimeVolatility.NORMAL, regime.volatility)
        assertEquals(RegimeLiquidity.NORMAL, regime.liquidity)
        assertEquals(MarketEvent.NONE, regime.event)
    }

    @Test
    fun `zero close price contributes a zero volatility observation`() {
        val candles =
            (0 until 40).map { i ->
                val price = if (i == 39) 0.0 else 100.0 + i * 0.2
                candle(price, price + 0.5, price - 0.5, price, i)
            }

        val regime = RegimeDetector.detect(candles, RegimeDetectionConfig())

        assertEquals(RegimeVolatility.LOW, regime.volatility)
    }

    @Test
    fun `single volatility observation returns NORMAL`() {
        val regime =
            RegimeDetector.detect(
                series(15),
                RegimeDetectionConfig(minBars = 10),
            )

        assertEquals(RegimeVolatility.NORMAL, regime.volatility)
    }

    @Test
    fun `flat candles give a zero atr and no event`() {
        val candles = (0 until 40).map { i -> candle(100.0, 100.0, 100.0, 100.0, i, volume = 1_000L + i) }

        val regime = RegimeDetector.detect(candles, RegimeDetectionConfig())

        assertEquals(MarketEvent.NONE, regime.event)
        assertEquals(RegimeLiquidity.NORMAL, regime.liquidity)
    }

    @Test
    fun `zero opening price suppresses the crash and pump detection`() {
        val candles =
            (0 until 40).map { i ->
                val close = 100.0 + (i % 3)
                candle(0.0, close + 1.0, close - 1.0, close, i)
            }

        val regime = RegimeDetector.detect(candles, RegimeDetectionConfig())

        assertEquals(MarketEvent.NONE, regime.event)
    }
}
