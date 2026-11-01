package com.trading.bot.repository

import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import io.r2dbc.spi.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Unit-тест маппинга строки `positions` в [com.trading.bot.model.entity.Position].
 *
 * Схема БД объявляет nullable-колонки (pnl, close_price, sl_order_id и т.д.),
 * поэтому проверяются ОБА состояния: значения заданы и `NULL`. Второй сценарий
 * покрывает default-ветви (`?: 0`, `?: false`, `?: BigDecimal.ZERO`), которые
 * интеграционный тест с заполненной строкой не исполняет.
 */
class PositionRepositoryMappingTest {
    private val repository = PositionRepository(Mockito.mock(org.springframework.r2dbc.core.DatabaseClient::class.java))

    private fun requiredColumns(): Map<String, Any> =
        mapOf(
            "ticker" to "CNYRUBF",
            "direction" to "LONG",
            "quantity" to 3,
            "entry_price" to BigDecimal("12.34"),
            "instrument_type" to "FUTURES",
            "status" to "OPEN",
            "opened_at" to LocalDateTime.of(2026, 1, 5, 10, 0),
        )

    private fun row(values: Map<String, Any?>): Row =
        Mockito.mock(Row::class.java) { invocation ->
            val column = invocation.arguments.firstOrNull() as? String
            values[column]
        }

    @Test
    fun `nullable columns fall back to schema defaults`() {
        val position = repository.toPosition(row(requiredColumns()))

        assertEquals(0, position.cumulativeSlFillQty)
        assertEquals(0, position.cumulativeTpFillQty)
        assertEquals(0, position.cumulativeCloseFillQty)
        assertEquals(0, BigDecimal.ZERO.compareTo(position.realizedPnl))
        assertEquals(0, BigDecimal.ZERO.compareTo(position.variationMargin))
        assertFalse(position.slPendingReplace)
        assertFalse(position.tpPendingReplace)
        assertFalse(position.slCancelPending)
        assertFalse(position.tpCancelPending)
        assertFalse(position.closeCancelPending)
        assertFalse(position.pendingClose)
        assertFalse(position.pendingEntry)
        assertFalse(position.fundingUnknown)
        assertNull(position.closeReason)
        assertNull(position.closedAt)
        assertNull(position.currentPrice)
        assertNull(position.alorOrderId)
        assertEquals(PositionDirection.LONG, position.direction)
        assertEquals(PositionStatus.OPEN, position.status)
    }

    @Test
    fun `nullable columns are mapped when present`() {
        val values =
            requiredColumns() +
                mapOf(
                    "id" to 7L,
                    "cumulative_sl_fill_qty" to 1,
                    "cumulative_tp_fill_qty" to 2,
                    "current_price" to BigDecimal("12.50"),
                    "close_price" to BigDecimal("12.60"),
                    "stop_loss" to BigDecimal("12.00"),
                    "take_profit" to BigDecimal("13.00"),
                    "leverage" to BigDecimal("1.0"),
                    "go_per_contract" to BigDecimal("850"),
                    "margin_used" to BigDecimal("850"),
                    "liquidation_price" to BigDecimal("11.00"),
                    "variation_margin" to BigDecimal("5"),
                    "stop_loss_points" to 300,
                    "trailing_stop_price" to BigDecimal("12.10"),
                    "pnl" to BigDecimal("10"),
                    "alor_order_id" to "ord-1",
                    "close_order_id" to "close-1",
                    "sl_order_id" to "sl-1",
                    "tp_order_id" to "tp-1",
                    "sl_order_price" to BigDecimal("12.00"),
                    "tp_order_price" to BigDecimal("13.00"),
                    "sl_pending_replace" to true,
                    "tp_pending_replace" to true,
                    "sl_cancel_pending" to true,
                    "tp_cancel_pending" to true,
                    "close_cancel_pending" to true,
                    "pending_close" to true,
                    "pending_entry" to true,
                    "realized_pnl" to BigDecimal("3"),
                    "close_reason" to "TAKE_PROFIT",
                    "cumulative_close_fill_qty" to 4,
                    "closed_at" to LocalDateTime.of(2026, 1, 6, 18, 45),
                    "cycle_id" to "cycle-1",
                    "account_id" to 2L,
                    "funding_unknown" to true,
                )

        val position = repository.toPosition(row(values))

        assertEquals(7L, position.id)
        assertEquals(1, position.cumulativeSlFillQty)
        assertEquals(2, position.cumulativeTpFillQty)
        assertEquals(4, position.cumulativeCloseFillQty)
        assertEquals(CloseReason.TAKE_PROFIT, position.closeReason)
        assertEquals(2L, position.accountId)
        assertEquals("cycle-1", position.cycleId)
        assertEquals(true, position.fundingUnknown)
        assertEquals(0, BigDecimal("12.50").compareTo(position.currentPrice!!))
        assertEquals(300, position.stopLossPoints)
    }
}
