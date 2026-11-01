package com.trading.bot.domain.microstructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [MicrostructureFeatures] — fail-closed на неполных/неположительных котировках
 * и знак отклонения microprice от середины.
 */
class MicrostructureFeaturesTest {
    @Test
    fun `spread requires both sides and a positive mid`() {
        assertNull(MicrostructureFeatures.spreadBps(null, BigDecimal("100")))
        assertNull(MicrostructureFeatures.spreadBps(BigDecimal("99"), null))
        assertNull(MicrostructureFeatures.spreadBps(BigDecimal("-2"), BigDecimal("-1")))
        assertNull(MicrostructureFeatures.spreadBps(BigDecimal.ZERO, BigDecimal.ZERO))
    }

    @Test
    fun `spread is positive in basis points for a normal book`() {
        val spread = MicrostructureFeatures.spreadBps(BigDecimal("99.9"), BigDecimal("100.1"))!!

        assertTrue(spread > BigDecimal.ZERO)
    }

    @Test
    fun `deviation is null without a book or sizes and positive on bid pressure`() {
        assertNull(MicrostructureFeatures.deviationBps(null, BigDecimal("100"), 10L, 10L))
        assertNull(MicrostructureFeatures.deviationBps(BigDecimal("99"), BigDecimal("100"), null, 10L))
        assertNull(MicrostructureFeatures.deviationBps(BigDecimal("99"), BigDecimal("100"), 0L, 10L))

        val positive = MicrostructureFeatures.deviationBps(BigDecimal("99"), BigDecimal("100"), 30L, 10L)!!
        assertTrue(positive > BigDecimal.ZERO)
    }
}
