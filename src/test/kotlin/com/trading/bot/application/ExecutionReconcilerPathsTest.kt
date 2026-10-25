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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [ExecutionReconciler] — разрешение pending-входов и pending-закрытий по outbox.
 *
 * Проверяются: диспетчеризация по состоянию позиции, частичный филл входа с
 * ожиданием/отменой остатка, «исчезнувшая» заявка с уже исполненным объёмом
 * (НЕ abandoned — иначе локально позиция закрывается «в ноль», а на бирже остаётся
 * без учёта), отказ доставки во всех терминальных статусах, а также сброс
 * close-состояния для повторного закрытия.
 */
class ExecutionReconcilerPathsTest {
    private val alorClient = mock<AlorClient>()
    private val orderOutboxRepo = mock<OrderOutboxRepository>()
    private val positionRepo = mock<PositionRepository>()
    private val tradeEventService = mock<TradeEventService>()
    private val meterRegistry = SimpleMeterRegistry()
    private val opened = AtomicInteger(0)
    private val abandonedCleanup = AtomicReference<Long?>()
    private val confirmedClose = AtomicInteger(0)
    private val attachCalls = AtomicInteger(0)

    private val reconciler =
        ExecutionReconciler(
            alorClient = alorClient,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig =
                AlorConfig().apply {
                    maxOrderRetries = 3
                    entryPartialFillCancelAfterMs = 30_000
                },
            objectMapper = jacksonObjectMapper(),
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            metricPrefix = "test.reconciler",
            portfolioResolver = { "D12345" },
            onEntryOpened = { opened.incrementAndGet() },
            isGoneStatus = { it.status.uppercase() in GONE },
            isFilledStatus = { it.status.uppercase() in FILLED && it.filledQuantity > 0 },
            attachProtectionOrders = { attachCalls.incrementAndGet() },
            confirmCloseFill = { _, _, _ -> confirmedClose.incrementAndGet() },
            reconcileProtectionOrders = { },
            onAbandonCleanup = { abandonedCleanup.set(it) },
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
        return entryPosition()
    }

    private fun entryPosition(
        quantity: Int = 10,
        pendingEntry: Boolean = true,
        id: Long? = 1L,
    ): Position =
        Position(
            id = id,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = quantity,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            pendingEntry = pendingEntry,
        )

    private fun closePosition(
        pendingClose: Boolean = true,
        closeOrderId: String? = "CLOSE-1",
        id: Long? = 1L,
    ): Position =
        Position(
            id = id,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            pendingClose = pendingClose,
            closeOrderId = closeOrderId,
        )

    private fun entryOutbox(
        status: OutboxStatus = OutboxStatus.SENT,
        alorOrderId: String? = "ENTRY-1",
        payload: String = """{"qty":10}""",
        createdAt: LocalDateTime = LocalDateTime.now(),
        retryCount: Int = 0,
    ): OrderOutbox =
        OrderOutbox(
            payloadJson = payload,
            status = status,
            alorOrderId = alorOrderId,
            idempotencyKey = "idem-1",
            retryCount = retryCount,
            positionId = 1L,
            createdAt = createdAt,
        )

    // ─── диспетчеризация ─────────────────────────────────────────────

    @Test
    fun `reconcilePosition resolves pending entry through outbox`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry")).thenReturn(entryOutbox())
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("FILLED", 10, BigDecimal("100.5")))

            reconciler.reconcilePosition(pos)

            assertFalse(pos.pendingEntry)
            assertEquals(1, opened.get())
            assertEquals(1, attachCalls.get())
            verify(tradeEventService).recordPositionOpened(pos)
        }

    @Test
    fun `reconcilePosition resolves close via outbox when order id is unknown`(): Unit =
        runBlocking {
            val pos = closePosition(closeOrderId = null)
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "close"))
                .thenReturn(entryOutbox(payload = "{}"))

            reconciler.reconcilePosition(pos)

            assertEquals("ENTRY-1", pos.closeOrderId)
            assertEquals(1, confirmedClose.get())
        }

    @Test
    fun `reconcilePosition confirms close fill when order id is known`(): Unit =
        runBlocking {
            val pos = closePosition()

            reconciler.reconcilePosition(pos)

            assertEquals(1, confirmedClose.get())
        }

    // ─── вход ────────────────────────────────────────────────────────

    @Test
    fun `entry without id cannot be reconciled`(): Unit =
        runBlocking {
            val pos = entryPosition(id = null)

            reconciler.resolveEntryViaOutbox(pos)

            assertTrue(pos.pendingEntry)
            verify(orderOutboxRepo, never()).findLatestByPositionId(Mockito.anyLong(), anyOrNull())
        }

    @Test
    fun `entry without outbox row stays pending`(): Unit =
        runBlocking {
            val pos = entryPosition()

            reconciler.resolveEntryViaOutbox(pos)

            assertTrue(pos.pendingEntry)
            assertEquals(0, opened.get())
        }

    @Test
    fun `entry payload without qty falls back to position quantity`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry"))
                .thenReturn(entryOutbox(payload = """{"purpose":"entry"}"""))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("FILLED", 10, BigDecimal("101")))

            reconciler.resolveEntryViaOutbox(pos)

            assertFalse(pos.pendingEntry)
            assertEquals(10, pos.quantity)
            assertEquals(0, BigDecimal("101").compareTo(pos.entryPrice))
        }

    @Test
    fun `partial entry fill is booked locally and remainder waits`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry"))
                .thenReturn(entryOutbox(payload = """{"qty":10}""", createdAt = LocalDateTime.now()))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("PARTIALLY_FILLED", 4, BigDecimal("99.5")))

            reconciler.resolveEntryViaOutbox(pos)

            assertTrue(pos.pendingEntry, "остаток ещё стоит на бирже — вход не завершён")
            assertEquals(4, pos.quantity)
            assertEquals(0, BigDecimal("99.5").compareTo(pos.entryPrice))
            verify(positionRepo).save(pos)
            assertEquals(0, opened.get())
        }

    @Test
    fun `entry remainder cancel failure leaves position for the next cycle`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry"))
                .thenReturn(entryOutbox(payload = """{"qty":10}""", createdAt = LocalDateTime.now().minusMinutes(5)))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("PARTIALLY_FILLED", 4, BigDecimal("99.5")))
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("exchange timeout"))

            reconciler.resolveEntryViaOutbox(pos)

            assertTrue(pos.pendingEntry)
            assertEquals(0, opened.get())
        }

    @Test
    fun `entry remainder cancel uncertain keeps position pending`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry"))
                .thenReturn(entryOutbox(payload = """{"qty":10}""", createdAt = LocalDateTime.now().minusMinutes(5)))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("PARTIALLY_FILLED", 4, BigDecimal("99.5")))
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.UNCERTAIN)

            reconciler.resolveEntryViaOutbox(pos)

            assertTrue(pos.pendingEntry)
            assertEquals(0, opened.get())
        }

    @Test
    fun `gone entry without any fill is abandoned`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry")).thenReturn(entryOutbox())
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("CANCELED", 0, null))

            reconciler.resolveEntryViaOutbox(pos)

            assertConfirmedAbandon(pos, CloseReason.ENTRY_REJECTED)
        }

    /**
     * Ключевой инвариант: «исчезнувшая» заявка с исполненным объёмом НЕ отменяет
     * локальный вход в ноль — позиция фиксируется, иначе на бирже остаётся
     * неучтённый объём.
     */
    @Test
    fun `gone entry with partial fill is finalized instead of abandoned`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry"))
                .thenReturn(entryOutbox(payload = """{"qty":10}""", createdAt = LocalDateTime.now().minusMinutes(5)))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("EXPIRED", 4, BigDecimal("99.0")))
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.CONFIRMED)

            reconciler.resolveEntryViaOutbox(pos)

            assertFalse(pos.pendingEntry)
            assertEquals(PositionStatus.OPEN, pos.status)
            assertEquals(4, pos.quantity)
            assertEquals(0, BigDecimal("99.0").compareTo(pos.entryPrice))
            assertNull(abandonedCleanup.get())
            assertEquals(1, opened.get())
        }

    @Test
    fun `entry delivery failed at retry limit is abandoned`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry"))
                .thenReturn(entryOutbox(status = OutboxStatus.FAILED, alorOrderId = null, retryCount = 3))

            reconciler.resolveEntryViaOutbox(pos)

            assertConfirmedAbandon(pos, CloseReason.ENTRY_NOT_CONFIRMED)
        }

    @Test
    fun `entry blocked by interlock is abandoned`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry"))
                .thenReturn(entryOutbox(status = OutboxStatus.BLOCKED, alorOrderId = null))

            reconciler.resolveEntryViaOutbox(pos)

            assertConfirmedAbandon(pos, CloseReason.ENTRY_NOT_CONFIRMED)
        }

    @Test
    fun `entry rejected by exchange is abandoned`(): Unit =
        runBlocking {
            val pos = entryPosition()
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "entry"))
                .thenReturn(entryOutbox(status = OutboxStatus.REJECTED, alorOrderId = null))

            reconciler.resolveEntryViaOutbox(pos)

            assertConfirmedAbandon(pos, CloseReason.ENTRY_NOT_CONFIRMED)
        }

    // ─── закрытие ────────────────────────────────────────────────────

    @Test
    fun `close cancel waits while old order is still filled`(): Unit =
        runBlocking {
            val pos = closePosition().apply { closeCancelPending = true }
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("FILLED", 5, BigDecimal("100")))

            reconciler.resolveCloseCancel(pos)

            assertTrue(pos.pendingClose, "пока заявка жива, состояние close не сбрасывается")
            assertEquals(0, confirmedClose.get())
        }

    @Test
    fun `close without id clears pending state`(): Unit =
        runBlocking {
            val pos = closePosition().apply { closeCancelPending = true }
            pos.closeOrderId = null

            reconciler.resolveCloseCancel(pos)

            assertFalse(pos.pendingClose)
            assertFalse(pos.closeCancelPending)
            verify(positionRepo).save(pos)
        }

    @Test
    fun `close outbox permanently failed resets state for a fresh close`(): Unit =
        runBlocking {
            val pos = closePosition(closeOrderId = null)
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "close"))
                .thenReturn(entryOutbox(status = OutboxStatus.FAILED, alorOrderId = null, retryCount = 3))

            reconciler.resolveCloseViaOutbox(pos)

            assertFalse(pos.pendingClose)
            assertNull(pos.closeOrderId)
            assertEquals(0, confirmedClose.get())
        }

    @Test
    fun `close blocked by interlock resets state for a fresh close`(): Unit =
        runBlocking {
            val pos = closePosition(closeOrderId = null)
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "close"))
                .thenReturn(entryOutbox(status = OutboxStatus.BLOCKED, alorOrderId = null))

            reconciler.resolveCloseViaOutbox(pos)

            assertFalse(pos.pendingClose)
            assertNull(pos.closeOrderId)
        }

    @Test
    fun `close rejected by exchange resets state for a fresh close`(): Unit =
        runBlocking {
            val pos = closePosition(closeOrderId = null)
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "close"))
                .thenReturn(entryOutbox(status = OutboxStatus.REJECTED, alorOrderId = null))

            reconciler.resolveCloseViaOutbox(pos)

            assertFalse(pos.pendingClose)
            assertNull(pos.closeOrderId)
        }

    @Test
    fun `close without id cannot be reconciled`(): Unit =
        runBlocking {
            val pos = closePosition(closeOrderId = null, id = null)

            reconciler.resolveCloseViaOutbox(pos)

            verify(orderOutboxRepo, never()).findLatestByPositionId(anyOrNull(), eq("close"))
        }

    private suspend fun assertConfirmedAbandon(
        pos: Position,
        reason: CloseReason,
    ) {
        assertFalse(pos.pendingEntry)
        assertEquals(PositionStatus.CLOSED, pos.status)
        assertEquals(reason, pos.closeReason)
        assertEquals(1L, abandonedCleanup.get())
        Mockito.verify(positionRepo).releaseEntry(pos.ticker, pos.accountId)
        assertEquals(
            1.0,
            meterRegistry.counter("test.reconciler.entry.abandoned", "ticker", pos.ticker, "reason", reason.code).count(),
        )
    }

    private companion object {
        val GONE = setOf("CANCELED", "CANCELLED", "REJECTED", "EXPIRED")
        val FILLED = setOf("FILLED", "PARTIALLY_FILLED")
    }
}
