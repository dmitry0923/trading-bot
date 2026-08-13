package com.trading.bot.domain.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Онлайн-калибровка порога уверенности (roadmap 13.11.8): поиск самой низкой
 * границы уверенности, при которой выборка `confidence >= c` достигает целевого
 * win rate при достаточном размере выборки.
 */
class ConfidenceCalibratorTest {
    private val target = 0.60
    private val minTrades = 5
    private val minThreshold = 0.50
    private val maxThreshold = 0.80
    private val step = 0.05

    private fun calibrate(outcomes: List<Pair<Double, Boolean>>) =
        ConfidenceCalibrator.calibrate(
            outcomes = outcomes,
            targetWinRate = target,
            minTrades = minTrades,
            minThreshold = minThreshold,
            maxThreshold = maxThreshold,
            step = step,
        )

    @Test
    fun `returns null for empty outcomes`() {
        assertNull(calibrate(emptyList()))
    }

    @Test
    fun `returns null when total outcomes below min trades`() {
        val outcomes = listOf(0.70 to true, 0.70 to true, 0.70 to true, 0.70 to true)
        assertNull(calibrate(outcomes))
    }

    @Test
    fun `returns null when even top band misses target win rate`() {
        // Только одна победа из шести — ни на одной границе выборка не даёт 0.60.
        val outcomes =
            listOf(
                0.80 to true,
                0.75 to false,
                0.70 to false,
                0.65 to false,
                0.60 to false,
                0.55 to false,
            )
        assertNull(calibrate(outcomes))
    }

    @Test
    fun `picks lowest threshold whose band reaches target win rate`() {
        val outcomes =
            listOf(
                0.80 to true,
                0.80 to true,
                0.75 to true,
                0.70 to false,
                0.65 to true,
                0.60 to false,
                0.60 to false,
                0.55 to true,
                0.50 to false,
            )
        // c=0.65: 4/5 = 0.80 -> ок; c=0.60: 4/7 = 0.571 -> нет;
        // c=0.55: 5/8 = 0.625 -> ок; c=0.50: 5/9 = 0.556 -> нет. Итог: 0.55.
        val result = calibrate(outcomes)
        assertEquals(0.55, result!!.threshold, 1e-9)
        assertEquals(8, result.sampleSize)
        assertEquals(0.63, result.winRate, 1e-9)
    }

    @Test
    fun `clamps to max threshold when only top band works`() {
        val outcomes =
            listOf(
                0.80 to true,
                0.80 to true,
                0.80 to true,
                0.80 to true,
                0.80 to true,
                0.75 to false,
                0.75 to false,
                0.75 to false,
                0.75 to false,
            )
        // c=0.80: 5/5 -> ок; c=0.75: 5/9 = 0.556 < 0.60 -> нет. Итог: 0.80.
        val result = calibrate(outcomes)
        assertEquals(0.80, result!!.threshold, 1e-9)
        assertEquals(5, result.sampleSize)
        assertEquals(1.0, result.winRate, 1e-9)
    }

    @Test
    fun `uses min bound when every band beats target`() {
        val outcomes =
            listOf(
                0.90 to true,
                0.80 to true,
                0.75 to true,
                0.70 to true,
                0.60 to true,
            )
        // На любой границе выборка из всех 5 сделок даёт win rate 1.0 -> самая низкая 0.50.
        val result = calibrate(outcomes)
        assertEquals(0.50, result!!.threshold, 1e-9)
        assertEquals(5, result.sampleSize)
        assertEquals(1.0, result.winRate, 1e-9)
    }

    @Test
    fun `rounds threshold and win rate to two decimals`() {
        val outcomes =
            listOf(
                0.80 to true,
                0.80 to true,
                0.80 to true,
                0.80 to true,
                0.80 to true,
                0.55 to false,
            )
        // target 0.80: на границе 0.55 выборка из 6 сделок даёт 5/6 = 0.833 -> 0.83.
        val result =
            ConfidenceCalibrator.calibrate(
                outcomes = outcomes,
                targetWinRate = 0.80,
                minTrades = 3,
                minThreshold = 0.50,
                maxThreshold = 0.80,
                step = 0.05,
            )
        assertEquals(0.50, result!!.threshold, 1e-9)
        assertEquals(6, result.sampleSize)
        assertEquals(0.83, result.winRate, 1e-9)
    }
}
