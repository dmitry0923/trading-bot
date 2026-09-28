package com.trading.bot.domain.technical

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Индикаторы для research-стратегий 2026-09-28: объёмное SMA (подтверждение
 * всплеска) и сжатие по среднему (High-Low) против ATR (стратегия №4).
 *
 * Отдельно проверяется, что предрассчитанный O(n)-ряд [IndicatorCalculator.rangeSqueezeSeries]
 * совпадает с точечным [IndicatorCalculator.isRangeSqueeze] на каждом баре — иначе
 * отфильтрованные и неотфильтрованные ветви давали бы разные сигналы.
 */
class IndicatorCalculatorRangeSqueezeTest {
    private val day = LocalDate.of(2026, 1, 5)

    private fun candle(
        idx: Int,
        close: Double,
        range: Double,
        volume: Long = 100,
    ): Candle =
        Candle(
            ticker = "TEST",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(close),
            highPrice = BigDecimal.valueOf(close + range / 2),
            lowPrice = BigDecimal.valueOf(close - range / 2),
            closePrice = BigDecimal.valueOf(close),
            volume = volume,
            time = day.atTime(6, 0).plusMinutes(10L * idx),
        )

    @Test
    fun `volume sma averages the last bars including the current one`() {
        val bars = (0 until 20).map { candle(it, 100.0, 1.0, volume = (it + 1) * 10L) }
        // Объёмы 10..200, сумма 2100, среднее 105.
        assertEquals(105.0, IndicatorCalculator.volumeSma(bars, 20)!!, 1e-9)
        assertEquals(200.0, IndicatorCalculator.volumeSma(bars, 1)!!, 1e-9)
    }

    @Test
    fun `volume sma is null without data or on zero volume`() {
        val bars = (0 until 10).map { candle(it, 100.0, 1.0) }
        assertNull(IndicatorCalculator.volumeSma(bars, 20))
        val zero = (0 until 20).map { candle(it, 100.0, 1.0, volume = 0) }
        assertNull(IndicatorCalculator.volumeSma(zero, 20))
    }

    @Test
    fun `range squeeze is detected when the average range drops below the atr threshold`() {
        val wide = (0 until 40).map { candle(it, 100.0, 10.0) }
        val tight = (40 until 60).map { candle(it, 100.0, 0.2) }
        assertTrue(IndicatorCalculator.isRangeSqueeze(wide + tight, 20, 50, 0.5) == true)
    }

    @Test
    fun `range squeeze is absent when the range matches the atr`() {
        val bars = (0 until 60).map { candle(it, 100.0, 2.0) }
        assertFalse(IndicatorCalculator.isRangeSqueeze(bars, 20, 50, 0.5) == true)
    }

    @Test
    fun `range squeeze needs the full history and returns null otherwise`() {
        val bars = (0 until 30).map { candle(it, 100.0, 1.0) }
        assertNull(IndicatorCalculator.isRangeSqueeze(bars, 20, 50, 0.5))
        assertNull(IndicatorCalculator.rangeSqueezeSeries(bars, 20, 50, 0.5))
        assertEquals(51, IndicatorCalculator.rangeSqueezeMinBars(20, 50))
        assertEquals(100, IndicatorCalculator.rangeSqueezeMinBars(100, 50))
    }

    @Test
    fun `range squeeze series matches the pointwise calculation on every bar`() {
        val rng = java.util.Random(42)
        val bars =
            (0 until 400).map { i ->
                val range = 0.2 + rng.nextDouble() * (if (i % 97 < 20) 0.2 else 4.0)
                val drift = if (i % 53 == 0) 3.0 else 0.0
                candle(i, 100.0 + drift, range)
            }
        val series = IndicatorCalculator.rangeSqueezeSeries(bars, 20, 50, 0.5)
        assertNotNull(series)
        val states = series!!
        val minBars = IndicatorCalculator.rangeSqueezeMinBars(20, 50)
        for (i in minBars until bars.size) {
            val expected = IndicatorCalculator.isRangeSqueeze(bars.subList(0, i + 1), 20, 50, 0.5)
            assertEquals(expected, states[i], "bar $i")
        }
    }

    @Test
    fun `range squeeze series matches the pointwise calculation when the range window is wider than atr`() {
        val rng = java.util.Random(7)
        val bars =
            (0 until 300).map { i ->
                val range = 0.5 + rng.nextDouble() * (if (i % 61 < 25) 0.3 else 3.0)
                candle(i, 100.0, range)
            }
        val series = IndicatorCalculator.rangeSqueezeSeries(bars, 100, 14, 0.5)
        assertNotNull(series)
        val states = series!!
        val minBars = IndicatorCalculator.rangeSqueezeMinBars(100, 14)
        for (i in minBars until bars.size) {
            val expected = IndicatorCalculator.isRangeSqueeze(bars.subList(0, i + 1), 100, 14, 0.5)
            assertEquals(expected, states[i], "bar $i")
        }
    }

    @Test
    fun `range squeeze series marks the leading bars as uncompressed`() {
        val bars = (0 until 60).map { candle(it, 100.0, 1.0) }
        val states = IndicatorCalculator.rangeSqueezeSeries(bars, 20, 50, 0.5)!!
        val minBars = IndicatorCalculator.rangeSqueezeMinBars(20, 50)
        for (i in 0 until minBars) {
            assertFalse(states[i], "bar $i")
        }
    }

    @Test
    fun `candles keep ascending time for deterministic series`() {
        val bars = (0 until 5).map { candle(it, 100.0, 1.0) }
        val times: List<LocalDateTime> = bars.map { it.time }
        assertEquals(times.sorted(), times)
    }
}
