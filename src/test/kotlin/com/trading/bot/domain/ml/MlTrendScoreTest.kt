package com.trading.bot.domain.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MlTrendScoreTest {
    @Test
    fun `neutral indicators and fifty percent give point five`() {
        val vector = vector(direction = "LONG")

        assertEquals(0.5, MlTrendScore.score(vector, 0.5), 1e-9)
        assertEquals(0.5, MlTrendScore.indicatorStrength(vector), 1e-9)
    }

    @Test
    fun `bullish indicators push long score up`() {
        val vector = vector(direction = "LONG").copy(emaSlopePercent = 0.3, return20 = 4.0, macdHistogramPercent = 0.5, bbPercentB = 70.0)

        assertEquals(1.0, MlTrendScore.indicatorStrength(vector), 1e-9)
        // 0.6 * 0.8 + 0.4 * 1.0 = 0.88
        assertEquals(0.88, MlTrendScore.score(vector, 0.8), 1e-9)
    }

    @Test
    fun `same bullish indicators score short below neutral`() {
        val vector = vector(direction = "SHORT").copy(emaSlopePercent = 0.3, return20 = 4.0, macdHistogramPercent = 0.5, bbPercentB = 70.0)

        assertEquals(0.0, MlTrendScore.indicatorStrength(vector), 1e-9)
        // 0.6 * 0.8 + 0.4 * 0.0 = 0.48
        assertEquals(0.48, MlTrendScore.score(vector, 0.8), 1e-9)
    }

    @Test
    fun `bearish indicators score long below neutral and short above`() {
        val long = vector(direction = "LONG").copy(emaSlopePercent = -0.3, return20 = -4.0, macdHistogramPercent = -0.5, bbPercentB = 30.0)
        val short =
            vector(
                direction = "SHORT",
            ).copy(emaSlopePercent = -0.3, return20 = -4.0, macdHistogramPercent = -0.5, bbPercentB = 30.0)

        assertEquals(0.0, MlTrendScore.indicatorStrength(long), 1e-9)
        assertEquals(1.0, MlTrendScore.indicatorStrength(short), 1e-9)
        assertTrue(MlTrendScore.score(short, 0.6) > MlTrendScore.score(long, 0.6))
    }

    @Test
    fun `model weight dominates with conflicting indicators`() {
        // Индикаторы против LONG (сила 0.0), но модель уверена (0.9) → итог выше нейтрального.
        val vector =
            vector(
                direction = "LONG",
            ).copy(emaSlopePercent = -0.3, return20 = -4.0, macdHistogramPercent = -0.5, bbPercentB = 30.0)

        assertEquals(0.54, MlTrendScore.score(vector, 0.9), 1e-9)
    }

    @Test
    fun `probability is clamped to 0 1`() {
        val vector = vector(direction = "LONG")

        assertTrue(MlTrendScore.score(vector, 1.5) <= 1.0)
        assertTrue(MlTrendScore.score(vector, -0.5) >= 0.0)
    }

    @Test
    fun `unknown direction is neutral`() {
        val vector = vector(direction = "X")

        assertEquals(0.5, MlTrendScore.indicatorStrength(vector))
    }

    private fun vector(direction: String): MlFeatureVector =
        MlFeatureVector(
            rsi14 = 50.0,
            atrPercent = 1.0,
            macdHistogramPercent = 0.0,
            bbPercentB = 50.0,
            emaSlopePercent = 0.0,
            volatility20Percent = 1.0,
            return3 = 0.0,
            return10 = 0.0,
            return20 = 0.0,
            cbrRate = 16.0,
            brentPrice = 75.0,
            usdRub = 90.0,
            strategySignalStrength = null,
            inBlindSpotHour = 0,
            hourOfDay = 14,
            strategyAction = "",
            direction = direction,
        )
}
