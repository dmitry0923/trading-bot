package com.trading.bot.backtest.portfolio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Регрессионные тесты отрезки dev-части для holdout-протокола IS70/OOS30
 * (`devSlice` в PortfolioMomentumService.kt): holdout не должен попадать в
 * walk-forward, прежнее поведение (без параметра) — не меняется.
 */
class PortfolioHoldoutSliceTest {
    @Test
    fun `null holdout fraction keeps the whole history unchanged`() {
        val bars = (0 until 100).toList()
        assertSame(bars, devSlice(bars, null))
    }

    @Test
    fun `holdout fraction 0 30 keeps the first 70 percent of history`() {
        val bars = (0 until 1000).toList()
        val dev = devSlice(bars, 0.30)
        assertEquals(700, dev.size)
        assertEquals(0, dev.first())
        assertEquals(699, dev.last())
    }

    @Test
    fun `tiny history keeps at least one dev bar`() {
        val dev = devSlice(listOf(1), 0.30)
        assertEquals(listOf(1), dev)
    }

    @Test
    fun `holdout fraction out of range is rejected`() {
        val bars = (0 until 10).toList()
        assertThrows(IllegalArgumentException::class.java) { devSlice(bars, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { devSlice(bars, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { devSlice(bars, 1.5) }
    }
}
