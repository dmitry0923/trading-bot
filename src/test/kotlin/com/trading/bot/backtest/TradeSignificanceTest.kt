package com.trading.bot.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TradeSignificanceTest {
    @Test
    fun `empty or single trade yields empty result`() {
        val empty = TradeSignificance.bootstrap(emptyList())
        assertFalse(empty.edgeStatisticallySignificant)
        assertEquals(0, empty.tradeCount)
        assertEquals(1.0, empty.probabilityOfNoEdge, 1e-9)

        val single = TradeSignificance.bootstrap(listOf(500.0))
        assertFalse(single.edgeStatisticallySignificant)
        assertEquals(0, single.tradeCount)
    }

    @Test
    fun `consistently profitable trades yield significant edge`() {
        // 30 сделок, все положительные, малая дисперсия -> CI явно выше нуля.
        val returns = List(30) { 100.0 + (it % 3) * 5.0 }
        val result = TradeSignificance.bootstrap(returns, simulations = 2000, seed = 7)

        assertEquals(30, result.tradeCount)
        assertTrue(result.ci95Low > 0.0, "CI low should be positive, got ${result.ci95Low}")
        assertTrue(result.edgeStatisticallySignificant, "consistently profitable trades must be significant")
    }

    @Test
    fun `mixed near-zero returns are not significant`() {
        // 30 сделок с шумом вокруг нуля -> edge не значим на 95%.
        val returns = List(30) { i -> if (i % 2 == 0) 20.0 else -18.0 }
        val result = TradeSignificance.bootstrap(returns, simulations = 2000, seed = 7)

        assertEquals(30, result.tradeCount)
        assertTrue(result.probabilityOfNoEdge > 0.05, "noisy trades should have high no-edge probability")
        assertFalse(result.edgeStatisticallySignificant)
    }

    @Test
    fun `CI bracket mean and is deterministic for same seed`() {
        val returns = List(40) { 50.0 - (it % 4) * 30.0 }
        val a = TradeSignificance.bootstrap(returns, simulations = 500, seed = 42)
        val b = TradeSignificance.bootstrap(returns, simulations = 500, seed = 42)

        assertEquals(a.ci95Low, b.ci95Low, 1e-12)
        assertEquals(a.ci95High, b.ci95High, 1e-12)
        assertEquals(a.probabilityOfNoEdge, b.probabilityOfNoEdge, 1e-12)
        assertTrue(a.ci95Low <= a.meanTrade && a.meanTrade <= a.ci95High)
    }

    @Test
    fun `losing strategy is not significant`() {
        val returns = List(30) { -50.0 - (it % 3) * 10.0 }
        val result = TradeSignificance.bootstrap(returns, simulations = 2000, seed = 7)

        assertEquals(1.0, result.probabilityOfNoEdge, 0.0)
        assertFalse(result.edgeStatisticallySignificant)
        assertTrue(result.ci95High < 0.0)
    }
}
