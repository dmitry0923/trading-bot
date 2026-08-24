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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * Regression tests for P0#1 close replacement race fix.
 *
 * State machine:
 * - closeCancelPending=TRUE: old close order A still live, cancel sent but unconfirmed.
 * - No new close order created until A is confirmed terminal.
 * - Reconciler verifies A → gone → clears old state → monitor re-triggers fresh close.
 *
 * Tests:
 * 1. cancelConfirmed_thenStateCleared: old order gone → state reset, monitor can re-trigger.
 * 2. cancelUnknown_preservesState: old order status unknown → nothing changes.
 * 3. cancelNullOrderId_clearsState: closeOrderId null + closeCancelPending → clean reset.
 * 4. oldCloseFillDuringCancelIsApplied: WS fill for old order while cancelPending → delta model handles it.
 * 5. closePositionWhenCloseCancelPending_doesNothing: second closePosition call → ignored.
 */
class CloseCancelPendingReplacementTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private var confirmedCloseFillCount = 0
    private var lastConfirmedReason: CloseReason? = null

    private fun buildReconciler(): ExecutionReconciler =
        ExecutionReconciler(
            alorClient = alorClient,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
            objectMapper =
                tools.jackson.module.kotlin
                    .jacksonObjectMapper(),
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            metricPrefix = "test.reconciler",
            portfolioResolver = { "D12345" },
            onEntryOpened = {},
            isGoneStatus = { exec ->
                when (exec.status.uppercase()) {
                    "CANCELED", "CANCELLED", "REJECTED", "EXPIRED" -> true
                    else -> false
                }
            },
            isFilledStatus = { exec ->
                when (exec.status.uppercase()) {
                    "FILLED", "PARTIALLY_FILLED" -> exec.filledQuantity > 0
                    else -> false
                }
            },
            attachProtectionOrders = {},
            confirmCloseFill = { _, _, reason ->
                confirmedCloseFillCount++
                lastConfirmedReason = reason
            },
            reconcileProtectionOrders = {},
            onAbandonCleanup = {},
        )

    @BeforeEach
    fun setUp() {
        confirmedCloseFillCount = 0
        lastConfirmedReason = null
        Mockito.reset(alorClient, orderOutboxRepo, positionRepo, tradeEventService)
    }

    /**
     * 1: Old order confirmed gone (CANCELED) → state cleared → pendingClose=false.
     * Monitor will re-trigger fresh close if SL still breached.
     */
    @Test
    fun cancelConfirmed_thenStateCleared() {
        runBlocking {
            val reconciler = buildReconciler()
            val pos =
                Position(
                    id = 1L,
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 7,
                    entryPrice = BigDecimal("100"),
                    currentPrice = BigDecimal("100"),
                    instrumentType = InstrumentType.STOCK,
                    status = PositionStatus.OPEN,
                    pendingClose = true,
                    closeOrderId = "order-A",
                    closeCancelPending = true,
                    cumulativeCloseFillQty = 3,
                    closeReason = CloseReason.STOP_LOSS,
                )
            whenever(positionRepo.findById(1L)).thenReturn(pos)
            whenever(positionRepo.save(anyOrNull<Position>())).thenAnswer { it.getArgument<Position>(0) }
            whenever(alorClient.verifyOrder(eq("order-A"), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("CANCELED", 0, null))

            reconciler.reconcilePosition(pos)

            assertFalse(pos.closeCancelPending) { "closeCancelPending cleared after cancel confirmed" }
            assertFalse(pos.pendingClose) { "pendingClose cleared — monitor will re-trigger" }
            assertEquals(null, pos.closeOrderId) { "closeOrderId cleared" }
            assertEquals(0, pos.cumulativeCloseFillQty) { "cumulativeCloseFillQty reset" }
            assertEquals(null, pos.closeReason) { "closeReason cleared" }
            verify(positionRepo).save(anyOrNull<Position>())
        }
    }

    /**
     * 2: Old order status UNKNOWN → nothing changes, keep waiting.
     */
    @Test
    fun cancelUnknown_preservesState() {
        runBlocking {
            val reconciler = buildReconciler()
            val pos =
                Position(
                    id = 1L,
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 7,
                    entryPrice = BigDecimal("100"),
                    currentPrice = BigDecimal("100"),
                    instrumentType = InstrumentType.STOCK,
                    status = PositionStatus.OPEN,
                    pendingClose = true,
                    closeOrderId = "order-A",
                    closeCancelPending = true,
                    cumulativeCloseFillQty = 3,
                    closeReason = CloseReason.STOP_LOSS,
                )
            whenever(positionRepo.findById(1L)).thenReturn(pos)
            whenever(alorClient.verifyOrder(eq("order-A"), anyOrNull(), anyOrNull())).thenReturn(null)

            reconciler.reconcilePosition(pos)

            assertTrue(pos.closeCancelPending) { "closeCancelPending preserved — still waiting" }
            assertTrue(pos.pendingClose) { "pendingClose preserved" }
            assertEquals("order-A", pos.closeOrderId) { "closeOrderId preserved" }
            assertEquals(3, pos.cumulativeCloseFillQty) { "cumulative preserved" }
            Mockito.verify(positionRepo, never()).save(anyOrNull<Position>())
        }
    }

    /**
     * 3: closeOrderId is null + closeCancelPending → clean reset (defensive).
     */
    @Test
    fun cancelNullOrderId_clearsState() {
        runBlocking {
            val reconciler = buildReconciler()
            val pos =
                Position(
                    id = 1L,
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 7,
                    entryPrice = BigDecimal("100"),
                    currentPrice = BigDecimal("100"),
                    instrumentType = InstrumentType.STOCK,
                    status = PositionStatus.OPEN,
                    pendingClose = true,
                    closeOrderId = null,
                    closeCancelPending = true,
                    cumulativeCloseFillQty = 3,
                )
            whenever(positionRepo.save(anyOrNull<Position>())).thenAnswer { it.getArgument<Position>(0) }

            reconciler.reconcilePosition(pos)

            assertFalse(pos.closeCancelPending) { "closeCancelPending cleared" }
            assertFalse(pos.pendingClose) { "pendingClose cleared" }
            verify(positionRepo).save(anyOrNull<Position>())
        }
    }

    /**
     * 4: WS fill arrives for old order A while closeCancelPending=true.
     * handlePendingCloseReport applies delta correctly (pendingClose=true + closeOrderId=A).
     * Then when cancel is confirmed, remaining fills are already applied.
     */
    @Test
    fun oldCloseFillDuringCancelIsAppliedViaDelta() {
        runBlocking {
            val pos =
                Position(
                    id = 1L,
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 7,
                    entryPrice = BigDecimal("100"),
                    currentPrice = BigDecimal("100"),
                    instrumentType = InstrumentType.STOCK,
                    status = PositionStatus.OPEN,
                    pendingClose = true,
                    closeOrderId = "order-A",
                    closeCancelPending = true,
                    cumulativeCloseFillQty = 3,
                    closeReason = CloseReason.STOP_LOSS,
                )

            // Create engine to test WS dispatch path
            val engine =
                OrderExecutionEngine(
                    alorClient = alorClient,
                    orderOutboxService = Mockito.mock(OrderOutboxService::class.java),
                    orderOutboxRepo = orderOutboxRepo,
                    positionRepo = positionRepo,
                    alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
                    objectMapper =
                        tools.jackson.module.kotlin
                            .jacksonObjectMapper(),
                    tradeEventService = tradeEventService,
                    meterRegistry = meterRegistry,
                    pnlCalculator = PnlCalculator.plain(),
                    instrumentFilter = { true },
                    metricPrefix = "test",
                    onEntryOpened = {},
                    onPositionClosed = {},
                    protectionOrdersEnabled = false,
                    portfolioResolver = { "D12345" },
                )

            whenever(positionRepo.findByCloseOrderId("order-A")).thenReturn(pos)
            whenever(positionRepo.findByAlorOrderId("order-A")).thenReturn(null)
            whenever(positionRepo.findBySlOrderId("order-A")).thenReturn(null)
            whenever(positionRepo.findByTpOrderId("order-A")).thenReturn(null)
            whenever(positionRepo.findById(1L)).thenReturn(pos)
            whenever(positionRepo.save(anyOrNull<Position>())).thenAnswer { it.getArgument<Position>(0) }

            // Simulate WS fill: cumulativeFilledQty=5 (delta=5-3=2)
            val report =
                ExecutionReport(
                    orderId = "order-A",
                    status = OrderStatus.PARTIALLY_FILLED,
                    cumulativeFilledQty = 5,
                    avgPrice = BigDecimal("105"),
                    ticker = "SBER",
                    side = "sell",
                )

            val handled = engine.handleExecutionReport(report)

            assertTrue(handled) { "WS report handled by delta model" }
            assertEquals(5, pos.cumulativeCloseFillQty) { "cumulative updated by delta model" }
            assertEquals(5, pos.quantity) { "quantity reduced by delta (7-2=5)" }
            assertTrue(pos.pendingClose) { "pendingClose preserved after partial fill" }
            assertEquals("order-A", pos.closeOrderId) { "closeOrderId preserved" }
        }
    }

    /**
     * 5: closePosition() called while closeCancelPending=true → no-op (claim fails, early return).
     */
    @Test
    fun closePositionWhenCloseCancelPending_doesNothing() {
        runBlocking {
            val pos =
                Position(
                    id = 1L,
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 7,
                    entryPrice = BigDecimal("100"),
                    currentPrice = BigDecimal("100"),
                    instrumentType = InstrumentType.STOCK,
                    status = PositionStatus.OPEN,
                    pendingClose = true,
                    closeOrderId = "order-A",
                    closeCancelPending = true,
                    cumulativeCloseFillQty = 3,
                    closeReason = CloseReason.STOP_LOSS,
                )
            // claimForClose fails because pendingClose=true
            whenever(positionRepo.claimForClose(1L)).thenReturn(false)
            whenever(positionRepo.findById(1L)).thenReturn(pos)

            val engine =
                OrderExecutionEngine(
                    alorClient = alorClient,
                    orderOutboxService = Mockito.mock(OrderOutboxService::class.java),
                    orderOutboxRepo = orderOutboxRepo,
                    positionRepo = positionRepo,
                    alorConfig = Mockito.mock(AlorConfig::class.java),
                    objectMapper =
                        tools.jackson.module.kotlin
                            .jacksonObjectMapper(),
                    tradeEventService = tradeEventService,
                    meterRegistry = meterRegistry,
                    pnlCalculator = PnlCalculator.plain(),
                    instrumentFilter = { true },
                    metricPrefix = "test",
                    onEntryOpened = {},
                    onPositionClosed = {},
                    protectionOrdersEnabled = false,
                    portfolioResolver = { "D12345" },
                )

            engine.closePosition(pos, BigDecimal("100"), CloseReason.STOP_LOSS)

            // State unchanged — early return before any work
            assertTrue(pos.closeCancelPending) { "closeCancelPending unchanged" }
            assertEquals("order-A", pos.closeOrderId) { "closeOrderId unchanged" }
            assertEquals(3, pos.cumulativeCloseFillQty) { "cumulativeCloseFillQty unchanged" }
            assertTrue(pos.pendingClose) { "pendingClose unchanged" }
        }
    }
}
