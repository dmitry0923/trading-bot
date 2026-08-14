package com.trading.bot.domain.risk

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Чистая математика ATR (roadmap: адаптивный стоп-лосс фьючерсов).
 * TR(i) = max(high-low, |high-prevClose|, |low-prevClose|); ATR = среднее TR.
 */
class AtrTest {
    @Test
    fun `atr averages true ranges against previous close`() {
        // TR(c1) = max(2, 2, 0) = 2.0; TR(c2) = max(1.5, 1, 0.5) = 1.5 -> ATR(2) = 1.75
        val candles =
            listOf(
                candle(close = "10", high = "11", low = "9"),
                candle(close = "13.5", high = "12", low = "10"),
                candle(close = "14", high = "14.5", low = "13"),
            )
        assertEquals(0, BigDecimal("1.75").compareTo(Atr.calculate(candles, period = 2)!!))
    }

    @Test
    fun `atr returns null when not enough candles`() {
        val candles = listOf(candle(close = "10", high = "11", low = "9"))
        assertNull(Atr.calculate(candles, period = 14))
        assertNull(Atr.calculate(emptyList(), period = 14))
    }

    @Test
    fun `stop points map atr price to points with multiplier`() {
        // ATR 0.20 ₽ при priceStep 0.01 => 20 пунктов; ×2 => 40 пунктов.
        assertEquals(40, Atr.stopPoints(BigDecimal("0.20"), BigDecimal("0.01"), 2.0, 10, 100))
    }

    @Test
    fun `stop points clamp to configured bounds`() {
        // ATR 4 ₽ => 400 пунктов -> max 100; ATR 0.03 ₽ => 3 пункта -> min 10.
        assertEquals(100, Atr.stopPoints(BigDecimal("4.0"), BigDecimal("0.01"), 2.0, 10, 100))
        assertEquals(10, Atr.stopPoints(BigDecimal("0.03"), BigDecimal("0.01"), 2.0, 10, 100))
    }

    @Test
    fun `stop points return null on degenerate inputs`() {
        assertNull(Atr.stopPoints(BigDecimal.ZERO, BigDecimal("0.01"), 2.0, 10, 100))
        assertNull(Atr.stopPoints(BigDecimal("0.2"), BigDecimal.ZERO, 2.0, 10, 100))
        assertNull(Atr.stopPoints(BigDecimal("0.2"), BigDecimal("0.01"), 0.0, 10, 100))
    }

    private fun candle(
        close: String,
        high: String,
        low: String,
    ): Candle =
        Candle(
            ticker = "Si",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(close),
            highPrice = BigDecimal(high),
            lowPrice = BigDecimal(low),
            closePrice = BigDecimal(close),
            volume = 1000L,
            time = LocalDateTime.now(),
        )
}
