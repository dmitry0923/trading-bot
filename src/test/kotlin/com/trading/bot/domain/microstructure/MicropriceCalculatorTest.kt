package com.trading.bot.domain.microstructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MicropriceCalculatorTest {
    @Test
    fun `equal sizes returns mid-price`() {
        val mp = MicropriceCalculator.calculate(
            bid = BigDecimal("100.00"),
            ask = BigDecimal("100.10"),
            bidSize = 500L,
            askSize = 500L,
        )!!
        val mid = BigDecimal("100.05")
        assertEquals(0, mid.compareTo(mp))
    }

    @Test
    fun `more bid size shifts microprice up`() {
        val mp = MicropriceCalculator.calculate(
            bid = BigDecimal("100.00"),
            ask = BigDecimal("100.10"),
            bidSize = 900L,
            askSize = 100L,
        )!!
        assertTrue(mp > BigDecimal("100.05"))
    }

    @Test
    fun `more ask size shifts microprice down`() {
        val mp = MicropriceCalculator.calculate(
            bid = BigDecimal("100.00"),
            ask = BigDecimal("100.10"),
            bidSize = 100L,
            askSize = 900L,
        )!!
        assertTrue(mp < BigDecimal("100.05"))
    }

    @Test
    fun `microprice always between bid and ask`() {
        val bid = BigDecimal("99.50")
        val ask = BigDecimal("100.50")
        for (bs in listOf(1L, 10L, 100L, 1000L)) {
            for (as_ in listOf(1L, 10L, 100L, 1000L)) {
                val mp = MicropriceCalculator.calculate(bid, ask, bs, as_)!!
                assertTrue(mp > bid, "mp=$mp must be above bid=$bid for bs=$bs, as_=$as_")
                assertTrue(mp < ask, "mp=$mp must be below ask=$ask for bs=$bs, as_=$as_")
            }
        }
    }

    @Test
    fun `null bid or ask returns null`() {
        assertNull(MicropriceCalculator.calculate(null, BigDecimal("100"), 100, 100))
        assertNull(MicropriceCalculator.calculate(BigDecimal("100"), null, 100, 100))
    }

    @Test
    fun `zero or negative sizes return null`() {
        assertNull(MicropriceCalculator.calculate(BigDecimal("99"), BigDecimal("101"), 0L, 100L))
        assertNull(MicropriceCalculator.calculate(BigDecimal("99"), BigDecimal("101"), 100L, 0L))
        assertNull(MicropriceCalculator.calculate(BigDecimal("99"), BigDecimal("101"), 100L, -1L))
    }

    @Test
    fun `bid greater or equal ask returns null`() {
        assertNull(MicropriceCalculator.calculate(BigDecimal("100"), BigDecimal("100"), 100, 100))
        assertNull(MicropriceCalculator.calculate(BigDecimal("101"), BigDecimal("100"), 100, 100))
    }

    @Test
    fun `extreme imbalance`() {
        val mp = MicropriceCalculator.calculate(
            bid = BigDecimal("100.00"),
            ask = BigDecimal("100.10"),
            bidSize = 9999L,
            askSize = 1L,
        )!!
        assertTrue(mp > BigDecimal("100.099"))
    }

    @Test
    fun `deviation returns positive when more bid liquidity`() {
        val dev = MicropriceCalculator.deviation(
            bid = BigDecimal("100.00"),
            ask = BigDecimal("100.10"),
            bidSize = 900L,
            askSize = 100L,
        )!!
        assertTrue(dev > BigDecimal.ZERO)
    }

    @Test
    fun `deviation is zero for equal sizes`() {
        val dev = MicropriceCalculator.deviation(
            bid = BigDecimal("100.00"),
            ask = BigDecimal("100.10"),
            bidSize = 500L,
            askSize = 500L,
        )!!
        assertEquals(0, dev.compareTo(BigDecimal.ZERO))
    }
}
