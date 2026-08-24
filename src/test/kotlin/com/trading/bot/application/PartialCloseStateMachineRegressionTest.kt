package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.kotlin.eq
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * Regression tests for P0 partial close state machine fix:
 *
 * 1. After partial close (pendingClose=TRUE), a second partial fill of the SAME
 *    order is correctly applied via the delta model (handlePendingCloseReport path).
 *
 * 2. If pendingClose was somehow cleared (legacy state), a WS report for the live
 *    close order is NOT lost — it routes through handleCloseFill fallback which
 *    applies the delta while pendingClose=FALSE + closeOrderId set.
 *
 * 3. closePosition() with cumulativeCloseFillQty > 0 (partial fill already applied)
 *    cancels the stale old order and creates a fresh close order for the remaining qty.
 */
class PartialCloseStateMachineRegressionTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val alorConfig = Mockito.mock(AlorConfig::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private var closedPositions = mutableListOf<Position>()

    private val engine =
        OrderExecutionEngine(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = alorConfig,
            objectMapper = objectMapper,
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            pnlCalculator = PnlCalculator.plain(),
            instrumentFilter = { true },
            metricPrefix = "test",
            onEntryOpened = {},
            onPositionClosed = { closedPositions.add(it) },
            protectionOrdersEnabled = false,
            portfolioResolver = { "D12345" },
        )

    @BeforeEach
    fun setUp() {
        closedPositions.clear()
        Mockito.reset(alorClient, orderOutboxService, orderOutboxRepo, positionRepo, alorConfig, tradeEventService)
    }

    private fun openPos(
        quantity: Int = 10,
        pendingClose: Boolean = false,
        closeOrderId: String? = null,
        cumulativeCloseFillQty: Int = 0,
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
            pendingClose = pendingClose,
            closeOrderId = closeOrderId,
            cumulativeCloseFillQty = cumulativeCloseFillQty,
        )

    private fun stubFindCloseOrderId(pos: Position) {
        runBlocking {
            Mockito.`when`(positionRepo.findByCloseOrderId(Mockito.anyString())).thenReturn(pos)
            Mockito.`when`(positionRepo.findByAlorOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.findBySlOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.findByTpOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.findById(Mockito.anyLong())).thenReturn(pos)
        }
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return openPos()
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyStatus(): PositionStatus {
        Mockito.any(PositionStatus::class.java)
        return PositionStatus.OPEN
    }

    private fun anyCloseReason(): CloseReason {
        Mockito.any(CloseReason::class.java)
        return CloseReason.STOP_LOSS
    }

    private fun stubSaveReturnsArg() {
        runBlocking {
            Mockito.`when`(positionRepo.save(anyPosition()))
                .thenAnswer { it.getArgument<Position>(0) }
        }
    }

    private fun stubTransitionToClosed() {
        runBlocking {
            Mockito.`when`(
                positionRepo.transitionToClosed(
                    Mockito.anyLong(),
                    anyStatus(),
                    anyBigDecimal(),
                    anyCloseReason(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                )
            ).thenReturn(true)
        }
    }

    private fun report(
        orderId: String = "close-1",
        cumulativeFilledQty: Int,
        avgPrice: BigDecimal = BigDecimal("110"),
    ) = ExecutionReport(
        orderId = orderId,
        status = OrderStatus.FILLED,
        cumulativeFilledQty = cumulativeFilledQty,
        avgPrice = avgPrice,
        ticker = "SBER",
        side = "sell",
    )

    /**
     * CRITICAL: report arrives after pendingClose was cleared (legacy state).
     *
     * In production, this happens when:
     * 1. Partial close → pendingClose=FALSE (old bug) + closeOrderId="close-1"
     * 2. WS report for "close-1" arrives
     * 3. handleExecutionReport: pendingClose=FALSE → returns false
     * 4. TradingBotService.handleRegularStockFill → handleCloseFill
     * 5. handleCloseFill: closeOrderId matches → delta applied → fill NOT lost
     *
     * This test ensures the fallback path works correctly for legacy states.
     */
    @Test
    fun partialClose_reportAfterPendingFlagCleared_isNotLost() {
        val pos = openPos(
            quantity = 5,
            pendingClose = false,
            closeOrderId = "close-1",
            cumulativeCloseFillQty = 3,
        )
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()

        runBlocking {
            engine.handleCloseFill(pos, report(cumulativeFilledQty = 8))
        }

        // delta = 8 - 3 = 5, 5 >= quantity=5 → finalize
        assertEquals(8, pos.cumulativeCloseFillQty) { "cumulative updated to 8" }
        assertEquals(PositionStatus.CLOSED, pos.status) { "position finalized" }
        assertEquals(1, closedPositions.size) { "onPositionClosed callback invoked" }
    }

    /**
     * CRITICAL (P0#2): After partial fill (cumulativeCloseFillQty > 0), closePosition()
     * cancels the stale old order and SETS closeCancelPending=TRUE. No new close order
     * is created immediately — that would risk over-close if the old order still fills.
     *
     * The reconciler will verify the old order is terminal before clearing state.
     * After confirmation, the monitor re-triggers a fresh close on the next tick.
     */
    @Test
    fun closePosition_cancelsStaleOrderSetsCloseCancelPendingAndReturns() {
        val pos = openPos(
            quantity = 7,
            pendingClose = true,
            closeOrderId = "order-A",
            cumulativeCloseFillQty = 3,
        )

        runBlocking {
            Mockito.`when`(positionRepo.claimForClose(pos.id!!)).thenReturn(true)
            Mockito.`when`(positionRepo.findById(pos.id!!)).thenReturn(pos)
            stubSaveReturnsArg()
            stubTransitionToClosed()

            Mockito.`when`(
                orderOutboxService.placeCancelOrder(Mockito.anyLong(), Mockito.anyString(), Mockito.nullable(Long::class.java)),
            ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, true))

            engine.closePosition(pos, BigDecimal("110"), CloseReason.STOP_LOSS)
        }

        // Old order A cancelled via outbox
        runBlocking {
            Mockito.verify(orderOutboxService).placeCancelOrder(
                eq(pos.id!!),
                eq("order-A"),
                Mockito.nullable(Long::class.java),
            )
        }
        // No new close order created — closeCancelPending prevents it
        runBlocking {
            Mockito.verify(orderOutboxService, never()).placeOrder(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.nullable(BigDecimal::class.java),
                Mockito.anyString(),
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.nullable(BigDecimal::class.java),
                Mockito.nullable(String::class.java),
                Mockito.nullable(Long::class.java),
            )
        }
        assertTrue(pos.closeCancelPending) { "closeCancelPending must be true — waiting for cancel confirmation" }
        assertEquals("order-A", pos.closeOrderId) { "closeOrderId preserved — old order still tracked" }
        assertEquals(3, pos.cumulativeCloseFillQty) { "cumulativeCloseFillQty preserved — delta model continuity" }
        assertTrue(pos.pendingClose) { "pendingClose still true" }
    }
}
