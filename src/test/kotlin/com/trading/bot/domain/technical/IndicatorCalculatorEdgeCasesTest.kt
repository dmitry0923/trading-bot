package com.trading.bot.domain.technical

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [IndicatorCalculator] — вырожденные входы и защитные ветки: недостаток данных,
 * плоская сессия (σ/ATR = 0), нулевые цены/объёмы, сброс VWAP по дате и O(n)-ряды
 * сжатия. Эти ветки определяют fail-closed поведение фильтров входа.
 */
class IndicatorCalculatorEdgeCasesTest {
    private fun candle(
        close: String,
        high: String = close,
        low: String = close,
        volume: Long = 10,
        time: LocalDateTime = LocalDateTime.of(2026, 10, 1, 10, 0),
    ): Candle =
        Candle(
            ticker = "Si",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(close),
            highPrice = BigDecimal(high),
            lowPrice = BigDecimal(low),
            closePrice = BigDecimal(close),
            volume = volume,
            time = time,
        )

    private fun flatCandles(count: Int): List<Candle> = List(count) { candle("100") }

    private fun risingCandles(count: Int): List<Candle> = List(count) { i -> candle((100 + i).toString()) }

    // ─── RSI / ATR / EMA / MACD ─────────────────────────────────────

    @Test
    fun `rsi returns neutral 50 without enough data`() {
        assertEquals(50.0, IndicatorCalculator.rsi(emptyList(), 14), 1e-9)
        assertEquals(50.0, IndicatorCalculator.rsi(List(14) { BigDecimal("100") }, 14), 1e-9)
    }

    @Test
    fun `rsi is 100 when every change is a gain and 0 when every change is a loss`() {
        val up = List(20) { BigDecimal((100 + it).toString()) }
        val down = List(20) { BigDecimal((100 - it).toString()) }

        assertEquals(100.0, IndicatorCalculator.rsi(up, 14), 1e-9)
        assertEquals(0.0, IndicatorCalculator.rsi(down, 14), 1e-9)
    }

    @Test
    fun `atr returns zero without enough candles and on a flat range`() {
        assertEquals(0.0, IndicatorCalculator.atr(flatCandles(10), 14), 1e-9)
        assertEquals(0.0, IndicatorCalculator.atr(flatCandles(30), 14), 1e-9)
    }

    @Test
    fun `atrPercentile requires at least two candles`() {
        assertEquals(-1, IndicatorCalculator.atrPercentile(emptyList()))
        assertEquals(-1, IndicatorCalculator.atrPercentile(flatCandles(1)))
        assertEquals(0, IndicatorCalculator.atrPercentile(flatCandles(2)))
    }

    @Test
    fun `ema of an empty series is empty and a single value is preserved`() {
        assertTrue(IndicatorCalculator.ema(emptyList(), 12).isEmpty())
        assertEquals(listOf(42.0), IndicatorCalculator.ema(listOf(BigDecimal("42")), 12))
    }

    @Test
    fun `macd of an empty series fails instead of returning a silent zero`() {
        assertThrows(NoSuchElementException::class.java) { IndicatorCalculator.macd(emptyList()) }
    }

    @Test
    fun `calculate requires thirty candles`() {
        assertNull(IndicatorCalculator.calculate(flatCandles(29)))
    }

    @Test
    fun `calculate reports trend and conclusion on a rising series`() {
        val indicators = IndicatorCalculator.calculate(risingCandles(40))!!

        assertEquals("UP", indicators.trend)
        assertTrue(indicators.macdLine.isFinite())
        assertTrue(indicators.conclusion.isNotBlank())
    }

    // ─── VWAP ───────────────────────────────────────────────────────

    @Test
    fun `vwap without candles is null`() {
        assertNull(IndicatorCalculator.vwap(emptyList()))
    }

    @Test
    fun `vwap with zero volume falls back to typical price`() {
        val value = IndicatorCalculator.vwap(listOf(candle("100", high = "110", low = "90", volume = 0)))

        assertEquals(100.0, value!!, 1e-9)
    }

    @Test
    fun `vwap resets at the session boundary`() {
        val day1 = LocalDateTime.of(2026, 10, 1, 10, 0)
        val day2 = LocalDateTime.of(2026, 10, 2, 10, 0)
        val candles =
            listOf(
                candle("100", volume = 10, time = day1),
                candle("300", volume = 10, time = day1.plusHours(1)),
                candle("200", volume = 10, time = day2),
            )

        // последняя сессия содержит одну свечу → VWAP = её typical price
        assertEquals(200.0, IndicatorCalculator.vwap(candles)!!, 1e-9)
    }

    @Test
    fun `vwap sigma requires two candles and a positive mean`() {
        assertNull(IndicatorCalculator.vwapStdDevPercent(flatCandles(1)))
        assertNull(IndicatorCalculator.vwapStdDevPercent(listOf(candle("0"), candle("0"))))
        assertEquals(0.0, IndicatorCalculator.vwapStdDevPercent(flatCandles(2))!!, 1e-9)
    }

    // ─── ADX ────────────────────────────────────────────────────────

    @Test
    fun `adx is zero without enough data and on a flat market`() {
        assertEquals(0.0, IndicatorCalculator.adx(flatCandles(20), 14), 1e-9)
        assertEquals(0.0, IndicatorCalculator.adx(flatCandles(40), 14), 1e-9)
    }

    @Test
    fun `adx is positive on a directional trend`() {
        val candles =
            List(40) { i ->
                candle(
                    close = (100 + i).toString(),
                    high = (101 + i).toString(),
                    low = (99 + i).toString(),
                )
            }

        assertTrue(IndicatorCalculator.adx(candles, 14) > 0.0)
    }

    // ─── Keltner / squeeze ──────────────────────────────────────────

    @Test
    fun `keltner requires enough candles and positive width`() {
        assertNull(IndicatorCalculator.keltner(flatCandles(10)))
        // плоский рынок: ATR = 0 → канал нулевой ширины не имеет смысла
        assertNull(IndicatorCalculator.keltner(flatCandles(40)))
    }

    @Test
    fun `isSqueeze requires enough candles`() {
        assertNull(IndicatorCalculator.isSqueeze(flatCandles(5)))
    }

    @Test
    fun `squeezeSeries rejects too few bars and a degenerate channel`() {
        assertNull(IndicatorCalculator.squeezeSeries(flatCandles(5)))
        assertNull(IndicatorCalculator.squeezeSeries(flatCandles(40)))
        assertEquals(20, IndicatorCalculator.squeezeMinBars())
    }

    // ─── volume SMA ────────────────────────────────────────────────

    @Test
    fun `volume sma validates window and rejects zero volume`() {
        assertNull(IndicatorCalculator.volumeSma(flatCandles(5), 10))
        assertNull(IndicatorCalculator.volumeSma(flatCandles(10), 0))
        assertNull(IndicatorCalculator.volumeSma(List(10) { candle("100", volume = 0) }, 10))
        assertEquals(10.0, IndicatorCalculator.volumeSma(flatCandles(10), 10)!!, 1e-9)
    }

    // ─── range squeeze ─────────────────────────────────────────────

    @Test
    fun `isRangeSqueeze requires data and a positive atr`() {
        assertNull(IndicatorCalculator.isRangeSqueeze(flatCandles(20)))
        // плоский ряд: ATR = 0 → сравнение не определено
        assertNull(IndicatorCalculator.isRangeSqueeze(flatCandles(60)))
    }

    @Test
    fun `rangeSqueezeSeries rejects invalid periods and short series`() {
        assertNull(IndicatorCalculator.rangeSqueezeSeries(flatCandles(60), rangePeriod = 0))
        assertNull(IndicatorCalculator.rangeSqueezeSeries(flatCandles(60), atrPeriod = 0))
        assertNull(IndicatorCalculator.rangeSqueezeSeries(flatCandles(10)))
    }

    @Test
    fun `rangeSqueezeSeries marks a flat market as not squeezed`() {
        val states = IndicatorCalculator.rangeSqueezeSeries(flatCandles(60))!!

        assertEquals(60, states.size)
        // ATR = 0 → условие atrAcc > 0 не выполнено ни на одном баре
        assertFalse(states.any { it })
    }

    @Test
    fun `rangeSqueezeSeries detects a narrow range inside a wide atr window`() {
        // первые 50 баров широкие, последние 10 — узкие: средний диапазон падает,
        // а ATR остаётся высоким → сжатие на хвосте
        val wide =
            List(50) {
                candle(
                    "100",
                    high = "110",
                    low = "90",
                    time = LocalDateTime.of(2026, 10, 1, 10, 0).plusMinutes(it.toLong()),
                )
            }
        val narrow =
            List(10) {
                candle(
                    "100",
                    high = "101",
                    low = "99",
                    time = LocalDateTime.of(2026, 10, 1, 20, 0).plusMinutes(it.toLong()),
                )
            }

        val states = IndicatorCalculator.rangeSqueezeSeries(wide + narrow, rangePeriod = 10, atrPeriod = 50)!!

        assertTrue(states.last(), "narrow tail must be classified as squeezed")
    }
}
