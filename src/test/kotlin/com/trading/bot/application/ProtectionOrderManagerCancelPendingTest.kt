package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.UUID

/**
 * Regression tests for slCancelPending / tpCancelPending state machine (P0#1).
 *
 * A. cancel requested → slOrderId remains → slCancelPending=true → attachProtectionOrders() → NO new SL
 * B. cancel UNCERTAIN → old SL remains → NO replacement
 * C. gone status → clears orderId + cancelPending → next attach creates new SL
 * D. gone status on TP → same flow for TP
 * E. SL fill while cancelPending → atomic clear + position close
 * F. cancelProtectionOrders with skip="SL" → only TP cancelled
 */
class ProtectionOrderManagerCancelPendingTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val manager =
        ProtectionOrderManager(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
            meterRegistry = meterRegistry,
            metricPrefix = "test.protect",
            portfolioResolver = { "D12345" },
            onSlProtectionFailed = {},
            protectionOrdersEnabled = true,
            applyCloseExecution = { _, _, _, _ -> },
        )

    @BeforeEach
    suspend fun setup() {
        stubCancelProtectionSafe()
        stubSave()
    }

    /**
     * A: cancel requested → slOrderId remains → slCancelPending=true → attach skips.
     */
    @Test
    fun cancelRequested_preservesOrderIdAndBlocksNewSL() {
        runBlocking {
        val pos = openPosition(slOrderId = "SL-OLD", tpOrderId = "TP-OLD")

        manager.cancelProtectionOrders(pos)

        assertTrue(pos.slCancelPending, "slCancelPending should be true")
        assertEquals("SL-OLD", pos.slOrderId, "slOrderId must NOT be cleared during cancel")
        assertTrue(pos.tpCancelPending, "tpCancelPending should be true")
        assertEquals("TP-OLD", pos.tpOrderId, "tpOrderId must NOT be cleared during cancel")

        verify(orderOutboxService).placeCancelOrder(1L, "SL-OLD", null)
        verify(orderOutboxService).placeCancelOrder(1L, "TP-OLD", null)

        // attachProtectionOrders should skip — slCancelPending=true blocks placement
        manager.attachProtectionOrders(pos)

        // Verify NO new SL/TP outbox was placed
        verify(orderOutboxService, never()).placeOrder(
            anyOrNull(), anyOrNull(), Mockito.anyInt(), anyOrNull(), anyOrNull(),
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
        )
        }
    }

    /**
     * B: reconcile with UNCERTAIN cancel → old SL remains, no replacement.
     */
    @Test
    fun uncertainCancel_keepsOldSlNoReplacement() = runBlocking {
        val pos = openPosition(slOrderId = "SL-1", slOrderPrice = BigDecimal("91500"), takeProfit = null)

        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(null)
        whenever(orderOutboxRepo.findLatestConfirmedCancel(anyOrNull(), anyOrNull())).thenReturn(null)

        manager.reconcileProtectionOrders(pos)

        assertEquals("SL-1", pos.slOrderId, "SL orderId must remain on uncertain cancel")
    }

    /**
     * C: gone status (CANCELED) → clears SL orderId + cancelPending → new SL placed.
     */
    @Test
    fun goneStatus_clearsCancelPendingAndAllowsNewSL() = runBlocking {
        val pos = openPosition(
            slOrderId = "SL-1",
            slOrderPrice = BigDecimal("91500"),
            slCancelPending = true,
            stopLoss = BigDecimal("91500"),
        )

        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution(status = "CANCELED", filledQuantity = 0, avgPrice = null))
        stubNewProtectionPlacement("SL-NEW")

        manager.reconcileProtectionOrders(pos)

        assertFalse(pos.slCancelPending, "slCancelPending should be cleared after gone status")
        assertEquals("SL-NEW", pos.slOrderId, "New SL should be placed after old one is gone")
    }

    /**
     * D: gone status (EXPIRED) on TP → clears TP orderId + cancelPending → new TP placed.
     */
    @Test
    fun goneStatusOnTp_clearsTpCancelPendingAndAllowsNewTP() = runBlocking {
        val pos = openPosition(
            tpOrderId = "TP-1",
            tpOrderPrice = BigDecimal("93000"),
            tpCancelPending = true,
            takeProfit = BigDecimal("93000"),
        )

        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution(status = "EXPIRED", filledQuantity = 0, avgPrice = null))
        stubNewProtectionPlacement("TP-NEW")

        manager.reconcileProtectionOrders(pos)

        assertFalse(pos.tpCancelPending, "tpCancelPending should be cleared")
        assertEquals("TP-NEW", pos.tpOrderId, "New TP should be placed after old one is gone")
    }

    /**
     * E: SL fill while cancelPending → atomic clear + position closed.
     */
    @Test
    fun filledStatus_clearsCancelPendingAtomically() = runBlocking {
        var closedPosition: Position? = null
        val mgrWithClose = ProtectionOrderManager(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
            meterRegistry = meterRegistry,
            metricPrefix = "test.protect",
            portfolioResolver = { "D12345" },
            onSlProtectionFailed = {},
            protectionOrdersEnabled = true,
            applyCloseExecution = { pos, qty, price, reason ->
                pos.quantity = qty
                pos.closePrice = price
                pos.status = PositionStatus.CLOSED
                closedPosition = pos
            },
        )

        val pos = openPosition(
            slOrderId = "SL-1",
            slCancelPending = true,
            stopLoss = BigDecimal("91500"),
        )

        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution(status = "FILLED", filledQuantity = 1, avgPrice = BigDecimal("91490")))

        mgrWithClose.reconcileProtectionOrders(pos)

        assertEquals(null, pos.slOrderId, "SL orderId must be cleared after fill")
        assertFalse(pos.slCancelPending, "slCancelPending must be cleared after fill")
        assertEquals(PositionStatus.CLOSED, pos.status, "Position should be closed by SL fill")
    }

    /**
     * F: cancelProtectionOrders with skip="SL" → only TP is cancelled, SL untouched.
     */
    @Test
    fun cancelWithSkip_slOnly() {
        runBlocking {
            val pos = openPosition(slOrderId = "SL-1", tpOrderId = "TP-1")

            manager.cancelProtectionOrders(pos, skip = "SL")

            assertFalse(pos.slCancelPending, "SL should NOT be cancelled when skip=SL")
            assertEquals("SL-1", pos.slOrderId, "SL orderId must remain when skip=SL")
            assertTrue(pos.tpCancelPending, "TP should still be cancelled")
            assertEquals("TP-1", pos.tpOrderId, "TP orderId must remain (cancel pending)")
            verify(orderOutboxService).placeCancelOrder(1L, "TP-1", null)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private suspend fun stubCancelProtectionSafe() {
        whenever(
            orderOutboxService.placeCancelOrder(anyOrNull<Long>(), anyOrNull(), anyOrNull())
        ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = true))
    }

    private suspend fun stubSave() {
        whenever(positionRepo.save(anyOrNull<Position>())).thenAnswer { it.getArgument<Position>(0) }
    }

    private suspend fun stubNewProtectionPlacement(orderId: String) {
        whenever(
            orderOutboxService.placeOrder(
                anyOrNull(), anyOrNull(), Mockito.anyInt(), anyOrNull(), anyOrNull(),
                anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            )
        ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), orderId, success = true))
    }

    private fun openPosition(
        slOrderId: String? = null,
        slOrderPrice: BigDecimal? = null,
        slCancelPending: Boolean = false,
        tpOrderId: String? = null,
        tpOrderPrice: BigDecimal? = null,
        tpCancelPending: Boolean = false,
        stopLoss: BigDecimal? = BigDecimal("91500"),
        takeProfit: BigDecimal? = BigDecimal("93000"),
    ): Position =
        Position(
            id = 1L,
            ticker = "CNYRUB_TOM",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("12.50"),
            instrumentType = InstrumentType.FX,
            status = PositionStatus.OPEN,
            slOrderId = slOrderId,
            slOrderPrice = slOrderPrice,
            slCancelPending = slCancelPending,
            tpOrderId = tpOrderId,
            tpOrderPrice = tpOrderPrice,
            tpCancelPending = tpCancelPending,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
        )
}
