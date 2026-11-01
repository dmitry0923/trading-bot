package com.trading.bot.application

import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [PnlCalculator] — формулы P&L закрытия: round-trip комиссия, направленный funding
 * (MOEX: положительная ставка = LONG платит), пропуск выходных в клирингах и
 * fail-open при отсутствии funding (`onFundingUnknown` + нулевой заряд).
 */
class PnlCalculatorTest {
    private fun position(
        direction: PositionDirection,
        openedAt: LocalDateTime,
        closedAt: LocalDateTime,
        quantity: Int = 2,
    ): Position =
        Position(
            ticker = "Si",
            direction = direction,
            quantity = quantity,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.FUTURES,
            status = com.trading.bot.model.PositionStatus.OPEN,
            openedAt = openedAt,
            closedAt = closedAt,
            closeReason = CloseReason.STRATEGY_CLOSE,
        )

    @Test
    fun `futures pnl charges long funding and credits short funding`() {
        val opened = LocalDateTime.of(2026, 10, 5, 10, 0)
        val closed = LocalDateTime.of(2026, 10, 7, 10, 0)
        var clearings = 0
        val calculator =
            PnlCalculator.futures(
                pointValue = { BigDecimal("1000") },
                commissionRub = { BigDecimal("1.5") },
                fundingPerClearing = { _, dates ->
                    clearings = dates.size
                    BigDecimal("2.0")
                },
            )

        val long =
            calculator.pnl(position(PositionDirection.LONG, opened, closed), BigDecimal("100"), BigDecimal("110"), BigDecimal("2"))
        val short =
            calculator.pnl(position(PositionDirection.SHORT, opened, closed), BigDecimal("100"), BigDecimal("110"), BigDecimal("2"))

        assertEquals(2, clearings, "пн-вт = два клиринга")
        // 10 пунктов × 1000 × 2 лота − комиссия 1.5×2×2 − funding 2.0×2 (LONG платит)
        assertEquals(0, BigDecimal("19990").compareTo(long))
        // SHORT: −20000 − 6 + 4
        assertEquals(0, BigDecimal("-20002").compareTo(short))
    }

    @Test
    fun `futures pnl skips weekend clearings`() {
        val friday = LocalDateTime.of(2026, 10, 2, 10, 0)
        val monday = LocalDateTime.of(2026, 10, 5, 10, 0)
        var counted = 0
        val calculator =
            PnlCalculator.futures(
                pointValue = { BigDecimal("1000") },
                fundingPerClearing = { _, dates ->
                    counted = dates.size
                    BigDecimal.ZERO
                },
            )

        val pnl =
            calculator.pnl(position(PositionDirection.LONG, friday, monday), BigDecimal("100"), BigDecimal("101"), BigDecimal("1"))

        assertEquals(1, counted, "суббота и воскресенье не являются торговыми днями")
        assertEquals(0, BigDecimal("1000").compareTo(pnl))
    }

    @Test
    fun `futures pnl is fail open when funding is unknown`() {
        val opened = LocalDateTime.of(2026, 10, 5, 10, 0)
        val closed = LocalDateTime.of(2026, 10, 6, 10, 0)
        val unknown = AtomicReference<Position?>(null)
        val calculator =
            PnlCalculator.futures(
                pointValue = { BigDecimal("1000") },
                fundingPerClearing = { _, _ -> null },
                onFundingUnknown = { unknown.set(it) },
            )

        val pnl =
            calculator.pnl(position(PositionDirection.LONG, opened, closed), BigDecimal("100"), BigDecimal("101"), BigDecimal("1"))

        assertEquals(0, BigDecimal("1000").compareTo(pnl), "неизвестный funding = 0, без исключения")
        assertEquals("Si", unknown.get()?.ticker)
    }

    @Test
    fun `lot based pnl subtracts a round trip commission and defaults to zero`() {
        val opened = LocalDateTime.of(2026, 10, 5, 10, 0)
        val closed = LocalDateTime.of(2026, 10, 5, 12, 0)
        val withCommission =
            PnlCalculator.lotBased(
                lotSize = { 10L },
                commissionRub = { BigDecimal("0.5") },
            )
        val withoutCommission = PnlCalculator.lotBased(lotSize = { 10L })

        val pnl =
            withCommission.pnl(position(PositionDirection.LONG, opened, closed), BigDecimal("100"), BigDecimal("101"), BigDecimal("2"))
        val plain =
            withoutCommission.pnl(position(PositionDirection.SHORT, opened, closed), BigDecimal("101"), BigDecimal("100"), BigDecimal("1"))
        val unit = PnlCalculator.plain()
        val unitPnl = unit.pnl(position(PositionDirection.LONG, opened, closed), BigDecimal("100"), BigDecimal("101"), BigDecimal("1"))

        assertEquals(0, BigDecimal("18").compareTo(pnl))
        assertEquals(0, BigDecimal("10").compareTo(plain))
        assertEquals(0, BigDecimal("1").compareTo(unitPnl))
    }
}
