package com.trading.bot.application

import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PnlCalculatorCommissionTest {
    private val lotBasedNoComm = PnlCalculator.lotBased(lotSize = { 1000L })

    private val lotBasedWithComm =
        PnlCalculator.lotBased(
            lotSize = { 1000L },
            commissionRub = { BigDecimal("10.0") },
        )

    private fun longPos(ticker: String = "CNYRUB_TOM") =
        Position(
            id = 1L,
            ticker = ticker,
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("12.50"),
            instrumentType = InstrumentType.FX,
        )

    private fun shortPos(ticker: String = "CNYRUB_TOM") =
        Position(
            id = 2L,
            ticker = ticker,
            direction = PositionDirection.SHORT,
            quantity = 1,
            entryPrice = BigDecimal("12.50"),
            instrumentType = InstrumentType.FX,
        )

    // ── No commission (backward compatible) ───────────────────────────────

    @Test
    fun `lotBased without commission is pure price delta`() {
        val pnl = lotBasedNoComm.pnl(longPos(), BigDecimal("12.50"), BigDecimal("12.625"), BigDecimal(1))
        // (12.625 - 12.50) * 1 * 1000 = 125
        assertEquals(0, pnl.compareTo(BigDecimal("125")))
    }

    // ── LONG with commission ──────────────────────────────────────────────

    @Test
    fun `LONG TP at 12_625 net P and L is 105 after 20 commission`() {
        val pnl = lotBasedWithComm.pnl(longPos(), BigDecimal("12.50"), BigDecimal("12.625"), BigDecimal(1))
        // gross = (12.625 - 12.50) * 1 * 1000 = 125
        // commission = 10 * 1 * 2 = 20
        // net = 125 - 20 = 105
        assertEquals(0, pnl.compareTo(BigDecimal("105")))
    }

    @Test
    fun `LONG SL at 12_4375 net P and L is -82_5 after 20 commission`() {
        val pnl = lotBasedWithComm.pnl(longPos(), BigDecimal("12.50"), BigDecimal("12.4375"), BigDecimal(1))
        // gross = (12.4375 - 12.50) * 1 * 1000 = -62.5
        // commission = 10 * 1 * 2 = 20
        // net = -62.5 - 20 = -82.5
        assertEquals(0, pnl.compareTo(BigDecimal("-82.5")))
    }

    // ── SHORT with commission ─────────────────────────────────────────────

    @Test
    fun `SHORT TP at 12_375 net P and L is 105 after 20 commission`() {
        val pnl = lotBasedWithComm.pnl(shortPos(), BigDecimal("12.50"), BigDecimal("12.375"), BigDecimal(1))
        // gross = (12.50 - 12.375) * 1 * 1000 = 125
        // commission = 10 * 1 * 2 = 20
        // net = 125 - 20 = 105
        assertEquals(0, pnl.compareTo(BigDecimal("105")))
    }

    @Test
    fun `SHORT SL at 12_5625 net P and L is -82_5 after 20 commission`() {
        val pnl = lotBasedWithComm.pnl(shortPos(), BigDecimal("12.50"), BigDecimal("12.5625"), BigDecimal(1))
        // gross = (12.50 - 12.5625) * 1 * 1000 = -62.5
        // commission = 10 * 1 * 2 = 20
        // net = -62.5 - 20 = -82.5
        assertEquals(0, pnl.compareTo(BigDecimal("-82.5")))
    }

    // ── Multi-lot ─────────────────────────────────────────────────────────

    @Test
    fun `2 lots LONG TP commission scales with quantity`() {
        val pnl = lotBasedWithComm.pnl(longPos(), BigDecimal("12.50"), BigDecimal("12.625"), BigDecimal(2))
        // gross = (12.625 - 12.50) * 2 * 1000 = 250
        // commission = 10 * 2 * 2 = 40
        // net = 250 - 40 = 210
        assertEquals(0, pnl.compareTo(BigDecimal("210")))
    }

    // ── Zero commission instrument ────────────────────────────────────────

    @Test
    fun `instrument with null commission has no deduction`() {
        val calc =
            PnlCalculator.lotBased(
                lotSize = { 1000L },
                commissionRub = { null },
            )
        val pnl = calc.pnl(longPos("SBER"), BigDecimal("12.50"), BigDecimal("12.625"), BigDecimal(1))
        // gross only: (12.625 - 12.50) * 1 * 1000 = 125
        assertEquals(0, pnl.compareTo(BigDecimal("125")))
    }

    // ── Commission exceeds profit (deep loss) ─────────────────────────────

    @Test
    fun `deep SL results in loss greater than price delta due to commission`() {
        val pnl = lotBasedWithComm.pnl(longPos(), BigDecimal("12.50"), BigDecimal("12.00"), BigDecimal(1))
        // gross = (12.00 - 12.50) * 1 * 1000 = -500
        // commission = 10 * 1 * 2 = 20
        // net = -500 - 20 = -520
        assertEquals(0, pnl.compareTo(BigDecimal("-520")))
    }

    // ── plain() still has no commission ───────────────────────────────────

    @Test
    fun `plain calculator has no commission deduction`() {
        val plain = PnlCalculator.plain()
        val pnl = plain.pnl(longPos(), BigDecimal("12.50"), BigDecimal("12.625"), BigDecimal(1))
        // (12.625 - 12.50) * 1 * 1 = 0.125
        assertEquals(0, pnl.compareTo(BigDecimal("0.125")))
    }
}
