package com.trading.bot.domain.risk

import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ExitRulesTest {

    // ── calcSL ──────────────────────────────────────────────

    @Test
    fun `calcSL LONG CNYRUB_TOM aligns to 0_0005 grid`() {
        val sl = ExitRules.calcSL(
            entryPrice = BigDecimal("12.4200"),
            direction = PositionDirection.LONG,
            percent = BigDecimal("0.5"),
            priceStep = BigDecimal("0.0005"),
        )
        // raw = 12.42 * (1 - 0.005) = 12.3579 → alignToGrid → 12.3580
        assertEquals(0, BigDecimal("12.3580").compareTo(sl))
    }

    @Test
    fun `calcSL SHORT CNYRUB_TOM aligns to 0_0005 grid`() {
        val sl = ExitRules.calcSL(
            entryPrice = BigDecimal("12.4200"),
            direction = PositionDirection.SHORT,
            percent = BigDecimal("0.5"),
            priceStep = BigDecimal("0.0005"),
        )
        // raw = 12.42 * (1 + 0.005) = 12.4821 → alignToGrid → 12.4820
        assertEquals(0, BigDecimal("12.4820").compareTo(sl))
    }

    @Test
    fun `calcSL LONG default priceStep 0_01`() {
        val sl = ExitRules.calcSL(
            entryPrice = BigDecimal("100.50"),
            direction = PositionDirection.LONG,
            percent = BigDecimal("2.0"),
        )
        // raw = 100.50 * 0.98 = 98.49 → alignToGrid → 98.49
        assertEquals(0, BigDecimal("98.49").compareTo(sl))
    }

    // ── calcTP ──────────────────────────────────────────────

    @Test
    fun `calcTP LONG CNYRUB_TOM aligns to 0_0005 grid`() {
        val tp = ExitRules.calcTP(
            entryPrice = BigDecimal("12.4200"),
            direction = PositionDirection.LONG,
            percent = BigDecimal("1.0"),
            priceStep = BigDecimal("0.0005"),
        )
        // raw = 12.42 * 1.01 = 12.5442 → alignToGrid → 12.5440
        assertEquals(0, BigDecimal("12.5440").compareTo(tp))
    }

    @Test
    fun `calcTP SHORT CNYRUB_TOM aligns to 0_0005 grid`() {
        val tp = ExitRules.calcTP(
            entryPrice = BigDecimal("12.4200"),
            direction = PositionDirection.SHORT,
            percent = BigDecimal("1.0"),
            priceStep = BigDecimal("0.0005"),
        )
        // raw = 12.42 * 0.99 = 12.2958 → alignToGrid → 12.2960
        assertEquals(0, BigDecimal("12.2960").compareTo(tp))
    }

    @Test
    fun `calcTP LONG default priceStep`() {
        val tp = ExitRules.calcTP(
            entryPrice = BigDecimal("100.50"),
            direction = PositionDirection.LONG,
            percent = BigDecimal("4.0"),
        )
        // raw = 100.50 * 1.04 = 104.52 → alignToGrid → 104.52
        assertEquals(0, BigDecimal("104.52").compareTo(tp))
    }

    // ── alignToGrid edge cases ──────────────────────────────

    @Test
    fun `alignToGrid with zero priceStep falls back to scale 2`() {
        val sl = ExitRules.calcSL(
            entryPrice = BigDecimal("100.50"),
            direction = PositionDirection.LONG,
            percent = BigDecimal("1.0"),
            priceStep = BigDecimal("0"),
        )
        // priceStep <= 0 → setScale(2) → 99.50
        assertEquals(0, BigDecimal("99.50").compareTo(sl))
    }

    @Test
    fun `alignToGrid exactly on grid is unchanged`() {
        val sl = ExitRules.calcSL(
            entryPrice = BigDecimal("10.0000"),
            direction = PositionDirection.LONG,
            percent = BigDecimal("1.0"),
            priceStep = BigDecimal("0.01"),
        )
        // raw = 10.00 * 0.99 = 9.90 → already on grid
        assertEquals(0, BigDecimal("9.90").compareTo(sl))
    }

    // ── shouldCloseBySL ─────────────────────────────────────

    @Test
    fun `shouldCloseBySL LONG triggers when price below stop`() {
        val pos = makePos(direction = PositionDirection.LONG, stopLoss = BigDecimal("12.3580"))
        assertTrue(ExitRules.shouldCloseBySL(pos, BigDecimal("12.3500")))
    }

    @Test
    fun `shouldCloseBySL LONG does not trigger above stop`() {
        val pos = makePos(direction = PositionDirection.LONG, stopLoss = BigDecimal("12.3580"))
        assertFalse(ExitRules.shouldCloseBySL(pos, BigDecimal("12.3600")))
    }

    @Test
    fun `shouldCloseBySL LONG triggers exactly at stop`() {
        val pos = makePos(direction = PositionDirection.LONG, stopLoss = BigDecimal("12.3580"))
        assertTrue(ExitRules.shouldCloseBySL(pos, BigDecimal("12.3580")))
    }

    @Test
    fun `shouldCloseBySL SHORT triggers when price above stop`() {
        val pos = makePos(direction = PositionDirection.SHORT, stopLoss = BigDecimal("12.4820"))
        assertTrue(ExitRules.shouldCloseBySL(pos, BigDecimal("12.4900")))
    }

    @Test
    fun `shouldCloseBySL SHORT does not trigger below stop`() {
        val pos = makePos(direction = PositionDirection.SHORT, stopLoss = BigDecimal("12.4820"))
        assertFalse(ExitRules.shouldCloseBySL(pos, BigDecimal("12.4700")))
    }

    @Test
    fun `shouldCloseBySL with null stopLoss returns false`() {
        val pos = makePos(direction = PositionDirection.LONG)
        assertFalse(ExitRules.shouldCloseBySL(pos, BigDecimal("10.00")))
    }

    // ── shouldCloseByTP ─────────────────────────────────────

    @Test
    fun `shouldCloseByTP LONG triggers when price above takeProfit`() {
        val pos = makePos(direction = PositionDirection.LONG, takeProfit = BigDecimal("12.5440"))
        assertTrue(ExitRules.shouldCloseByTP(pos, BigDecimal("12.5500")))
    }

    @Test
    fun `shouldCloseByTP LONG does not trigger below takeProfit`() {
        val pos = makePos(direction = PositionDirection.LONG, takeProfit = BigDecimal("12.5440"))
        assertFalse(ExitRules.shouldCloseByTP(pos, BigDecimal("12.5400")))
    }

    @Test
    fun `shouldCloseByTP SHORT triggers when price below takeProfit`() {
        val pos = makePos(direction = PositionDirection.SHORT, takeProfit = BigDecimal("12.2960"))
        assertTrue(ExitRules.shouldCloseByTP(pos, BigDecimal("12.2900")))
    }

    @Test
    fun `shouldCloseByTP with null takeProfit returns false`() {
        val pos = makePos(direction = PositionDirection.LONG)
        assertFalse(ExitRules.shouldCloseByTP(pos, BigDecimal("100.00")))
    }

    // ── shouldCloseByTrailing ────────────────────────────────

    @Test
    fun `shouldCloseByTrailing LONG triggers when price below trailing`() {
        val pos = makePos(direction = PositionDirection.LONG, trailingStop = BigDecimal("12.3755"))
        assertTrue(ExitRules.shouldCloseByTrailing(pos, BigDecimal("12.3700")))
    }

    @Test
    fun `shouldCloseByTrailing with null trailing returns false`() {
        val pos = makePos(direction = PositionDirection.LONG)
        assertFalse(ExitRules.shouldCloseByTrailing(pos, BigDecimal("10.00")))
    }

    // ── effectiveSl ──────────────────────────────────────────

    @Test
    fun `effectiveSl returns hard stop when no trailing`() {
        val pos = makePos(direction = PositionDirection.LONG, stopLoss = BigDecimal("12.3580"))
        assertEquals(0, BigDecimal("12.3580").compareTo(ExitRules.effectiveSl(pos)))
    }

    @Test
    fun `effectiveSl LONG returns trailing when above hard stop`() {
        val pos = makePos(
            direction = PositionDirection.LONG,
            stopLoss = BigDecimal("12.3580"),
            trailingStop = BigDecimal("12.3800"),
        )
        assertEquals(0, BigDecimal("12.3800").compareTo(ExitRules.effectiveSl(pos)))
    }

    @Test
    fun `effectiveSl LONG returns hard stop when trailing is below`() {
        val pos = makePos(
            direction = PositionDirection.LONG,
            stopLoss = BigDecimal("12.3580"),
            trailingStop = BigDecimal("12.3000"),
        )
        assertEquals(0, BigDecimal("12.3580").compareTo(ExitRules.effectiveSl(pos)))
    }

    @Test
    fun `effectiveSl SHORT returns trailing when below hard stop`() {
        val pos = makePos(
            direction = PositionDirection.SHORT,
            stopLoss = BigDecimal("12.4820"),
            trailingStop = BigDecimal("12.4500"),
        )
        assertEquals(0, BigDecimal("12.4500").compareTo(ExitRules.effectiveSl(pos)))
    }

    @Test
    fun `effectiveSl with no stopLoss returns null`() {
        val pos = makePos(direction = PositionDirection.LONG)
        assertNull(ExitRules.effectiveSl(pos))
    }

    // ── exchangeSlCovers / exchangeTpCovers ──────────────────

    @Test
    fun `exchangeSlCovers true when order price matches effectiveSl`() {
        val pos = makePos(
            direction = PositionDirection.LONG,
            stopLoss = BigDecimal("12.3580"),
            slOrderId = "sl-1",
            slOrderPrice = BigDecimal("12.3580"),
        )
        assertTrue(ExitRules.exchangeSlCovers(pos))
    }

    @Test
    fun `exchangeSlCovers false when order price differs`() {
        val pos = makePos(
            direction = PositionDirection.LONG,
            stopLoss = BigDecimal("12.3580"),
            slOrderId = "sl-1",
            slOrderPrice = BigDecimal("12.3500"),
        )
        assertFalse(ExitRules.exchangeSlCovers(pos))
    }

    @Test
    fun `exchangeSlCovers false when no slOrderId`() {
        val pos = makePos(
            direction = PositionDirection.LONG,
            stopLoss = BigDecimal("12.3580"),
        )
        assertFalse(ExitRules.exchangeSlCovers(pos))
    }

    @Test
    fun `exchangeTpCovers true when order price matches takeProfit`() {
        val pos = makePos(
            direction = PositionDirection.LONG,
            takeProfit = BigDecimal("12.5440"),
            tpOrderId = "tp-1",
            tpOrderPrice = BigDecimal("12.5440"),
        )
        assertTrue(ExitRules.exchangeTpCovers(pos))
    }

    @Test
    fun `exchangeTpCovers false when no tpOrderId`() {
        val pos = makePos(
            direction = PositionDirection.LONG,
            takeProfit = BigDecimal("12.5440"),
        )
        assertFalse(ExitRules.exchangeTpCovers(pos))
    }

    // ── updateTrailingStop ──────────────────────────────────

    @Test
    fun `updateTrailingStop LONG uses priceStep scale`() {
        val pos = makePos(direction = PositionDirection.LONG)
        ExitRules.updateTrailingStop(pos, BigDecimal("12.5005"), 1.0, BigDecimal("0.0005"))
        // newStop = 12.5005 * 0.99 = 12.375495 → alignToGrid(0.0005) → 12.3755
        assertEquals(0, BigDecimal("12.3755").compareTo(pos.trailingStopPrice))
    }

    @Test
    fun `updateTrailingStop SHORT uses priceStep scale`() {
        val pos = makePos(direction = PositionDirection.SHORT)
        ExitRules.updateTrailingStop(pos, BigDecimal("12.5005"), 1.0, BigDecimal("0.0005"))
        // newStop = 12.5005 * 1.01 = 12.625505 → alignToGrid(0.0005) → 12.6255
        assertEquals(0, BigDecimal("12.6255").compareTo(pos.trailingStopPrice))
    }

    @Test
    fun `updateTrailingStop default priceStep 0_01`() {
        val pos = makePos(direction = PositionDirection.LONG)
        ExitRules.updateTrailingStop(pos, BigDecimal("100.50"), 2.0)
        // newStop = 100.50 * 0.98 = 98.49 → alignToGrid(0.01) → 98.49
        assertEquals(0, BigDecimal("98.49").compareTo(pos.trailingStopPrice))
    }

    // ── helpers ──────────────────────────────────────────────

    private fun makePos(
        direction: PositionDirection = PositionDirection.LONG,
        stopLoss: BigDecimal? = null,
        takeProfit: BigDecimal? = null,
        trailingStop: BigDecimal? = null,
        slOrderId: String? = null,
        slOrderPrice: BigDecimal? = null,
        tpOrderId: String? = null,
        tpOrderPrice: BigDecimal? = null,
    ) = Position(
        id = 1L,
        ticker = "CNYRUB_TOM",
        direction = direction,
        quantity = 1,
        entryPrice = BigDecimal("12.4200"),
        instrumentType = InstrumentType.STOCK,
        status = PositionStatus.OPEN,
        stopLoss = stopLoss,
        takeProfit = takeProfit,
        trailingStopPrice = trailingStop,
        slOrderId = slOrderId,
        slOrderPrice = slOrderPrice,
        tpOrderId = tpOrderId,
        tpOrderPrice = tpOrderPrice,
    )
}
