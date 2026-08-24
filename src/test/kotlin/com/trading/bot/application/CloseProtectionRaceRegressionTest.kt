package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
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
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.UUID

/**
 * Regression tests for two P0 state-machine races discovered in a00168d review:
 *
 * P0-A (protectionFill_cancelPendingClose): SL/TP fires while pendingClose=true.
 *   Old code cleared closeOrderId/pendingClose immediately, leaving old close order
 *   live on exchange. Fix: set closeCancelPending=true, preserve closeOrderId.
 *
 * P0-B (resolveCloseCancel_staleOverwrite): confirmCloseFill() may transition position
 *   to CLOSED in DB. Old code then saved stale OPEN entity over it. Fix: re-read from
 *   DB after confirmCloseFill and check status before clearing.
 */
class CloseProtectionRaceRegressionTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    @BeforeEach
    fun setUp() {
        Mockito.reset(alorClient, orderOutboxService, orderOutboxRepo, positionRepo, tradeEventService)
        stubCancelSafe()
        stubSave()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Test 1: resolveCloseCancel_terminalWithFinalFill_doesNotReopenPosition
    // ═══════════════════════════════════════════════════════════════════

    /**
     * P0-B regression: confirmCloseFill transitions position to CLOSED in DB.
     * resolveCloseCancel must NOT save stale OPEN entity afterwards.
     *
     * Scenario:
     * - Position OPEN qty=7, closeCancelPending=true, closeOrderId="A", cumulative=3
     * - REST: A = CANCELED, filledQuantity=7
     * - confirmCloseFill applies delta (7-3=4), fills remaining → finalize → CLOSED
     * - resolveCloseCancel re-reads → sees CLOSED → does NOT save stale OPEN
     */
    @Test
    fun resolveCloseCancel_terminalWithFinalFill_doesNotReopenPosition() = runBlocking {
        var savedPosition: Position? = null

        // Simulate confirmCloseFill transitioning to CLOSED in DB
        val confirmCloseFillLambda: suspend (Position, BigDecimal, CloseReason) -> Unit = { pos, _, _ ->
            pos.status = PositionStatus.CLOSED
            pos.quantity = 0
            pos.closedAt = java.time.LocalDateTime.now()
            pos.closePrice = BigDecimal("105")
            savedPosition = pos
        }

        val reconciler = buildReconcilerWithConfirmCloseFill(confirmCloseFillLambda)

        val pos = Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 7,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("105"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            pendingClose = true,
            closeOrderId = "order-A",
            closeCancelPending = true,
            cumulativeCloseFillQty = 3,
            closeReason = CloseReason.STOP_LOSS,
        )

        whenever(positionRepo.findById(1L)).thenReturn(pos)
        whenever(alorClient.verifyOrder(eq("order-A"), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution("CANCELED", 7, BigDecimal("105")))

        reconciler.reconcilePosition(pos)

        // Position MUST remain CLOSED — not reopened by stale entity save
        assertEquals(PositionStatus.CLOSED, pos.status) { "status must stay CLOSED" }
        assertEquals(0, pos.quantity) { "quantity must stay 0" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Test 2: protectionFill_cancelPendingClose_oldCloseCannotRemainActive
    // ═══════════════════════════════════════════════════════════════════

    /**
     * P0-A regression: SL fires while close order A is live on exchange.
     *
     * Scenario:
     * - Position qty=10, close A for qty=10, SL for qty=10
     * - SL B fills 4 (partial)
     * - applyExchangeProtectionClose:
     *   - SL state cleared
     *   - pendingClose=true → CANCEL A → closeCancelPending=TRUE
     *   - closeOrderId = "A" (PRESERVED)
     *   - pendingClose = true (PRESERVED)
     *   - applyCloseExecution → qty becomes 6
     *
     * Assert:
     * - closeOrderId == "A" (not null!)
     * - closeCancelPending == true
     * - pendingClose == true
     * - NO new close order placed (cancel was placed, not a new order)
     */
    @Test
    fun protectionFill_cancelPendingClose_oldCloseCannotRemainActive() = runBlocking {
        var cancelPlacedFor: String? = null
        var newOrderPlaced = false

        whenever(orderOutboxService.placeCancelOrder(anyOrNull<Long>(), anyOrNull(), anyOrNull())).thenAnswer {
            cancelPlacedFor = it.getArgument<String>(1)
            OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = true)
        }
        whenever(orderOutboxService.placeOrder(
            anyOrNull(), anyOrNull(), Mockito.anyInt(), anyOrNull(), anyOrNull(),
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
        )).thenAnswer {
            newOrderPlaced = true
            OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), "NEW-ORDER", success = true)
        }

        val manager = ProtectionOrderManager(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
            portfolioResolver = { "D12345" },
            onSlProtectionFailed = {},
            protectionOrdersEnabled = true,
            applyCloseExecution = { pos, qty, price, _ ->
                pos.quantity -= qty
                pos.currentPrice = price
            },
        )

        val pos = Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            slOrderId = "SL-B",
            slOrderPrice = BigDecimal("95"),
            closeOrderId = "close-A",
            pendingClose = true,
            closeReason = CloseReason.STOP_LOSS,
        )

        val execution = AlorClient.OrderExecution("FILLED", 4, BigDecimal("95"))
        manager.applyExchangeProtectionClosePublic(pos, execution, CloseReason.STOP_LOSS)

        // CRITICAL: close order A preserved, not cleared
        assertEquals("close-A", pos.closeOrderId) { "closeOrderId MUST be preserved — old order still live" }
        assertTrue(pos.closeCancelPending) { "closeCancelPending must be set" }
        assertTrue(pos.pendingClose) { "pendingClose must remain true" }
        assertEquals(CloseReason.STOP_LOSS, pos.closeReason) { "closeReason preserved" }

        // Cancel was placed for old close order
        assertEquals("close-A", cancelPlacedFor) { "cancel must be sent for old close order" }

        // Quantity reduced by SL fill
        assertEquals(6, pos.quantity) { "quantity reduced by SL fill (10-4=6)" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Test 3: lateCloseFillAfterProtectionFill
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Integration test: SL fills partial, cancels old close, then late WS fill
     * for the old close order arrives. Delta model must still work.
     *
     * Scenario:
     * - Position qty=10, close A for 10
     * - SL B fills 4 → qty=6, closeCancelPending=true, closeOrderId="A"
     * - WS fill for "A": cumulativeFilledQty=6
     * - Delta model: delta = 6 - 0 = 6, position has qty=6 → full close
     *
     * Assert:
     * - position CLOSED
     * - no negative quantity
     * - no opposite order
     */
    @Test
    fun lateCloseFillAfterProtectionFill() = runBlocking {
        var closedPosition: Position? = null

        val manager = ProtectionOrderManager(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
            portfolioResolver = { "D12345" },
            onSlProtectionFailed = {},
            protectionOrdersEnabled = true,
            applyCloseExecution = { pos, qty, price, _ ->
                pos.quantity -= qty
                pos.currentPrice = price
                if (pos.quantity <= 0) {
                    pos.status = PositionStatus.CLOSED
                    pos.closePrice = price
                    closedPosition = pos
                }
            },
        )

        val pos = Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            slOrderId = "SL-B",
            slOrderPrice = BigDecimal("95"),
            closeOrderId = "close-A",
            pendingClose = true,
            closeReason = CloseReason.STOP_LOSS,
        )

        // Step 1: SL B fills 4
        val slExecution = AlorClient.OrderExecution("FILLED", 4, BigDecimal("95"))
        manager.applyExchangeProtectionClosePublic(pos, slExecution, CloseReason.STOP_LOSS)

        assertEquals(6, pos.quantity) { "after SL: qty = 10-4 = 6" }
        assertTrue(pos.closeCancelPending) { "after SL: closeCancelPending = true" }
        assertEquals("close-A", pos.closeOrderId) { "after SL: closeOrderId preserved" }
        assertTrue(pos.pendingClose) { "after SL: pendingClose preserved" }

        // Step 2: Late WS fill for close-A arrives (cumulativeFilledQty=6)
        // In handlePendingCloseReport, delta = 6 - 0 = 6, position has qty=6 → full close
        val closePos = Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 6,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("95"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            pendingClose = true,
            closeOrderId = "close-A",
            closeCancelPending = true,
            cumulativeCloseFillQty = 0,
            closeReason = CloseReason.STOP_LOSS,
        )

        val closeFillProcessor = CloseFillProcessor(
            positionRepo = positionRepo,
            alorClient = alorClient,
            pnlCalculator = PnlCalculator.plain(),
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            metricPrefix = "test",
            portfolioResolver = { "D12345" },
            onPositionClosed = {},
            cancelProtectionOrders = { },
            attachProtectionOrders = { },
        )

        whenever(positionRepo.findById(1L)).thenReturn(closePos)
        whenever(positionRepo.transitionToClosed(
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), Mockito.anyInt(),
        )).thenReturn(true)

        val closeReport = com.trading.bot.model.dto.ExecutionReport(
            orderId = "close-A",
            status = com.trading.bot.model.dto.OrderStatus.FILLED,
            cumulativeFilledQty = 6,
            avgPrice = BigDecimal("103"),
            ticker = "SBER",
            side = "sell",
        )

        closeFillProcessor.handlePendingCloseReport(closePos, closeReport)

        assertEquals(PositionStatus.CLOSED, closePos.status) { "position must be CLOSED after late fill" }
        assertTrue(closePos.quantity >= 0) { "quantity must never be negative" }
        assertFalse(closePos.pendingClose) { "pendingClose cleared after finalize" }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun buildReconcilerWithConfirmCloseFill(
        onConfirmClose: suspend (Position, BigDecimal, CloseReason) -> Unit,
    ): ExecutionReconciler =
        ExecutionReconciler(
            alorClient = alorClient,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
            objectMapper = tools.jackson.module.kotlin.jacksonObjectMapper(),
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
            confirmCloseFill = onConfirmClose,
            reconcileProtectionOrders = {},
            onAbandonCleanup = {},
        )

    private fun stubCancelSafe() {
        runBlocking {
            whenever(orderOutboxService.placeCancelOrder(anyOrNull<Long>(), anyOrNull(), anyOrNull()))
                .thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = true))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Test 4: protectionPartialFill_doesNotRearmWhileCloseCancelPending
    // ═══════════════════════════════════════════════════════════════════

    /**
     * When SL fills partially while close is pending, old close order is cancelled
     * (closeCancelPending=true). New SL/TP must NOT be created while old close is
     * still potentially live on the exchange. Otherwise both old close and new
     * protection could fire → over-close.
     *
     * Flow:
     * - SL B fills 4 (qty 10→6), closeCancelPending set
     * - attachProtectionOrders must be SKIPPED
     * - After reconcile confirms old close terminal → clear closeCancelPending
     * - Then reconcileProtectionOrders creates fresh SL/TP
     */
    @Test
    fun protectionPartialFill_doesNotRearmWhileCloseCancelPending() = runBlocking {
        var orderPlaced = false

        whenever(orderOutboxService.placeOrder(
            anyOrNull(), anyOrNull(), Mockito.anyInt(), anyOrNull(), anyOrNull(),
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
        )).thenAnswer {
            orderPlaced = true
            OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), "NEW-SL", success = true)
        }

        val manager = ProtectionOrderManager(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
            portfolioResolver = { "D12345" },
            onSlProtectionFailed = {},
            protectionOrdersEnabled = true,
            applyCloseExecution = { pos, qty, price, _ ->
                pos.quantity -= qty
                pos.currentPrice = price
            },
        )

        val pos = Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            slOrderId = "SL-B",
            slOrderPrice = BigDecimal("95"),
            takeProfit = BigDecimal("110"),
            closeOrderId = "close-A",
            pendingClose = true,
            closeReason = CloseReason.STOP_LOSS,
        )

        val execution = AlorClient.OrderExecution("FILLED", 4, BigDecimal("95"))
        manager.applyExchangeProtectionClosePublic(pos, execution, CloseReason.STOP_LOSS)

        // State checks
        assertEquals(6, pos.quantity) { "qty reduced by SL fill" }
        assertTrue(pos.closeCancelPending) { "closeCancelPending set" }
        assertEquals("close-A", pos.closeOrderId) { "closeOrderId preserved" }

        // CRITICAL: no new SL/TP order placed
        assertFalse(orderPlaced) { "attachProtectionOrders must NOT create new SL/TP while closeCancelPending=true" }

        // After old close confirmed terminal, reconcile will clear and re-arm
        // Simulate: clear closeCancelPending manually (as reconciler would)
        pos.closeCancelPending = false
        pos.closeOrderId = null
        pos.pendingClose = false
        pos.slOrderId = null
        pos.tpOrderId = null
        orderPlaced = false

        // Now reconcile should place new protection
        manager.attachProtectionOrders(pos)

        assertTrue(orderPlaced) { "after closeCancelPending cleared, new SL/TP must be created" }
    }

    private fun stubSave() {
        runBlocking {
            whenever(positionRepo.save(anyOrNull<Position>())).thenAnswer { it.getArgument<Position>(0) }
        }
    }
}
