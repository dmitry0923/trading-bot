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
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * Regression test (P1#3): [ExecutionReconciler.resolveCloseViaOutbox] must pick ONLY
 * the `purpose="close"` outbox row for a position.
 *
 * A position accumulates multiple outbox rows (entry, SL, TP, close). Without the
 * purpose filter, `findLatestByPositionId(positionId)` returns the latest row of ANY
 * purpose — e.g. a protection order placed AFTER the close request — and reconciliation
 * would "resolve" the close to an SL/TP orderId, corrupting the close state machine.
 *
 * Setup: SL outbox created LATER than the close outbox (so unfiltered latest == SL).
 * The resolver must still select the close row.
 */
class ExecutionReconcilerClosePurposeTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var reconciler: ExecutionReconciler

    private var confirmedCloseFillCount = 0
    private var confirmedOrderIdAtConfirm: String? = null

    private fun buildReconciler(): ExecutionReconciler =
        ExecutionReconciler(
            alorClient = alorClient,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
            objectMapper = jacksonObjectMapper(),
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            metricPrefix = "test.reconciler",
            portfolioResolver = { "D12345" },
            onEntryOpened = {},
            isGoneStatus = { false },
            isFilledStatus = { true },
            attachProtectionOrders = {},
            confirmCloseFill = { pos, _, _ ->
                confirmedCloseFillCount++
                confirmedOrderIdAtConfirm = pos.closeOrderId
            },
            reconcileProtectionOrders = {},
            onAbandonCleanup = {},
        )

    @Suppress("UNUSED_PARAMETER")
    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return openPos()
    }

    private fun openPos(): Position =
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
            closeOrderId = null,
        )

    /**
     * Entry + SL + TP + close outbox entries exist for the same positionId;
     * SL/TP are the LATEST rows. resolveCloseViaOutbox must pick purpose="close".
     */
    @Test
    fun `resolveCloseViaOutbox picks only the close purpose outbox row`() =
        runBlocking {
            reconciler = buildReconciler()
            val pos = openPos()

            val entryRow =
                OrderOutbox(payloadJson = "{}", status = OutboxStatus.SENT, alorOrderId = "entry-1", positionId = 1L)
            val slRow = OrderOutbox(payloadJson = "{}", status = OutboxStatus.SENT, alorOrderId = "sl-1", positionId = 1L)
            val tpRow = OrderOutbox(payloadJson = "{}", status = OutboxStatus.SENT, alorOrderId = "tp-1", positionId = 1L)
            // Close row is OLDER than SL/TP — unfiltered "latest" would be wrong
            val closeRow = OrderOutbox(payloadJson = "{}", status = OutboxStatus.SENT, alorOrderId = "close-1", positionId = 1L)

            // Purpose-filtered query returns exactly the close row
            whenever(orderOutboxRepo.findLatestByPositionId(eq(1L), eq("close"))).thenReturn(closeRow)
            // Unfiltered query (old buggy behaviour) would return the latest = TP row
            whenever(orderOutboxRepo.findLatestByPositionId(eq(1L), isNull())).thenReturn(tpRow)
            whenever(orderOutboxRepo.findLatestByPositionId(eq(1L), eq("sl"))).thenReturn(slRow)
            whenever(orderOutboxRepo.findLatestByPositionId(eq(1L), eq("tp"))).thenReturn(tpRow)
            whenever(orderOutboxRepo.findLatestByPositionId(eq(1L), eq("entry"))).thenReturn(entryRow)
            whenever(positionRepo.save(anyOrNull<Position>())).thenAnswer { it.getArgument<Position>(0) }

            reconciler.resolveCloseViaOutbox(pos)

            // The purpose filter MUST have been applied
            verify(orderOutboxRepo).findLatestByPositionId(1L, "close")
            verify(orderOutboxRepo, never()).findLatestByPositionId(eq(1L), isNull())

            // Resolved closeOrderId comes from the CLOSE row — not SL/TP/entry
            assertEquals("close-1", pos.closeOrderId) { "closeOrderId resolved from purpose=close row" }
            assertTrue(pos.pendingClose) { "still pending until confirmCloseFill verifies" }

            // Hand off to delta-model confirmation with the resolved id
            assertEquals(1, confirmedCloseFillCount) { "confirmCloseFill invoked exactly once" }
            assertEquals("close-1", confirmedOrderIdAtConfirm)
        }

    /**
     * No close-purpose outbox row → pendingClose reset (clean OPEN, ready for fresh close),
     * NOT resolved from some other purpose's row.
     */
    @Test
    fun `resolveCloseViaOutbox without close row resets pendingClose instead of guessing`() =
        runBlocking {
            reconciler = buildReconciler()
            val pos = openPos()

            val tpRow = OrderOutbox(payloadJson = "{}", status = OutboxStatus.SENT, alorOrderId = "tp-1", positionId = 1L)
            whenever(orderOutboxRepo.findLatestByPositionId(eq(1L), eq("close"))).thenReturn(null)
            whenever(orderOutboxRepo.findLatestByPositionId(eq(1L), isNull())).thenReturn(tpRow)
            whenever(positionRepo.save(anyOrNull<Position>())).thenAnswer { it.getArgument<Position>(0) }

            reconciler.resolveCloseViaOutbox(pos)

            assertEquals(null, pos.closeOrderId) { "must NOT adopt another purpose's orderId" }
            assertFalse(pos.pendingClose) { "pendingClose reset when no close outbox exists" }
            assertEquals(0, confirmedCloseFillCount)
        }
}
