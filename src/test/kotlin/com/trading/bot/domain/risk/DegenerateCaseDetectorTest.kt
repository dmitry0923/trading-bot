package com.trading.bot.domain.risk

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class DegenerateCaseDetectorTest {
    private fun candle(
        open: Double = 100.0,
        close: Double = 100.0,
        volume: Long = 1000L,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(open),
            highPrice = BigDecimal(maxOf(open, close)),
            lowPrice = BigDecimal(minOf(open, close)),
            closePrice = BigDecimal(close),
            volume = volume,
            time = LocalDateTime.of(2026, 1, 1, 10, 0),
        )

    @Test
    fun `spread percent computed from ask and bid`() {
        val spread = DegenerateCaseDetector.spreadPercent(BigDecimal("99"), BigDecimal("101"), BigDecimal("100"))
        assertEquals(BigDecimal("0.019802"), spread)
    }

    @Test
    fun `spread is zero when both quotes missing`() {
        assertEquals(BigDecimal.ZERO, DegenerateCaseDetector.spreadPercent(null, null, BigDecimal("100")))
    }

    @Test
    fun `spread falls back to current price when one side missing`() {
        assertEquals(BigDecimal("0.009901"), DegenerateCaseDetector.spreadPercent(null, BigDecimal("101"), BigDecimal("100")))
        assertEquals(BigDecimal("0.010000"), DegenerateCaseDetector.spreadPercent(BigDecimal("99"), null, BigDecimal("100")))
    }

    @Test
    fun `spread is zero when quotes inverted or non positive`() {
        assertEquals(BigDecimal.ZERO, DegenerateCaseDetector.spreadPercent(BigDecimal("101"), BigDecimal("99"), BigDecimal("100")))
        assertEquals(BigDecimal.ZERO, DegenerateCaseDetector.spreadPercent(BigDecimal("0"), BigDecimal("101"), BigDecimal("100")))
        assertEquals(BigDecimal.ZERO, DegenerateCaseDetector.spreadPercent(BigDecimal("99"), BigDecimal("0"), BigDecimal("100")))
    }

    @Test
    fun `wide spread detected above threshold`() {
        val bid = BigDecimal("99")
        val ask = BigDecimal("101")
        assertTrue(DegenerateCaseDetector.isWideSpread(bid, ask, BigDecimal("100"), maxSpreadPercent = BigDecimal("1.0")))
        assertFalse(DegenerateCaseDetector.isWideSpread(bid, ask, BigDecimal("100"), maxSpreadPercent = BigDecimal("3.0")))
    }

    @Test
    fun `wide spread check disabled at non positive threshold`() {
        assertFalse(DegenerateCaseDetector.isWideSpread(BigDecimal("50"), BigDecimal("100"), BigDecimal("100"), maxSpreadPercent = BigDecimal("0.0")))
        assertFalse(DegenerateCaseDetector.isWideSpread(BigDecimal("50"), BigDecimal("100"), BigDecimal("100"), maxSpreadPercent = BigDecimal("-1.0")))
    }

    @Test
    fun `gap detected when last open far from previous close`() {
        val candles = listOf(candle(open = 100.0, close = 100.0), candle(open = 105.0, close = 106.0))
        assertTrue(DegenerateCaseDetector.isGap(candles, maxGapPercent = BigDecimal("3.0")))
        assertFalse(DegenerateCaseDetector.isGap(candles, maxGapPercent = BigDecimal("6.0")))
    }

    @Test
    fun `gap fails open on insufficient candles or zero prev close`() {
        assertFalse(DegenerateCaseDetector.isGap(emptyList(), maxGapPercent = BigDecimal("3.0")))
        assertFalse(DegenerateCaseDetector.isGap(listOf(candle(open = 100.0, close = 100.0)), maxGapPercent = BigDecimal("3.0")))
        val prevZero = listOf(candle(open = 0.0, close = 0.0), candle(open = 100.0, close = 100.0))
        assertFalse(DegenerateCaseDetector.isGap(prevZero, maxGapPercent = BigDecimal("3.0")))
    }

    @Test
    fun `gap check disabled at non positive threshold`() {
        val candles = listOf(candle(open = 100.0, close = 100.0), candle(open = 105.0, close = 106.0))
        assertFalse(DegenerateCaseDetector.isGap(candles, maxGapPercent = BigDecimal("0.0")))
    }

    @Test
    fun `depositary pause detected on consecutive zero volume bars`() {
        val candles = listOf(candle(volume = 1000L), candle(volume = 0L), candle(volume = 0L), candle(volume = 0L))
        assertTrue(DegenerateCaseDetector.isDepositaryPause(candles, consecutiveZeroVolumeBars = 3))
        assertFalse(DegenerateCaseDetector.isDepositaryPause(candles, consecutiveZeroVolumeBars = 4))
    }

    @Test
    fun `depositary pause fails open on insufficient candles`() {
        assertFalse(DegenerateCaseDetector.isDepositaryPause(emptyList(), consecutiveZeroVolumeBars = 3))
        assertFalse(DegenerateCaseDetector.isDepositaryPause(listOf(candle(volume = 0L)), consecutiveZeroVolumeBars = 3))
    }

    @Test
    fun `depositary pause check disabled at non positive threshold`() {
        val candles = listOf(candle(volume = 0L), candle(volume = 0L), candle(volume = 0L))
        assertFalse(DegenerateCaseDetector.isDepositaryPause(candles, consecutiveZeroVolumeBars = 0))
        assertFalse(DegenerateCaseDetector.isDepositaryPause(candles, consecutiveZeroVolumeBars = -1))
    }
}
