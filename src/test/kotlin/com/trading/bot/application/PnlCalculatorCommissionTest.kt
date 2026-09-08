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

    // ── futures with commission ────────────────────────────────

    private val futuresNoComm = PnlCalculator.futures(pointValue = { BigDecimal("1000.0") })

    private val futuresWithComm =
        PnlCalculator.futures(
            pointValue = { BigDecimal("1000.0") },
            commissionRub = { BigDecimal("1.0") },
        )

    private fun futuresPos(ticker: String = "CNYRUBF") =
        Position(
            id = 3L,
            ticker = ticker,
            direction = PositionDirection.LONG,
            quantity = 3,
            entryPrice = BigDecimal("12.50"),
            instrumentType = InstrumentType.FUTURES,
        )

    @Test
    fun `futures without commission is pure price delta times pointValue`() {
        val pnl = futuresNoComm.pnl(futuresPos(), BigDecimal("12.50"), BigDecimal("13.00"), BigDecimal(3))
        // (13.00 - 12.50) * 1000 * 3 = 1500
        assertEquals(0, pnl.compareTo(BigDecimal("1500")))
    }

    @Test
    fun `futures LONG TP deducts round-trip commission per contract`() {
        val pnl = futuresWithComm.pnl(futuresPos(), BigDecimal("12.50"), BigDecimal("13.00"), BigDecimal(3))
        // gross = (13.00 - 12.50) * 1000 * 3 = 1500
        // commission = 1.0 * 3 * 2 = 6
        assertEquals(0, pnl.compareTo(BigDecimal("1494")))
    }

    @Test
    fun `futures SHORT SL loss is deepened by commission`() {
        val short =
            Position(
                id = 4L,
                ticker = "RI",
                direction = PositionDirection.SHORT,
                quantity = 2,
                entryPrice = BigDecimal("120.00"),
                instrumentType = InstrumentType.FUTURES,
            )
        val pnl = futuresWithComm.pnl(short, BigDecimal("120.00"), BigDecimal("121.00"), BigDecimal(2))
        // gross = (120.00 - 121.00) * 1000 * 2 = -2000
        // commission = 1.0 * 2 * 2 = 4
        assertEquals(0, pnl.compareTo(BigDecimal("-2004")))
    }

    @Test
    fun `futures null commission has no deduction`() {
        val calc =
            PnlCalculator.futures(
                pointValue = { BigDecimal("1000.0") },
                commissionRub = { null },
            )
        val pnl = calc.pnl(futuresPos(), BigDecimal("12.50"), BigDecimal("13.00"), BigDecimal(3))
        // gross only: 1500
        assertEquals(0, pnl.compareTo(BigDecimal("1500")))
    }

    // ── futures with funding ──────────────────────────────────────────────

    private val futuresWithFunding =
        PnlCalculator.futures(
            pointValue = { BigDecimal("1000.0") },
            commissionRub = { BigDecimal("1.0") },
            fundingRubPerContractPerDay = { BigDecimal("0.5") },
        )

    private fun futuresPosCrossedClearing(): Position =
        futuresPos().apply {
            openedAt = java.time.LocalDateTime.of(2026, 9, 8, 10, 0) // вт, до клиринга 18:45
            closedAt = java.time.LocalDateTime.of(2026, 9, 9, 17, 0) // ср, до клиринга 18:45 → ровно 1 клиринг (вт)
        }

    private fun futuresPosIntraday(): Position =
        futuresPos().apply {
            openedAt = java.time.LocalDateTime.of(2026, 9, 8, 10, 0)
            closedAt = java.time.LocalDateTime.of(2026, 9, 8, 12, 0)
        }

    @Test
    fun `futures funding is deducted per clearing crossed`() {
        val pnl = futuresWithFunding.pnl(futuresPosCrossedClearing(), BigDecimal("12.50"), BigDecimal("13.00"), BigDecimal(3))
        // gross = 1500; commission = 6; funding = 0.5 * 3 qty * 1 clearing = 1.5
        assertEquals(0, pnl.compareTo(BigDecimal("1492.5")))
    }

    @Test
    fun `intraday futures pays no funding`() {
        val pnl = futuresWithFunding.pnl(futuresPosIntraday(), BigDecimal("12.50"), BigDecimal("13.00"), BigDecimal(3))
        // gross 1500 - commission 6 - funding 0 (внутридневная, клиринга не пережила)
        assertEquals(0, pnl.compareTo(BigDecimal("1494")))
    }

    @Test
    fun `futures funding absent (null) has no deduction`() {
        val calc =
            PnlCalculator.futures(
                pointValue = { BigDecimal("1000.0") },
                commissionRub = { BigDecimal("1.0") },
                fundingRubPerContractPerDay = { null },
            )
        val pnl = calc.pnl(futuresPosCrossedClearing(), BigDecimal("12.50"), BigDecimal("13.00"), BigDecimal(3))
        assertEquals(0, pnl.compareTo(BigDecimal("1494")))
    }
}
