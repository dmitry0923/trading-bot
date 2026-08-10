package com.trading.bot.domain.risk

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.math.pow
import kotlin.math.sin

class RegimeDetectorTest {
    private val config = RegimeDetectionConfig()

    private fun candle(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long,
        index: Int,
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

    /** Ряд свечей без гэпов: open = предыдущее закрытие, волатильность бара малая. */
    private fun series(
        closes: List<Double>,
        volumes: List<Long> = List(closes.size) { 1000L },
    ): List<Candle> =
        closes.mapIndexed { i, close ->
            val open = if (i == 0) close else closes[i - 1]
            val high = maxOf(open, close) * 1.001
            val low = minOf(open, close) * 0.999
            candle(open, high, low, close, volumes[i], i)
        }

    /** Растущие объёмы с пиком на последнем баре (ликвидность NORMAL). */
    private fun risingVolumes(size: Int): List<Long> = (0 until (size - 1).coerceAtLeast(0)).map { 800L + (it % 5) * 150L } + listOf(5000L)

    @Test
    fun `trend up detected from steady rising closes`() {
        val closes = (0 until 60).map { 100.0 * 1.001.pow(it.toDouble()) }
        val regime = RegimeDetector.detect(series(closes, risingVolumes(closes.size)), config)
        assertEquals(RegimeDirection.TREND_UP, regime.direction)
        assertEquals(MarketEvent.NONE, regime.event)
        assertFalse(regime.blocksEntry)
    }

    @Test
    fun `trend down detected from steady falling closes`() {
        val closes = (0 until 60).map { 100.0 * 0.999.pow(it.toDouble()) }
        val regime = RegimeDetector.detect(series(closes, risingVolumes(closes.size)), config)
        assertEquals(RegimeDirection.TREND_DOWN, regime.direction)
        assertFalse(regime.blocksEntry)
    }

    @Test
    fun `range detected from oscillating closes`() {
        val closes = (0 until 60).map { 100.0 + 2.0 * sin(it * 0.8) }
        val regime = RegimeDetector.detect(series(closes, risingVolumes(closes.size)), config)
        assertEquals(RegimeDirection.RANGE, regime.direction)
        assertFalse(regime.blocksEntry)
    }

    @Test
    fun `crash detected when price drops sharply over move window`() {
        val closes =
            (0 until 60).map { 100.0 } +
                listOf(99.5, 99.0, 98.5, 97.8, 97.0, 96.0)
        val regime = RegimeDetector.detect(series(closes, risingVolumes(closes.size)), config)
        assertEquals(MarketEvent.CRASH, regime.event)
        assertTrue(regime.blocksEntry)
        assertEquals("CRASH", regime.blockReason())
    }

    @Test
    fun `pump detected when price rises sharply over move window`() {
        val closes =
            (0 until 60).map { 100.0 } +
                listOf(100.5, 101.0, 101.6, 102.2, 102.8, 103.5)
        val regime = RegimeDetector.detect(series(closes, risingVolumes(closes.size)), config)
        assertEquals(MarketEvent.PUMP, regime.event)
        assertTrue(regime.blocksEntry)
        assertEquals("PUMP", regime.blockReason())
    }

    @Test
    fun `low liquidity detected when last volume is a tail outlier`() {
        val closes = (0 until 60).map { 100.0 + 0.2 * it }
        val volumes = List(59) { 1000L } + listOf(50L)
        val regime = RegimeDetector.detect(series(closes, volumes), config)
        assertEquals(RegimeLiquidity.THIN, regime.liquidity)
        assertTrue(regime.blocksEntry)
        assertEquals("LOW_LIQUIDITY", regime.blockReason())
    }

    @Test
    fun `extreme volatility detected when last atr spikes versus history`() {
        val closes = (0 until 60).map { 100.0 + 0.05 * it }
        val candles =
            series(closes, risingVolumes(closes.size)).toMutableList()
        candles[candles.size - 1] =
            candle(
                open = closes.last(),
                high = closes.last() * 1.15,
                low = closes.last() * 0.85,
                close = closes.last(),
                volume = 5000L,
                index = candles.size - 1,
            )
        val regime = RegimeDetector.detect(candles, config)
        assertTrue(
            regime.volatility == RegimeVolatility.HIGH || regime.volatility == RegimeVolatility.EXTREME,
            "volatility=${regime.volatility}",
        )
        assertTrue(regime.volatility.ordinal >= RegimeVolatility.HIGH.ordinal)
    }

    @Test
    fun `insufficient candles yield fail-safe unknown regime`() {
        val closes = (0 until 10).map { 100.0 }
        val regime = RegimeDetector.detect(series(closes, risingVolumes(closes.size)), config)
        assertEquals(PerTickerRegime.UNKNOWN, regime)
        assertFalse(regime.blocksEntry)
    }
}
