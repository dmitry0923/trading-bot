package com.trading.bot.domain.microstructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ObiCalculatorTest {
    @Test
    fun `equal sizes returns zero`() {
        assertEquals(0, ObiCalculator.calculate(500L, 500L)!!.compareTo(BigDecimal("0.0000")))
    }

    @Test
    fun `all bid returns 1`() {
        assertEquals(0, ObiCalculator.calculate(1000L, 0L)!!.compareTo(BigDecimal("1.0000")))
    }

    @Test
    fun `all ask returns -1`() {
        assertEquals(0, ObiCalculator.calculate(0L, 1000L)!!.compareTo(BigDecimal("-1.0000")))
    }

    @Test
    fun `null sizes returns null`() {
        assertNull(ObiCalculator.calculate(null, 100L))
        assertNull(ObiCalculator.calculate(100L, null))
    }

    @Test
    fun `zero sizes returns null`() {
        assertNull(ObiCalculator.calculate(0L, 0L))
    }

    @Test
    fun `positive when more bid liquidity`() {
        val obi = ObiCalculator.calculate(800L, 200L)!!
        assertTrue(obi > BigDecimal.ZERO)
    }

    @Test
    fun `negative when more ask liquidity`() {
        val obi = ObiCalculator.calculate(200L, 800L)!!
        assertTrue(obi < BigDecimal.ZERO)
    }

    @Test
    fun `range is clamped to minus1 plus1`() {
        assertEquals(0, ObiCalculator.calculate(10000L, 1L)!!.compareTo(BigDecimal("0.9998")))
        assertEquals(0, ObiCalculator.calculate(1L, 10000L)!!.compareTo(BigDecimal("-0.9998")))
    }

    @Test
    fun `isOpposing blocks buy when obi below negative threshold`() {
        val obi = BigDecimal("-0.6")
        assertTrue(ObiCalculator.isOpposing(obi, "buy", BigDecimal("0.5")))
    }

    @Test
    fun `isOpposing blocks sell when obi above threshold`() {
        val obi = BigDecimal("0.7")
        assertTrue(ObiCalculator.isOpposing(obi, "sell", BigDecimal("0.5")))
    }

    @Test
    fun `isOpposing allows buy when obi is positive`() {
        assertFalse(ObiCalculator.isOpposing(BigDecimal("0.3"), "buy", BigDecimal("0.5")))
    }

    @Test
    fun `isOpposing allows sell when obi is negative`() {
        assertFalse(ObiCalculator.isOpposing(BigDecimal("-0.3"), "sell", BigDecimal("0.5")))
    }

    @Test
    fun `isOpposing null obi returns false`() {
        assertFalse(ObiCalculator.isOpposing(null, "buy", BigDecimal("0.5")))
    }

    @Test
    fun `isOpposing zero threshold returns false (disabled)`() {
        assertFalse(ObiCalculator.isOpposing(BigDecimal("0.9"), "buy", BigDecimal("0.0")))
        assertFalse(ObiCalculator.isOpposing(BigDecimal("-0.9"), "sell", BigDecimal("0.0")))
    }

    @Test
    fun `isOpposing within threshold does not block`() {
        assertFalse(ObiCalculator.isOpposing(BigDecimal("-0.4"), "buy", BigDecimal("0.5")))
        assertFalse(ObiCalculator.isOpposing(BigDecimal("0.4"), "sell", BigDecimal("0.5")))
    }

    @Test
    fun `pressure returns double or null`() {
        assertEquals(0.5, ObiCalculator.pressure(750L, 250L)!!, 0.001)
        assertNull(ObiCalculator.pressure(null, 100L))
        assertNull(ObiCalculator.pressure(0L, 0L))
    }
}
