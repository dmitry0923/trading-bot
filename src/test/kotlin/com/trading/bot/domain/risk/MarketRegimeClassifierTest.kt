package com.trading.bot.domain.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Классификация режима рынка по перцентильному рангу волатильности.
 *
 * history = [10..100 step 10] (10 значений): rank = доля строго меньших значений.
 */
class MarketRegimeClassifierTest {
    private val history = (10..100 step 10).map { it.toDouble() } // 10..100, 10 значений

    @Test
    fun `low when current below p40 percentile`() {
        // current=15: ниже 1 из 10 -> rank 10% -> LOW
        assertEquals(MarketRegime.LOW, MarketRegimeClassifier.classify(history, 15.0))
        // current=35: ниже 3 из 10 -> rank 30% -> LOW
        assertEquals(MarketRegime.LOW, MarketRegimeClassifier.classify(history, 35.0))
    }

    @Test
    fun `normal between p40 and p70`() {
        // current=45: ниже 4 из 10 -> rank 40% -> NORMAL
        assertEquals(MarketRegime.NORMAL, MarketRegimeClassifier.classify(history, 45.0))
        // current=65: ниже 6 из 10 -> rank 60% -> NORMAL
        assertEquals(MarketRegime.NORMAL, MarketRegimeClassifier.classify(history, 65.0))
    }

    @Test
    fun `volatile between p70 and p90`() {
        // current=75: ниже 7 из 10 -> rank 70% -> VOLATILE
        assertEquals(MarketRegime.VOLATILE, MarketRegimeClassifier.classify(history, 75.0))
        // current=85: ниже 8 из 10 -> rank 80% -> VOLATILE
        assertEquals(MarketRegime.VOLATILE, MarketRegimeClassifier.classify(history, 85.0))
    }

    @Test
    fun `stress at or above p90`() {
        // current=95: ниже 9 из 10 -> rank 90% -> STRESS
        assertEquals(MarketRegime.STRESS, MarketRegimeClassifier.classify(history, 95.0))
        // current=100: ниже 9 из 10 -> rank 90% -> STRESS
        assertEquals(MarketRegime.STRESS, MarketRegimeClassifier.classify(history, 100.0))
        // current=105: ниже все 10 -> rank 100% -> STRESS
        assertEquals(MarketRegime.STRESS, MarketRegimeClassifier.classify(history, 105.0))
    }

    @Test
    fun `null when history empty`() {
        assertNull(MarketRegimeClassifier.classify(emptyList(), 50.0))
    }

    @Test
    fun `custom thresholds change boundaries`() {
        // pLow=50, pNormal=80, pVolatile=95: current=40 -> rank 30% -> LOW
        val regime =
            MarketRegimeClassifier.classify(
                history,
                current = 40.0,
                pLow = 50.0,
                pNormal = 80.0,
                pVolatile = 95.0,
            )
        assertEquals(MarketRegime.LOW, regime)
    }

    @Test
    fun `percentile rank is fraction of strictly smaller values`() {
        assertEquals(0.0, MarketRegimeClassifier.percentileRank(history, 5.0))
        assertEquals(50.0, MarketRegimeClassifier.percentileRank(history, 55.0))
        assertEquals(100.0, MarketRegimeClassifier.percentileRank(history, 1000.0))
    }
}
