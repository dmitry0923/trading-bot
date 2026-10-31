package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ExecutionReconciler] — исчерпывающие ветки разрешения pending-входа и
 * pending-закрытия: «заявка ещё в полёте», отмена без подтверждения, фил,
 * появившийся во время отмены, и поздний фил закрытия (дельта-модель).
 */
class ExecutionReconcilerOutboxBranchesTest {
    private val alorClient = mock<AlorClient>()
    private val orderOutboxRepo = mock<OrderOutboxRepository>()
    private val positionRepo = mock<PositionRepository>()
    private val tradeEventService = mock<TradeEventService>()
    private val meterRegistry = SimpleMeterRegistry()
    private val opened = AtomicInteger(0)
    private val confirmCalls = AtomicInteger(0)
    private val attachCalls = AtomicInteger(0)

    private val reconciler =
        ExecutionReconciler(
            alorClient = alorClient,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { entryPartialFillCancelAfterMs = 30_000 },
            objectMapper = jacksonObjectMapper(),
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            metricPrefix = "test.reconciler",
            portfolioResolver = { "D12345" },
            onEntryOpened = { opened.incrementAndGet() },
            isGoneStatus = { it.status.uppercase() in setOf("CANCELED", "EXPIRED", "REJECTED") },
            isFilledStatus = { it.status.uppercase() in setOf("FILLED", "MATCHED") && it.filledQuantity > 0 },
            attachProtectionOrders = { attachCalls.incrementAndGet() },
            confirmCloseFill = { _, _, _ -> confirmCalls.incrementAndGet() },
            reconcileProtectionOrders = { },
            onAbandonCleanup = { },
            clock = Clock.system(ZoneId.of("Europe/Moscow")),
        )

    private fun entry(quantity: Int = 10): Position =
        Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = quantity,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            pendingEntry = true,
        )

    private fun closing(
        closeOrderId: String? = "CLOSE-1",
        closeCancelPending: Boolean = false,
        cumulativeCloseFillQty: Int = 0,
        closeReason: CloseReason? = CloseReason.STRATEGY_CLOSE,
    ): Position =
        Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            pendingClose = true,
            closeOrderId = closeOrderId,
            closeCancelPending = closeCancelPending,
            cumulativeCloseFillQty = cumulativeCloseFillQty,
            closeReason = closeReason,
        )

    private fun outbox(
        createdAt: LocalDateTime,
        payload: String = """{"qty":10}""",
    ): OrderOutbox =
        OrderOutbox(
            payloadJson = payload,
            status = OutboxStatus.SENT,
            alorOrderId = "ENTRY-1",
            idempotencyKey = "idem-1",
            positionId = 1L,
            createdAt = createdAt,
        )

    private fun stubOutbox(createdAt: LocalDateTime = LocalDateTime.now()) {
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry")).thenReturn(outbox(createdAt))
            whenever(positionRepo.save(anyPosition())).thenAnswer { it.getArgument<Position>(0) }
        }
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return entry()
    }

    // ─── вход ────────────────────────────────────────────────────────

    @Test
    fun `missing execution keeps the entry pending`(): Unit =
        runBlocking {
            stubOutbox()
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(null)
            val pos = entry()

            reconciler.resolveEntryViaOutbox(pos)

            assertTrue(pos.pendingEntry)
            assertEquals(0, opened.get())
        }

    @Test
    fun `unfilled entry waits until the cancel threshold`(): Unit =
        runBlocking {
            stubOutbox(createdAt = LocalDateTime.now().minusSeconds(1))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("PENDING", 0, null))
            val pos = entry()

            reconciler.resolveEntryViaOutbox(pos)

            assertTrue(pos.pendingEntry)
            assertEquals(0, opened.get())
        }

    @Test
    fun `unfilled entry is abandoned once the cancel is confirmed`(): Unit =
        runBlocking {
            stubOutbox(createdAt = LocalDateTime.now().minusMinutes(5))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("PENDING", 0, null))
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.CONFIRMED)
            val pos = entry()

            reconciler.resolveEntryViaOutbox(pos)

            assertFalse(pos.pendingEntry)
            assertEquals(PositionStatus.CLOSED, pos.status)
            assertEquals(CloseReason.ENTRY_NOT_CONFIRMED, pos.closeReason)
            assertEquals(
                1.0,
                meterRegistry.counter("test.reconciler.entry.unfilled_cancelled", "ticker", "SBER").count(),
                1e-9,
            )
        }

    @Test
    fun `uncertain cancel on a live order stays pending`(): Unit =
        runBlocking {
            stubOutbox(createdAt = LocalDateTime.now().minusMinutes(5))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("PENDING", 0, null))
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.UNCERTAIN)
            val pos = entry()

            reconciler.resolveEntryViaOutbox(pos)

            assertTrue(pos.pendingEntry)
            assertEquals(0, opened.get())
        }

    @Test
    fun `cancel exception on a gone order still abandons the entry`(): Unit =
        runBlocking {
            stubOutbox(createdAt = LocalDateTime.now().minusMinutes(5))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("PENDING", 0, null), AlorClient.OrderExecution("EXPIRED", 0, null))
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("exchange timeout"))
            val pos = entry()

            reconciler.resolveEntryViaOutbox(pos)

            assertFalse(pos.pendingEntry)
            assertEquals(CloseReason.ENTRY_NOT_CONFIRMED, pos.closeReason)
        }

    @Test
    fun `fill that appeared during cancel is finalized on the next pass`(): Unit =
        runBlocking {
            stubOutbox(createdAt = LocalDateTime.now().minusMinutes(5))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(
                    AlorClient.OrderExecution("PENDING", 0, null),
                    AlorClient.OrderExecution("PARTIALLY_FILLED", 3, BigDecimal("100")),
                )
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.UNCERTAIN)
            val pos = entry()

            reconciler.resolveEntryViaOutbox(pos)

            assertTrue(pos.pendingEntry)
            assertEquals(0, opened.get())
        }

    @Test
    fun `partial fill is finalized when the recheck returns no quantity`(): Unit =
        runBlocking {
            stubOutbox(createdAt = LocalDateTime.now().minusMinutes(5))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(
                    AlorClient.OrderExecution("PARTIALLY_FILLED", 4, BigDecimal("99.5")),
                    AlorClient.OrderExecution("CANCELED", 0, null),
                )
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.CONFIRMED)
            val pos = entry()

            reconciler.resolveEntryViaOutbox(pos)

            assertFalse(pos.pendingEntry)
            assertEquals(4, pos.quantity)
            assertEquals(0, BigDecimal("99.5").compareTo(pos.entryPrice))
            assertEquals(1, opened.get())
            assertEquals(1, attachCalls.get())
        }

    // ─── закрытие ────────────────────────────────────────────────────

    @Test
    fun `close cancel with a late fill applies the delta instead of waiting`(): Unit =
        runBlocking {
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("FILLED", 10, BigDecimal("101")))
            val pos = closing(closeCancelPending = true, cumulativeCloseFillQty = 4)

            reconciler.resolveCloseCancel(pos)

            assertEquals(1, confirmCalls.get())
        }

    @Test
    fun `close cancel treats FILLED without quantity as a full fill`(): Unit =
        runBlocking {
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("FILLED", 0, null))
            whenever(positionRepo.findById(1L)).thenReturn(closing())
            val pos = closing(closeCancelPending = true)

            reconciler.resolveCloseCancel(pos)

            assertEquals(1, confirmCalls.get())
        }

    @Test
    fun `confirmed close cancel clears state only for a still open position`(): Unit =
        runBlocking {
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("CANCELED", 0, null))
            whenever(positionRepo.findById(1L)).thenReturn(closing(closeCancelPending = true))
            whenever(positionRepo.save(anyPosition())).thenAnswer { it.getArgument<Position>(0) }
            val pos = closing(closeCancelPending = true)

            reconciler.resolveCloseCancel(pos)

            assertEquals(
                1.0,
                meterRegistry.counter("test.reconciler.close.cancel_confirmed", "ticker", "SBER").count(),
                1e-9,
            )
        }
}
