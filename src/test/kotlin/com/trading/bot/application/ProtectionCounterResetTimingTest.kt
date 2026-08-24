package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.UUID

/**
 * Regression tests for P0#2 protection counter reset timing fix.
 *
 * Bug: cumulativeSlFillQty/cumulativeTpFillQty were reset to 0 when cancel was
 * REQUESTED (cancelProtectionOrders), not when CONFIRMED (checkProtectionFills →
 * isGoneStatus). Late WS fills for the old order then compute delta = exchangeFilled
 * - 0 = exchangeFilled → double-count.
 *
 * Fix: counters are only reset when the old order is confirmed terminal.
 * cancelProtectionOrders only sets slCancelPending/tpCancelPending flags.
 *
 * Tests:
 * 1. cancelRequest_preservesSlCumulativeFill: SL cumulative not reset on cancel request.
 * 2. cancelRequest_preservesTpCumulativeFill: TP cumulative not reset on cancel request.
 * 3. lateSlFillAfterCancel_usesCorrectDelta: late WS fill computes delta against preserved counter.
 * 4. goneStatus_resetsCounter: order confirmed gone → counter reset to 0.
 */
class ProtectionCounterResetTimingTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)

    private val manager = ProtectionOrderManager(
        alorClient = alorClient,
        orderOutboxService = orderOutboxService,
        orderOutboxRepo = orderOutboxRepo,
        positionRepo = positionRepo,
        alorConfig = com.trading.bot.config.AlorConfig().apply { maxOrderRetries = 3 },
        portfolioResolver = { "D12345" },
        onSlProtectionFailed = {},
        protectionOrdersEnabled = true,
        applyCloseExecution = { _, _, _, _ -> },
    )

    @BeforeEach
    suspend fun setUp() {
        Mockito.reset(alorClient, orderOutboxService, orderOutboxRepo, positionRepo)
        stubCancelProtectionSafe()
        stubSave()
    }

    /**
     * 1: cancelProtectionOrders must NOT reset cumulativeSlFillQty.
     * Before fix: cumulativeSlFillQty = 0 after cancel request.
     * After fix: cumulativeSlFillQty preserved.
     */
    @Test
    fun cancelRequest_preservesSlCumulativeFill() = runBlocking {
        val pos = Position(
            id = 1L,
            ticker = "CNYRUB_TOM",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("12.50"),
            instrumentType = InstrumentType.FX,
            status = PositionStatus.OPEN,
            slOrderId = "SL-OLD",
            slOrderPrice = BigDecimal("12.40"),
            cumulativeSlFillQty = 3,
            stopLoss = BigDecimal("12.40"),
            takeProfit = BigDecimal("12.60"),
        )

        manager.cancelProtectionOrders(pos)

        assertTrue(pos.slCancelPending) { "slCancelPending should be true" }
        assertEquals(3, pos.cumulativeSlFillQty) { "cumulativeSlFillQty MUST be preserved — old order still live" }
    }

    /**
     * 2: cancelProtectionOrders must NOT reset cumulativeTpFillQty.
     */
    @Test
    fun cancelRequest_preservesTpCumulativeFill() = runBlocking {
        val pos = Position(
            id = 1L,
            ticker = "CNYRUB_TOM",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("12.50"),
            instrumentType = InstrumentType.FX,
            status = PositionStatus.OPEN,
            tpOrderId = "TP-OLD",
            tpOrderPrice = BigDecimal("12.60"),
            cumulativeTpFillQty = 2,
            stopLoss = BigDecimal("12.40"),
            takeProfit = BigDecimal("12.60"),
        )

        manager.cancelProtectionOrders(pos)

        assertTrue(pos.tpCancelPending) { "tpCancelPending should be true" }
        assertEquals(2, pos.cumulativeTpFillQty) { "cumulativeTpFillQty MUST be preserved — old order still live" }
    }

    /**
     * 3: Late WS SL fill after cancel request: delta = exchangeFilled - preservedCounter.
     * Before fix: delta = 5 - 0 = 5 (double-count).
     * After fix: delta = 5 - 3 = 2 (correct).
     */
    @Test
    fun lateSlFillAfterCancel_usesCorrectDelta() = runBlocking {
        var closedQty = 0
        val mgrWithClose = ProtectionOrderManager(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = com.trading.bot.config.AlorConfig().apply { maxOrderRetries = 3 },
            portfolioResolver = { "D12345" },
            onSlProtectionFailed = {},
            protectionOrdersEnabled = true,
            applyCloseExecution = { pos, qty, price, _ ->
                closedQty = qty
                pos.quantity -= qty
                if (pos.quantity <= 0) {
                    pos.status = PositionStatus.CLOSED
                    pos.closePrice = price
                }
            },
        )

        val pos = Position(
            id = 1L,
            ticker = "CNYRUB_TOM",
            direction = PositionDirection.LONG,
            quantity = 2,
            entryPrice = BigDecimal("12.50"),
            instrumentType = InstrumentType.FX,
            status = PositionStatus.OPEN,
            slOrderId = "SL-OLD",
            slOrderPrice = BigDecimal("12.40"),
            cumulativeSlFillQty = 3,
            slCancelPending = true,
            stopLoss = BigDecimal("12.40"),
            takeProfit = null,
        )

        // Old SL order is still FILLED (exchange sent late WS fill)
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution("FILLED", 5, BigDecimal("12.40")))
        stubSave()

        mgrWithClose.reconcileProtectionOrders(pos)

        // delta = 5 - 3 = 2, NOT 5 - 0 = 5
        assertEquals(2, closedQty) { "delta must be 2 (5 cumulative - 3 prev), not 5 (double-count)" }
        assertEquals(5, pos.cumulativeSlFillQty) { "cumulative updated to exchange value" }
    }

    /**
     * 4: When old order confirmed gone (CANCELED), counter IS reset to 0 for new order.
     */
    @Test
    fun goneStatus_resetsCounterForNewOrder() = runBlocking {
        val mgrWithPlacement = ProtectionOrderManager(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = com.trading.bot.config.AlorConfig().apply { maxOrderRetries = 3 },
            portfolioResolver = { "D12345" },
            onSlProtectionFailed = {},
            protectionOrdersEnabled = true,
            applyCloseExecution = { _, _, _, _ -> },
        )

        val pos = Position(
            id = 1L,
            ticker = "CNYRUB_TOM",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("12.50"),
            instrumentType = InstrumentType.FX,
            status = PositionStatus.OPEN,
            slOrderId = "SL-OLD",
            slOrderPrice = BigDecimal("12.40"),
            cumulativeSlFillQty = 3,
            slCancelPending = true,
            stopLoss = BigDecimal("12.40"),
            takeProfit = null,
        )

        // Old SL is CANCELED → gone status → counter reset
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution("CANCELED", 0, null))
        whenever(orderOutboxService.placeOrder(
            anyOrNull(), anyOrNull(), Mockito.anyInt(), anyOrNull(), anyOrNull(),
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
        )).thenAnswer {
            OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), "SL-NEW", success = true)
        }
        stubSave()

        mgrWithPlacement.reconcileProtectionOrders(pos)

        assertEquals(0, pos.cumulativeSlFillQty) { "counter reset after gone status — ready for new order" }
        assertFalse(pos.slCancelPending) { "slCancelPending cleared" }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private suspend fun stubCancelProtectionSafe() {
        whenever(orderOutboxService.placeCancelOrder(anyOrNull<Long>(), anyOrNull(), anyOrNull()))
            .thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = true))
    }

    private suspend fun stubSave() {
        whenever(positionRepo.save(anyOrNull<Position>())).thenAnswer { it.getArgument<Position>(0) }
    }
}
