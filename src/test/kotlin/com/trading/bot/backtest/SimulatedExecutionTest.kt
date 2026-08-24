package com.trading.bot.backtest

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class SimulatedExecutionTest {
    private fun candle(
        high: String,
        low: String,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal("100"),
            highPrice = BigDecimal(high),
            lowPrice = BigDecimal(low),
            closePrice = BigDecimal("100"),
            volume = 1000L,
            time = LocalDateTime.now(),
        )

    @Test
    fun `estimateHalfSpread is a quarter of the candle range`() {
        // Диапазон 110-90 = 20 → halfSpread = 5.
        assertEquals(
            0,
            BigDecimal("5.00000000").compareTo(SimulatedExecution.estimateHalfSpread(candle("110", "90"), BigDecimal("100"))),
        )
    }

    @Test
    fun `volatile candle yields wider spread than calm candle`() {
        val volatile = SimulatedExecution.estimateHalfSpread(candle("110", "90"), BigDecimal("100"))
        val calm = SimulatedExecution.estimateHalfSpread(candle("101", "99"), BigDecimal("100"))
        assertTrue(volatile > calm, "wide range must give wider spread: volatile=$volatile calm=$calm")
    }

    @Test
    fun `flat candle falls back to reference times slippage rate over two`() {
        // Плоская свеча (high == low) → fallback reference * 0.1% / 2 = 0.05 при ref=100.
        val fallback = SimulatedExecution.estimateHalfSpread(candle("100", "100"), BigDecimal("100"))
        assertEquals(0, BigDecimal("0.05000000").compareTo(fallback))
        // Fallback пропорционален reference.
        assertEquals(0, BigDecimal("0.50000000").compareTo(SimulatedExecution.estimateHalfSpread(candle("92000", "92000"), BigDecimal("1000"))))
    }

    @Test
    fun `realisticFill buys above and sells below mid`() {
        val buy = SimulatedExecution.realisticFill(BigDecimal("100"), isBuy = true, halfSpread = BigDecimal("0.5"))
        assertEquals(0, BigDecimal("100.5").compareTo(buy.price))
        val sell = SimulatedExecution.realisticFill(BigDecimal("100"), isBuy = false, halfSpread = BigDecimal("0.5"))
        assertEquals(0, BigDecimal("99.5").compareTo(sell.price))
    }
}
