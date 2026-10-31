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
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

/**
 * [CloseFillProcessor] — ранние выходы по неполному состоянию позиции и
 * терминальному close-ордеру без фила (сброс pendingClose, чтобы монитор мог
 * перевыставить закрытие), плюс FILLED без количества как полный фил.
 */
class CloseFillProcessorEarlyExitBranchesTest {
    private val positionRepo = mock<PositionRepository>()
    private val alorClient = mock<AlorClient>()
    private val tradeEventService = mock<TradeEventService>()
    private val meterRegistry = SimpleMeterRegistry()
    private val closed = AtomicInteger(0)

    private val processor =
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
            closeReconcileMaxAttempts = 3,
        )

    private fun position(
        id: Long? = 1L,
        status: PositionStatus = PositionStatus.OPEN,
        closeOrderId: String? = "CLOSE-1",
        pendingClose: Boolean = true,
        quantity: Int = 10,
        cumulativeCloseFillQty: Int = 0,
    ): Position =
        Position(
            id = id,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = quantity,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = status,
            pendingClose = pendingClose,
            closeOrderId = closeOrderId,
            cumulativeCloseFillQty = cumulativeCloseFillQty,
        )

    private fun report(
        cumulativeFilledQty: Int = 4,
        avgPrice: BigDecimal? = BigDecimal("110"),
    ): ExecutionReport =
        ExecutionReport(
            orderId = "CLOSE-1",
            status = OrderStatus.FILLED,
            cumulativeFilledQty = cumulativeFilledQty,
            avgPrice = avgPrice,
            ticker = "SBER",
            side = "sell",
        )

    private fun execution(
        status: String,
        filled: Int,
    ) = AlorClient.OrderExecution(status, filled, BigDecimal("110"))

    private suspend fun stubSavedPosition() {
        whenever(positionRepo.save(anyPosition())).thenAnswer { it.getArgument<Position>(0) }
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return position()
    }

    // ─── handleCloseFill ────────────────────────────────────────────

    @Test
    fun `report without price or position id is ignored`(): Unit =
        runBlocking {
            processor.handleCloseFill(position(), report(avgPrice = null))
            processor.handleCloseFill(position(id = null), report())

            verify(positionRepo, never()).findById(Mockito.anyLong())
        }

    @Test
    fun `closed or unlinked position ignores the close fill`(): Unit =
        runBlocking {
            whenever(positionRepo.findById(1L)).thenReturn(position(status = PositionStatus.CLOSED))
            processor.handleCloseFill(position(), report())

            whenever(positionRepo.findById(1L)).thenReturn(position(closeOrderId = null))
            processor.handleCloseFill(position(), report())

            assertEquals(0, closed.get())
        }

    // ─── confirmCloseFill ───────────────────────────────────────────

    @Test
    fun `confirm without id or on a non open position is ignored`(): Unit =
        runBlocking {
            processor.confirmCloseFill(position(id = null), BigDecimal("100"), CloseReason.STRATEGY_CLOSE)
            verify(positionRepo, never()).findById(Mockito.anyLong())

            whenever(positionRepo.findById(1L)).thenReturn(position(status = PositionStatus.CLOSED))
            processor.confirmCloseFill(position(), BigDecimal("100"), CloseReason.STRATEGY_CLOSE)

            assertEquals(0, closed.get())
        }

    @Test
    fun `terminal close order without fill resets pending state`(): Unit =
        runBlocking {
            val fresh = position()
            whenever(positionRepo.findById(1L)).thenReturn(fresh)
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(execution("CANCELED", 0))
            stubSavedPosition()

            processor.confirmCloseFill(position(), BigDecimal("100"), CloseReason.STRATEGY_CLOSE)

            assertFalse(fresh.pendingClose)
            assertNull(fresh.closeOrderId)
            assertEquals(0, fresh.cumulativeCloseFillQty)
            verify(positionRepo).resetCloseFillQty(1L, expectedOrderId = "CLOSE-1")
            assertEquals(0, closed.get())
        }

    @Test
    fun `live close order without fill stays pending`(): Unit =
        runBlocking {
            val fresh = position()
            whenever(positionRepo.findById(1L)).thenReturn(fresh)
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(execution("PENDING", 0))

            processor.confirmCloseFill(position(), BigDecimal("100"), CloseReason.STRATEGY_CLOSE)

            assertTrue(fresh.pendingClose)
            assertEquals("CLOSE-1", fresh.closeOrderId)
        }

    @Test
    fun `FILLED without quantity finalizes the whole position`(): Unit =
        runBlocking {
            val fresh = position()
            whenever(positionRepo.findById(1L)).thenReturn(fresh)
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(execution("FILLED", 0))
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
            stubSavedPosition()

            processor.confirmCloseFill(position(), BigDecimal("100"), CloseReason.STRATEGY_CLOSE)

            assertEquals(1, closed.get())
        }

    // ─── handlePendingCloseReport ───────────────────────────────────

    @Test
    fun `pending close report without id is not handled`(): Unit =
        runBlocking {
            val handled = processor.handlePendingCloseReport(position(id = null), report())

            assertFalse(handled)
        }

    @Test
    fun `pending close report on an already closed position is consumed`(): Unit =
        runBlocking {
            whenever(positionRepo.findById(1L)).thenReturn(position(status = PositionStatus.CLOSED))

            val handled = processor.handlePendingCloseReport(position(), report())

            assertTrue(handled)
            assertEquals(0, closed.get())
        }

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
        return CloseReason.EXECUTION_FILL
    }
}
