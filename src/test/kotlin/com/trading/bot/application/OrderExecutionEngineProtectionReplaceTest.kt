package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.OutboxStatus
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
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.callSuspend

class OrderExecutionEngineProtectionReplaceTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val engine =
        OrderExecutionEngine(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = Mockito.mock(com.trading.bot.config.AlorConfig::class.java),
            objectMapper = objectMapper,
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            pnlCalculator = PnlCalculator.plain(),
            instrumentFilter = { true },
            metricPrefix = "test",
            onEntryOpened = {},
            onPositionClosed = {},
            protectionOrdersEnabled = true,
            portfolioResolver = { "D12345" },
        )

    private fun pendingReplacePos(): Position =
        Position(
            id = 1L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("150000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
            slOrderId = "old-sl",
            slOrderPrice = BigDecimal("149000"),
            slPendingReplace = true,
            tpOrderId = "old-tp",
            tpOrderPrice = BigDecimal("151000"),
        )

    /**
     * orderId-conditional verifyOrder stub: returns [slResult] for "old-sl",
     * [tpResult] for "old-tp", null for anything else.
     *
     * reconcileProtectionOrders calls checkProtectionFills FIRST (which verifies
     * both SL and TP), then finishProtectionReplacement. We must return the right
     * result per orderId to avoid checkProtectionFills clearing the wrong order.
     */
    private suspend fun stubVerifyOrderById(
        slResult: AlorClient.OrderExecution? = null,
        tpResult: AlorClient.OrderExecution? = null,
    ) {
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { invocation ->
            val orderId = invocation.getArgument<String>(0)
            when (orderId) {
                "old-sl" -> slResult
                "old-tp" -> tpResult
                else -> null
            }
        }
    }

    private suspend fun stubCancelOrderSafe() {
        whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.CancelResult.REJECTED)
    }

    private suspend fun stubCancelProtectionSafe() {
        whenever(
            orderOutboxService.placeCancelOrder(anyOrNull<Long>(), anyOrNull(), anyOrNull())
        ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = true))
    }

    private suspend fun stubNewProtectionPlacementSafe() {
        whenever(
            orderOutboxService.placeOrder(
                anyOrNull(), anyOrNull(), Mockito.anyInt(), anyOrNull(), anyOrNull(),
                anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            )
        ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = false))
    }

    private suspend fun stubSave() {
        whenever(positionRepo.save(anyOrNull<Position>())).thenAnswer { it.getArgument<Position>(0) }
    }

    private suspend fun stubFullClose() {
        whenever(positionRepo.transitionToClosed(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), Mockito.anyInt()))
            .thenReturn(true)
        whenever(positionRepo.releaseEntry(anyOrNull(), anyOrNull())).thenAnswer { }
        doReturn(Unit).whenever(tradeEventService).recordPositionClosed(anyOrNull(), anyOrNull())
    }

    // ── UNCERTAIN ─────────────────────────────────────────────────────────

    @Test
    fun `uncertain cancel keeps replace pending and retry reuses same idempotency key`() = runBlocking {
        stubVerifyOrderById()
        stubNewProtectionPlacementSafe()
        stubSave()

        whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.CancelResult.UNCERTAIN)
            .thenReturn(AlorClient.CancelResult.CONFIRMED)

        val pos = pendingReplacePos()
        engine.reconcilePosition(pos)
        assertTrue(pos.slPendingReplace)
        assertEquals("old-sl", pos.slOrderId)

        engine.reconcilePosition(pos)
        assertFalse(pos.slPendingReplace)

        val cancelKeys =
            Mockito.mockingDetails(alorClient).invocations
                .filter { it.method.name == "cancelOrder" }
                .map { it.arguments[1] as String }
        assertEquals(listOf("prot-cancel-old-sl", "prot-cancel-old-sl"), cancelKeys)
    }

    // ── REJECTED state machine: SL ────────────────────────────────────────
    //
    // reconcileProtectionOrders calls checkProtectionFills FIRST which verifies
    // both SL and TP. When checkProtectionFills sees CANCELED/FILLED for an order,
    // it handles it BEFORE finishProtectionReplacement runs.
    //
    // - CANCELED test: checkProtectionFills catches CANCELED → clears SL (GONE path)
    // - FILLED test: checkProtectionFills catches FILLED → full close via applyExchangeProtectionClose
    // - UNKNOWN tests: checkProtectionFills gets null → skip; finishProtectionReplacement gets REJECTED → verify → null → return

    @Test
    fun `SL rejected + verify FILLED closes position atomically`() = runBlocking {
        stubVerifyOrderById(
            slResult = AlorClient.OrderExecution(status = "FILLED", filledQuantity = 1, avgPrice = BigDecimal("149500")),
        )
        stubCancelProtectionSafe()
        stubNewProtectionPlacementSafe()
        stubSave()
        stubFullClose()

        val pos = pendingReplacePos()
        engine.reconcilePosition(pos)

        assertEquals(PositionStatus.CLOSED, pos.status)
    }

    @Test
    fun `SL FILLED close is atomic - transitionToClosed before any premature save`() = runBlocking {
        stubVerifyOrderById(
            slResult = AlorClient.OrderExecution(status = "FILLED", filledQuantity = 1, avgPrice = BigDecimal("149500")),
        )
        stubCancelProtectionSafe()
        stubNewProtectionPlacementSafe()
        stubSave()
        stubFullClose()

        val pos = pendingReplacePos()
        engine.reconcilePosition(pos)

        val invocations = Mockito.mockingDetails(positionRepo).invocations
        val transitionCalls = invocations.filter { it.method.name == "transitionToClosed" }
        assertEquals(1, transitionCalls.size, "transitionToClosed must be called exactly once")

        val saveCalls = invocations.filter { it.method.name == "save" }
        assertEquals(0, saveCalls.size, "save() must NOT be called - checkProtectionFills returns early at line 548, skipping save() at line 571")

        val releaseCalls = invocations.filter { it.method.name == "releaseEntry" }
        assertEquals(1, releaseCalls.size, "releaseEntry must be called exactly once after transitionToClosed")

        val tradeEventCalls = Mockito.mockingDetails(tradeEventService).invocations
            .filter { it.method.name == "recordPositionClosed" }
        assertEquals(1, tradeEventCalls.size, "recordPositionClosed must be called exactly once")

        assertEquals(PositionStatus.CLOSED, pos.status)
    }

    @Test
    fun `SL rejected + verify CANCELED clears SL ID via checkProtectionFills`() = runBlocking {
        stubVerifyOrderById(
            slResult = AlorClient.OrderExecution(status = "CANCELED", filledQuantity = 0, avgPrice = null),
        )
        stubSave()
        stubNewProtectionPlacementSafe()

        val pos = pendingReplacePos()
        engine.reconcilePosition(pos)

        assertEquals(null, pos.slOrderId)
        assertEquals(null, pos.slOrderPrice)
        assertFalse(pos.slPendingReplace)
        assertEquals("old-tp", pos.tpOrderId)
    }

    @Test
    fun `SL rejected + verify null preserves SL ID (UNKNOWN)`() = runBlocking {
        stubVerifyOrderById()
        stubCancelOrderSafe()
        stubNewProtectionPlacementSafe()

        val pos = pendingReplacePos()
        engine.reconcilePosition(pos)

        assertEquals("old-sl", pos.slOrderId)
        assertTrue(pos.slPendingReplace)
        assertEquals("old-tp", pos.tpOrderId)
    }

    @Test
    fun `SL rejected + verify unknown status preserves SL ID`() = runBlocking {
        stubVerifyOrderById(
            slResult = AlorClient.OrderExecution(status = "PARTIALLY_FILLED", filledQuantity = 0, avgPrice = null),
        )
        stubCancelOrderSafe()
        stubNewProtectionPlacementSafe()

        val pos = pendingReplacePos()
        engine.reconcilePosition(pos)

        assertEquals("old-sl", pos.slOrderId)
        assertTrue(pos.slPendingReplace)
        assertEquals("old-tp", pos.tpOrderId)
    }

    // ── REJECTED state machine: TP ────────────────────────────────────────

    @Test
    fun `TP rejected + verify CANCELED clears TP ID`() = runBlocking {
        val pos = pendingReplacePos().copy(
            slOrderId = null,
            slOrderPrice = null,
            slPendingReplace = false,
            tpPendingReplace = true,
        )
        stubVerifyOrderById(
            tpResult = AlorClient.OrderExecution(status = "CANCELED", filledQuantity = 0, avgPrice = null),
        )
        stubSave()
        stubNewProtectionPlacementSafe()

        engine.reconcilePosition(pos)

        assertFalse(pos.tpPendingReplace)
        assertEquals(null, pos.tpOrderId)
        assertEquals(null, pos.tpOrderPrice)
    }

    @Test
    fun `TP rejected + verify null preserves TP ID (UNKNOWN)`() = runBlocking {
        val pos = pendingReplacePos().copy(
            slOrderId = null,
            slOrderPrice = null,
            slPendingReplace = false,
            tpPendingReplace = true,
        )
        stubVerifyOrderById()
        stubCancelOrderSafe()
        stubNewProtectionPlacementSafe()

        engine.reconcilePosition(pos)

        assertEquals("old-tp", pos.tpOrderId)
        assertTrue(pos.tpPendingReplace)
    }

    // ── isGoneStatus strict whitelist ─────────────────────────────────────

    @Test
    fun `isGoneStatus rejects CANCEL_REJECTED and matches terminal statuses`() {
        val engine = Mockito.mock(OrderExecutionEngine::class.java)
        val method = OrderExecutionEngine::class.java.getDeclaredMethod("isGoneStatus", AlorClient.OrderExecution::class.java)
        method.isAccessible = true

        fun gone(s: String) = method.invoke(engine, AlorClient.OrderExecution(status = s, filledQuantity = 0, avgPrice = null)) as Boolean

        assertFalse(gone("CANCEL_REJECTED"))
        assertFalse(gone("NEW"))
        assertFalse(gone("PARTIALLY_FILLED"))
        assertFalse(gone("FILLED"))

        assertTrue(gone("CANCELED"))
        assertTrue(gone("REJECTED"))
        assertTrue(gone("EXPIRED"))
    }

    // ── Entry status whitelist (resolveEntryViaOutbox) ──────────────────────

    private fun pendingEntryPos(): Position =
        Position(
            id = 10L,
            ticker = "CNYRUB_TOM",
            direction = PositionDirection.LONG,
            quantity = 100,
            entryPrice = BigDecimal("12.50"),
            instrumentType = InstrumentType.FX,
            status = PositionStatus.OPEN,
            pendingEntry = true,
        )

    private fun entryOutbox(
        status: OutboxStatus = OutboxStatus.SENT,
        alorOrderId: String? = "entry-order-1",
    ): OrderOutbox =
        OrderOutbox(
            id = UUID.randomUUID(),
            payloadJson = """{"qty":100}""",
            status = status,
            alorOrderId = alorOrderId,
            idempotencyKey = "idem-key",
            positionId = 10L,
            accountId = 1L,
            createdAt = LocalDateTime.now(),
        )

    @Test
    fun `CANCEL_REJECTED entry keeps position pending`() = runBlocking {
        val pos = pendingEntryPos()
        whenever(orderOutboxRepo.findLatestByPositionId(10L)).thenReturn(entryOutbox())
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution(status = "CANCEL_REJECTED", filledQuantity = 0, avgPrice = null))
        stubSave()

        engine.resolveEntryViaOutbox(pos)

        assertTrue(pos.pendingEntry, "CANCEL_REJECTED must NOT abandon entry")
        assertEquals(PositionStatus.OPEN, pos.status)
    }

    @Test
    fun `CANCELED entry is abandoned`() = runBlocking {
        val pos = pendingEntryPos()
        whenever(orderOutboxRepo.findLatestByPositionId(10L)).thenReturn(entryOutbox())
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution(status = "CANCELED", filledQuantity = 0, avgPrice = null))
        stubSave()
        whenever(positionRepo.releaseEntry(anyOrNull(), anyOrNull())).thenAnswer { }

        engine.resolveEntryViaOutbox(pos)

        assertFalse(pos.pendingEntry)
        assertEquals(PositionStatus.CLOSED, pos.status)
    }

    @Test
    fun `REJECTED entry is abandoned`() = runBlocking {
        val pos = pendingEntryPos()
        whenever(orderOutboxRepo.findLatestByPositionId(10L)).thenReturn(entryOutbox())
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution(status = "REJECTED", filledQuantity = 0, avgPrice = null))
        stubSave()
        whenever(positionRepo.releaseEntry(anyOrNull(), anyOrNull())).thenAnswer { }

        engine.resolveEntryViaOutbox(pos)

        assertFalse(pos.pendingEntry)
        assertEquals(PositionStatus.CLOSED, pos.status)
    }

    @Test
    fun `EXPIRED entry is abandoned`() = runBlocking {
        val pos = pendingEntryPos()
        whenever(orderOutboxRepo.findLatestByPositionId(10L)).thenReturn(entryOutbox())
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution(status = "EXPIRED", filledQuantity = 0, avgPrice = null))
        stubSave()
        whenever(positionRepo.releaseEntry(anyOrNull(), anyOrNull())).thenAnswer { }

        engine.resolveEntryViaOutbox(pos)

        assertFalse(pos.pendingEntry)
        assertEquals(PositionStatus.CLOSED, pos.status)
    }

    @Test
    fun `UNKNOWN entry status keeps position pending`() = runBlocking {
        val pos = pendingEntryPos()
        whenever(orderOutboxRepo.findLatestByPositionId(10L)).thenReturn(entryOutbox())
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(AlorClient.OrderExecution(status = "UNKNOWN", filledQuantity = 0, avgPrice = null))
        stubSave()

        engine.resolveEntryViaOutbox(pos)

        assertTrue(pos.pendingEntry, "UNKNOWN status must NOT abandon entry")
        assertEquals(PositionStatus.OPEN, pos.status)
    }

    // ── Orphan SL/TP cleanup for CLOSED positions ──────────────────────────

    @Test
    fun `closed position with orphan SL gets cancel scheduled via outbox`() = runBlocking {
        val pos = Position(
            id = 20L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("150000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.CLOSED,
            slOrderId = "orphan-sl",
            slOrderPrice = BigDecimal("149000"),
        )
        stubCancelProtectionSafe()
        stubSave()

        engine.reconcilePosition(pos)

        assertEquals(null, pos.slOrderId, "orphan SL ID must be cleared")
        assertEquals(null, pos.slOrderPrice, "orphan SL price must be cleared")
        Mockito.verify(positionRepo).save(pos)
    }

    @Test
    fun `closed position with orphan TP gets cancel scheduled via outbox`() = runBlocking {
        val pos = Position(
            id = 21L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("150000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.CLOSED,
            tpOrderId = "orphan-tp",
            tpOrderPrice = BigDecimal("151000"),
        )
        stubCancelProtectionSafe()
        stubSave()

        engine.reconcilePosition(pos)

        assertEquals(null, pos.tpOrderId, "orphan TP ID must be cleared")
        assertEquals(null, pos.tpOrderPrice, "orphan TP price must be cleared")
    }

    @Test
    fun `closed position without orphan SL and TP does not trigger cancel`() = runBlocking {
        val pos = Position(
            id = 22L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("150000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.CLOSED,
        )

        engine.reconcilePosition(pos)

        Mockito.verify(positionRepo, Mockito.never()).save(anyOrNull())
        Mockito.verify(orderOutboxService, Mockito.never()).placeCancelOrder(anyOrNull<Long>(), anyOrNull(), anyOrNull())
    }

    // ── closeConfirmedByPositionDelta conservative ──────────────────────────

    @Test
    fun `verifyOrder null + partial position reduction does NOT close`() = runBlocking {
        val pos = Position(
            id = 30L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 100,
            entryPrice = BigDecimal("150000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
            pendingClose = true,
            closeOrderId = "close-1",
            closeReason = com.trading.bot.model.CloseReason.STRATEGY_CLOSE,
        )
        whenever(positionRepo.findById(30L)).thenReturn(pos)
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(null)
        whenever(alorClient.getPositions(anyOrNull())).thenReturn(
            AlorClient.ReconcileResult.Ok(
                listOf(AlorClient.ExchangePosition(ticker = "Si", qty = 60L, avgPrice = BigDecimal("150100")))
            )
        )
        stubSave()

        engine.reconcilePosition(pos)

        assertEquals(PositionStatus.OPEN, pos.status, "Partial reduction must NOT close position")
        assertTrue(pos.pendingClose, "pendingClose must remain true")
    }

    @Test
    fun `verifyOrder null + position gone confirms close`() = runBlocking {
        val pos = Position(
            id = 31L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 100,
            entryPrice = BigDecimal("150000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
            pendingClose = true,
            closeOrderId = "close-2",
            closeReason = com.trading.bot.model.CloseReason.STRATEGY_CLOSE,
        )
        whenever(positionRepo.findById(31L)).thenReturn(pos)
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(null)
        whenever(alorClient.getPositions(anyOrNull())).thenReturn(
            AlorClient.ReconcileResult.Ok(emptyList())
        )
        stubSave()
        stubFullClose()

        engine.reconcilePosition(pos)

        assertEquals(PositionStatus.CLOSED, pos.status, "Position qty=0 must be confirmed as closed")
    }
}
