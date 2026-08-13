package com.trading.bot.domain.technical

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Unit-тесты ресемплера свечей в старший таймфрейм [CandleResampler]
 * (roadmap v2.5, multi-timeframe).
 *
 * Проверяют: агрегацию OHLCV в часовые/дневные бары, point-in-time обрезку
 * незавершённого бакета через `completedBefore`,
 * сортировку и реакцию на неподдерживаемый таймфрейм.
 */
class CandleResamplerTest {
    @Test
    fun `aggregates ten minute candles into hourly bars`() {
        val start = LocalDateTime.of(2026, 8, 12, 10, 0)
        val candles = (0 until 6).map { i -> candle(start.plusMinutes(i * 10L), priceOf(100 + i), volume = 10L) }

        val hourly = CandleResampler.resample(candles, "HOUR_1")

        assertEquals(1, hourly.size)
        val bar = hourly.single()
        assertEquals(start, bar.time)
        assertEquals("HOUR_1", bar.timeframe)
        assertEquals(priceOf(100), bar.openPrice)
        assertEquals(priceOf(105), bar.closePrice)
        assertEquals(priceOf(107), bar.highPrice)
        assertEquals(priceOf(98), bar.lowPrice)
        assertEquals(60L, bar.volume)
    }

    @Test
    fun `splits candles across hour and day buckets`() {
        val start = LocalDateTime.of(2026, 8, 12, 23, 50)
        val candles = (0 until 3).map { i -> candle(start.plusMinutes(i * 10L), priceOf(100 + i)) }

        val hourly = CandleResampler.resample(candles, "HOUR_1")

        assertEquals(2, hourly.size)
        assertEquals(LocalDateTime.of(2026, 8, 12, 23, 0), hourly[0].time)
        assertEquals(LocalDateTime.of(2026, 8, 13, 0, 0), hourly[1].time)

        val daily = CandleResampler.resample(candles, "DAY_1")
        assertEquals(2, daily.size)
        assertEquals(LocalDateTime.of(2026, 8, 12, 0, 0), daily[0].time)
        assertEquals(LocalDateTime.of(2026, 8, 13, 0, 0), daily[1].time)
    }

    @Test
    fun `supports short aliases`() {
        val start = LocalDateTime.of(2026, 8, 12, 10, 0)
        val candles = (0 until 6).map { i -> candle(start.plusMinutes(i * 10L), priceOf(100 + i)) }

        assertEquals(1, CandleResampler.resample(candles, "H1").size)
        assertEquals(1, CandleResampler.resample(candles, "D1").size)
    }

    @Test
    fun `completedBefore drops unfinished trailing bucket`() {
        val start = LocalDateTime.of(2026, 8, 12, 10, 0)
        val candles = (0 until 6).map { i -> candle(start.plusMinutes(i * 10L), priceOf(100 + i)) }

        // К 10:30 завершены только свечи 10:00-10:10 и 10:10-10:20;
        // часовой бакет 10:00-11:00 ещё не завершён и должен быть отброшен.
        val dropped = CandleResampler.resample(candles, "HOUR_1", completedBefore = start.plusMinutes(30))
        assertEquals(0, dropped.size)

        val kept =
            CandleResampler.resample(candles, "HOUR_1", completedBefore = start.plusMinutes(70))
        assertEquals(1, kept.size)
    }

    @Test
    fun `completedBefore keeps only full day buckets`() {
        val start = LocalDateTime.of(2026, 8, 12, 22, 0)
        val candles = (0 until 3).map { i -> candle(start.plusMinutes(i * 10L), priceOf(100 + i)) }

        val daily = CandleResampler.resample(candles, "DAY_1", completedBefore = LocalDateTime.of(2026, 8, 13, 1, 0))

        assertEquals(1, daily.size)
        assertEquals(LocalDateTime.of(2026, 8, 12, 0, 0), daily.single().time)
    }

    @Test
    fun `sorts output by time regardless of input order`() {
        val start = LocalDateTime.of(2026, 8, 12, 10, 0)
        val a = candle(start.plusMinutes(0), priceOf(100))
        val b = candle(start.plusMinutes(10), priceOf(101))
        val c = candle(start.plusMinutes(60), priceOf(102))

        val resampled = CandleResampler.resample(listOf(c, a, b), "HOUR_1")

        assertEquals(2, resampled.size)
        assertEquals(start, resampled[0].time)
        assertEquals(start.plusHours(1), resampled[1].time)
    }

    @Test
    fun `returns empty list for empty input`() {
        assertTrue(CandleResampler.resample(emptyList(), "HOUR_1").isEmpty())
    }

    @Test
    fun `rejects unsupported timeframe`() {
        val start = LocalDateTime.of(2026, 8, 12, 10, 0)
        val candles = listOf(candle(start, priceOf(100)))

        assertThrows(IllegalArgumentException::class.java) {
            CandleResampler.resample(candles, "MINUTE_30")
        }
    }

    private fun candle(
        time: LocalDateTime,
        price: BigDecimal,
        volume: Long = 10L,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = price,
            highPrice = price.add(BigDecimal("2")),
            lowPrice = price.subtract(BigDecimal("2")),
            closePrice = price,
            volume = volume,
            time = time,
        )

    private fun priceOf(value: Int): BigDecimal = BigDecimal(value)
}
