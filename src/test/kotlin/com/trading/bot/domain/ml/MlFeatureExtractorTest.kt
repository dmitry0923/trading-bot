package com.trading.bot.domain.ml

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class MlFeatureExtractorTest {
    @Test
    fun `extract returns null when fewer than 30 candles`() {
        val candles = candles(List(29) { 100.0 })
        assertNull(MlFeatureExtractor.extract(candles, lookbackBars = 30))
    }

    @Test
    fun `extract on monotonic uptrend gives positive slope returns and high rsi`() {
        val closes = (1..70).map { 100.0 + it * 0.5 }
        val features = requireNotNull(MlFeatureExtractor.extract(candles(closes), lookbackBars = 30))
        assertTrue(features.emaSlopePercent > 0.0, "emaSlope=$features.emaSlopePercent")
        assertTrue(features.return20 > 0.0, "return20=$features.return20")
        assertTrue(features.return10 > 0.0)
        assertTrue(features.return3 > 0.0)
        assertTrue(features.rsi14 > 50.0, "rsi14=${features.rsi14}")
    }

    @Test
    fun `extract on monotonic downtrend gives negative slope and low rsi`() {
        val closes = (1..70).map { 200.0 - it * 0.5 }
        val features = requireNotNull(MlFeatureExtractor.extract(candles(closes), lookbackBars = 30))
        assertTrue(features.emaSlopePercent < 0.0, "emaSlope=$features.emaSlopePercent")
        assertTrue(features.return20 < 0.0)
        assertTrue(features.rsi14 < 50.0, "rsi14=${features.rsi14}")
    }

    @Test
    fun `flat prices give zero volatility, atr and neutral bollinger`() {
        val features = requireNotNull(MlFeatureExtractor.extract(flatCandles(60), lookbackBars = 30))
        assertEquals(0.0, features.volatility20Percent, 1e-9)
        assertEquals(0.0, features.atrPercent, 1e-9)
        assertEquals(50.0, features.bbPercentB, 1e-9)
        assertEquals(0.0, features.return20, 1e-9)
        assertEquals(0.0, features.return10, 1e-9)
        assertEquals(0.0, features.return3, 1e-9)
    }

    @Test
    fun `bb percent b is between 0 and 100 for trending prices`() {
        val closes = (1..70).map { 100.0 + it * 0.5 }
        val features = requireNotNull(MlFeatureExtractor.extract(candles(closes), lookbackBars = 30))
        assertTrue(features.bbPercentB in 0.0..100.0, "bbPercentB=${features.bbPercentB}")
    }

    @Test
    fun `atr and macd features are normalized to percent of price`() {
        val closes = (1..70).map { 100.0 + it * 0.5 }
        val features = requireNotNull(MlFeatureExtractor.extract(candles(closes), lookbackBars = 30))
        assertTrue(features.atrPercent >= 0.0)
        assertTrue(features.macdHistogramPercent.isFinite())
        assertNotNull(features)
    }

    private fun candles(closes: List<Double>): List<Candle> {
        val start = LocalDateTime.of(2026, 1, 1, 10, 0)
        return closes.mapIndexed { i, c ->
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = bd(c),
                highPrice = bd(c * 1.005),
                lowPrice = bd(c * 0.995),
                closePrice = bd(c),
                volume = 1000L,
                time = start.plusMinutes(10L * i),
            )
        }
    }

    private fun flatCandles(count: Int): List<Candle> {
        val start = LocalDateTime.of(2026, 1, 1, 10, 0)
        return (0 until count).map { i ->
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = bd(100.0),
                highPrice = bd(100.0),
                lowPrice = bd(100.0),
                closePrice = bd(100.0),
                volume = 1000L,
                time = start.plusMinutes(10L * i),
            )
        }
    }

    private fun bd(value: Double): BigDecimal = BigDecimal.valueOf(value)
}
