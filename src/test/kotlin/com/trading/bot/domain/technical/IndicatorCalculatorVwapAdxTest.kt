package com.trading.bot.domain.technical

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Индикаторы стратегии «VWAP mean reversion» (research 2026-09-26, стратегия №1):
 * сессионный VWAP, σ в % от VWAP и ADX как трендовый фильтр.
 */
class IndicatorCalculatorVwapAdxTest {
    private val day1 = LocalDate.of(2026, 1, 5)
    private val day2 = LocalDate.of(2026, 1, 6)

    private fun candle(
        day: LocalDate,
        idx: Int,
        high: Double,
        low: Double,
        close: Double,
        volume: Long,
    ): Candle =
        Candle(
            ticker = "CNYRUBF",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(close),
            highPrice = BigDecimal.valueOf(high),
            lowPrice = BigDecimal.valueOf(low),
            closePrice = BigDecimal.valueOf(close),
            volume = volume,
            time = day.atTime(10, 0).plusMinutes(10L * idx),
        )

    @Test
    fun `vwap is volume weighted average of typical price`() {
        val bars =
            listOf(
                candle(day1, 0, high = 11.0, low = 9.0, close = 10.0, volume = 100),
                candle(day1, 1, high = 21.0, low = 19.0, close = 20.0, volume = 300),
            )
        // typical1 = 10, typical2 = 20; VWAP = (10*100 + 20*300) / 400 = 17.5
        val vwap = IndicatorCalculator.vwap(bars)
        assertEquals(17.5, vwap!!, 1e-9)
    }

    @Test
    fun `vwap resets on new session`() {
        val bars =
            listOf(
                candle(day1, 0, high = 11.0, low = 9.0, close = 10.0, volume = 100),
                candle(day2, 0, high = 51.0, low = 49.0, close = 50.0, volume = 100),
            )
        // Сброс по дате: вчерашние 10 не входят в сегодняшний VWAP = 50.
        assertEquals(50.0, IndicatorCalculator.vwap(bars)!!, 1e-9)
    }

    @Test
    fun `vwap returns null for empty and stddev needs two bars`() {
        assertNull(IndicatorCalculator.vwap(emptyList()))
        assertNull(IndicatorCalculator.vwapStdDevPercent(emptyList()))
        val one = listOf(candle(day1, 0, 11.0, 9.0, 10.0, 100))
        assertNull(IndicatorCalculator.vwapStdDevPercent(one))
    }

    @Test
    fun `vwap stddev percent is zero for flat session`() {
        val bars = (0 until 5).map { candle(day1, it, 10.0, 10.0, 10.0, 100) }
        val sd = IndicatorCalculator.vwapStdDevPercent(bars)
        assertEquals(0.0, sd!!, 1e-9)
    }

    @Test
    fun `vwap stddev percent grows with dispersion`() {
        val flat = (0 until 5).map { candle(day1, it, 10.0, 10.0, 10.0, 100) }
        // Чередующиеся типичные цены 8/12 → σ заметно выше нуля.
        val wide =
            (0 until 5).map { i ->
                if (i % 2 == 0) {
                    candle(day1, i, high = 12.0, low = 8.0, close = 10.0, volume = 100)
                } else {
                    candle(day1, i, high = 14.0, low = 10.0, close = 12.0, volume = 100)
                }
            }
        val sdFlat = IndicatorCalculator.vwapStdDevPercent(flat)!!
        val sdWide = IndicatorCalculator.vwapStdDevPercent(wide)!!
        assertEquals(0.0, sdFlat, 1e-9)
        assertTrue(sdWide > 1.0, "разброс должен давать ощутимую σ, получено $sdWide")
    }

    @Test
    fun `flat session sigma is float noise and below meaningful threshold`() {
        // Регрессия: на полностью плохой сессии σ ≈ 1e-14, а не 0. Деление
        // отклонения на такую σ давало ложные отклонения в тысячи σ (BUY/SELL
        // на абсолютно плоском рынке). Порог отсекает этот шум.
        val bars =
            (0 until 10).map {
                candle(day1, it, high = 102.0, low = 100.0, close = 100.0, volume = 100)
            }
        val sd = IndicatorCalculator.vwapStdDevPercent(bars)!!
        assertTrue(
            sd < IndicatorCalculator.MIN_MEANINGFUL_VWAP_SIGMA_PERCENT,
            "σ плоской сессии должна быть ниже порога, получено $sd",
        )
    }

    @Test
    fun `adx is zero without enough data`() {
        val bars = (0 until 10).map { candle(day1, it, 11.0, 9.0, 10.0, 100) }
        assertEquals(0.0, IndicatorCalculator.adx(bars, period = 14), 1e-9)
    }

    @Test
    fun `adx is high on clean uptrend`() {
        // Монотонный рост: каждый бар выше предыдущего на 1.0.
        val bars =
            (0 until 40).map { i ->
                val base = 100.0 + i
                candle(day1, i, high = base + 1.0, low = base - 1.0, close = base, volume = 100)
            }
        val adx = IndicatorCalculator.adx(bars, period = 14)
        assertTrue(adx > 50.0, "чистый тренд должен давать высокий ADX, получено $adx")
    }

    @Test
    fun `adx is low on flat market`() {
        val bars = (0 until 40).map { candle(day1, it, 10.0, 10.0, 10.0, 100) }
        val adx = IndicatorCalculator.adx(bars, period = 14)
        assertTrue(adx < 20.0, "боковик должен давать низкий ADX, получено $adx")
    }

    @Test
    fun `adx result is within 0 to 100 range`() {
        val bars =
            (0 until 60).map { i ->
                val base = 100.0 + (i % 7) * 2.0
                candle(day1, i, high = base + 1.5, low = base - 1.5, close = base, volume = 100)
            }
        val adx = IndicatorCalculator.adx(bars, period = 14)
        assertTrue(adx in 0.0..100.0, "ADX вне диапазона: $adx")
        assertTrue(!adx.isNaN() && !adx.isInfinite(), "ADX NaN/Inf: $adx")
    }

    @Test
    fun `candle time is used for session grouping`() {
        val bars =
            listOf(
                candle(day1, 0, 11.0, 9.0, 10.0, 100),
                candle(
                    day1,
                    1,
                    11.0,
                    9.0,
                    10.0,
                    100,
                ).copy(time = LocalDateTime.of(day1, java.time.LocalTime.of(23, 50))),
            )
        // Один день, разные времена — сброса нет, VWAP = 10.
        assertEquals(10.0, IndicatorCalculator.vwap(bars)!!, 1e-9)
    }
}
