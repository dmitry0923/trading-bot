package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

/**
 * [CloseFillProcessor]: delta-модель закрытия на «краевых» путях.
 *
 * Покрывается: игнорирование неположительной дельты (дубликат/out-of-order),
 * эскалация в RECONCILIATION_REQUIRED после серии UNKNOWN от биржи, отказ
 * невозможного филла (cancel + ожидание терминального состояния), персист
 * FUNDING_UNKNOWN при финализации и fail-soft при недоступности REST.
 */
class CloseFillProcessorPathsTest {
    private val positionRepo = mock<PositionRepository>()
    private val alorClient = mock<AlorClient>()
    private val tradeEventService = mock<TradeEventService>()
    private val meterRegistry = SimpleMeterRegistry()
    private val closed = AtomicInteger(0)

    private fun processor(maxAttempts: Int = 2): CloseFillProcessor =
        CloseFillProcessor(
            positionRepo = positionRepo,
            alorClient = alorClient,
            pnlCalculator = PnlCalculator.plain(),
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            metricPrefix = "test.close",
            portfolioResolver = { "D12345" },
            onPositionClosed = { closed.incrementAndGet() },
            cancelProtectionOrders = { },
            attachProtectionOrders = { },
            closeReconcileMaxAttempts = maxAttempts,
        )

    @BeforeEach
    fun setUp() {
        runBlocking {
            whenever(positionRepo.save(anyPosition())).thenAnswer { it.getArgument<Position>(0) }
            Mockito.clearInvocations(positionRepo)
        }
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return openPosition()
    }

    private fun openPosition(
        quantity: Int = 10,
        cumulativeCloseFillQty: Int = 0,
        closeOrderId: String? = "CLOSE-1",
        fundingUnknown: Boolean = false,
    ): Position =
        Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = quantity,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            pendingClose = true,
            closeOrderId = closeOrderId,
            cumulativeCloseFillQty = cumulativeCloseFillQty,
            fundingUnknown = fundingUnknown,
        )

    private fun report(
        orderId: String = "CLOSE-1",
        cumulativeFilledQty: Int,
        avgPrice: BigDecimal? = BigDecimal("110"),
    ): ExecutionReport =
        ExecutionReport(
            orderId = orderId,
            status = OrderStatus.FILLED,
            cumulativeFilledQty = cumulativeFilledQty,
            avgPrice = avgPrice,
            ticker = "SBER",
            side = "sell",
        )

    private suspend fun stubFindById(pos: Position) {
        whenever(positionRepo.findById(1L)).thenReturn(pos)
    }

    @Test
    fun `handleCloseFill ignores duplicate cumulative fill`(): Unit =
        runBlocking {
            val pos = openPosition(quantity = 7, cumulativeCloseFillQty = 3)
            stubFindById(pos)

            processor().handleCloseFill(pos, report(cumulativeFilledQty = 3))

            assertEquals(7, pos.quantity)
            verify(positionRepo, Mockito.never()).save(anyPosition())
        }

    @Test
    fun `confirmCloseFill ignores duplicate REST cumulative`(): Unit =
        runBlocking {
            val pos = openPosition(quantity = 7, cumulativeCloseFillQty = 5)
            stubFindById(pos)
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("FILLED", 5, BigDecimal("110")))

            processor().confirmCloseFill(pos, BigDecimal("110"), CloseReason.STOP_LOSS)

            assertEquals(7, pos.quantity)
            assertEquals(PositionStatus.OPEN, pos.status)
        }

    @Test
    fun `pending close report ignores duplicate cumulative`(): Unit =
        runBlocking {
            val pos = openPosition(quantity = 4, cumulativeCloseFillQty = 6)
            stubFindById(pos)

            val handled = processor().handlePendingCloseReport(pos, report(cumulativeFilledQty = 6))

            assertTrue(handled)
            assertEquals(4, pos.quantity)
        }

    @Test
    fun `persistent unknown close escalates to reconciliation required`(): Unit =
        runBlocking {
            val pos = openPosition()
            stubFindById(pos)
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(null)
            whenever(alorClient.getPositions(anyOrNull())).thenReturn(
                AlorClient.ReconcileResult.Ok(
                    listOf(AlorClient.ExchangePosition(ticker = "SBER", qty = 10, avgPrice = BigDecimal("100"))),
                ),
            )
            val processor = processor(maxAttempts = 2)

            processor.confirmCloseFill(pos, BigDecimal("110"), CloseReason.STOP_LOSS)
            processor.confirmCloseFill(pos, BigDecimal("110"), CloseReason.STOP_LOSS)

            assertEquals(PositionStatus.RECONCILIATION_REQUIRED, pos.status)
            assertFalse(pos.pendingClose)
            assertEquals(1.0, meterRegistry.counter("test.close.close.unknown_escalated", "ticker", "SBER").count())
        }

    @Test
    fun `close confirmed by exchange position delta finalizes at expected price`(): Unit =
        runBlocking {
            val pos = openPosition()
            stubFindById(pos)
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(null)
            whenever(alorClient.getPositions(anyOrNull()))
                .thenReturn(AlorClient.ReconcileResult.Ok(emptyList()))
            whenever(
                positionRepo.transitionToClosed(
                    Mockito.anyLong(),
                    anyPositionStatus(),
                    anyBigDecimal(),
                    anyCloseReason(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                ),
            ).thenReturn(true)

            processor().confirmCloseFill(pos, BigDecimal("108"), CloseReason.STOP_LOSS)

            assertEquals(PositionStatus.CLOSED, pos.status)
            assertEquals(0, BigDecimal("108").compareTo(pos.closePrice!!))
            assertEquals(1, closed.get())
        }

    @Test
    fun `close confirmation fails when exchange fetch failed`(): Unit =
        runBlocking {
            val pos = openPosition()
            whenever(alorClient.getPositions(anyOrNull())).thenReturn(AlorClient.ReconcileResult.Failed)

            assertFalse(processor().closeConfirmedByPositionDelta(pos))
        }

    @Test
    fun `impossible close cancellation failure keeps position pending`(): Unit =
        runBlocking {
            val pos = openPosition()
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("exchange timeout"))

            processor().handleImpossibleClose(pos)

            assertTrue(pos.pendingClose)
            assertEquals("CLOSE-1", pos.closeOrderId)
            assertEquals(1.0, meterRegistry.counter("test.close.close.impossible_uncertain", "ticker", "SBER").count())
        }

    @Test
    fun `impossible close with failed verify leaves state for reconciler`(): Unit =
        runBlocking {
            val pos = openPosition()
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("rest down"))

            processor().handleImpossibleClose(pos)

            assertTrue(pos.pendingClose)
            assertEquals("CLOSE-1", pos.closeOrderId)
        }

    @Test
    fun `impossible close without order id resets close state`(): Unit =
        runBlocking {
            val pos = openPosition(closeOrderId = null)

            processor().handleImpossibleClose(pos)

            assertFalse(pos.pendingClose)
            assertNull(pos.closeOrderId)
        }

    @Test
    fun `apply close execution rejects impossible fill`(): Unit =
        runBlocking {
            val pos = openPosition(quantity = 10)
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.CONFIRMED)

            processor().applyCloseExecution(pos, 50, BigDecimal("110"), CloseReason.STOP_LOSS)

            assertEquals(10, pos.quantity)
            assertEquals(PositionStatus.OPEN, pos.status)
            assertNull(pos.closeOrderId)
        }

    @Test
    fun `finalize without id is skipped`(): Unit =
        runBlocking {
            val pos = openPosition().copy(id = null)

            processor().finalizeClosePosition(pos, BigDecimal("110"), CloseReason.STOP_LOSS)

            assertEquals(PositionStatus.OPEN, pos.status)
            verify(positionRepo, Mockito.never()).save(anyPosition())
        }

    @Test
    fun `close with unknown funding persists the marker`(): Unit =
        runBlocking {
            val pos = openPosition(fundingUnknown = true)
            whenever(
                positionRepo.transitionToClosed(
                    Mockito.anyLong(),
                    anyPositionStatus(),
                    anyBigDecimal(),
                    anyCloseReason(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                ),
            ).thenReturn(true)

            processor().finalizeClosePosition(pos, BigDecimal("110"), CloseReason.TAKE_PROFIT)

            verify(positionRepo).setFundingUnknown(1L, true)
            assertEquals(PositionStatus.TAKE_PROFIT, pos.status)
        }

    @Test
    fun `finalize skipped when another path already closed the position`(): Unit =
        runBlocking {
            val pos = openPosition()
            whenever(
                positionRepo.transitionToClosed(
                    Mockito.anyLong(),
                    anyPositionStatus(),
                    anyBigDecimal(),
                    anyCloseReason(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                ),
            ).thenReturn(false)

            processor().finalizeClosePosition(pos, BigDecimal("110"), CloseReason.STOP_LOSS)

            assertEquals(PositionStatus.OPEN, pos.status)
            assertEquals(0, closed.get())
        }

    // ─── matcher-хелперы ─────────────────────────────────────────────

    private fun anyPositionStatus(): PositionStatus {
        Mockito.any(PositionStatus::class.java)
        return PositionStatus.CLOSED
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyCloseReason(): CloseReason {
        Mockito.any(CloseReason::class.java)
        return CloseReason.STOP_LOSS
    }
}
