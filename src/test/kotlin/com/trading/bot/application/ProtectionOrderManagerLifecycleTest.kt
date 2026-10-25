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
import com.trading.bot.service.OrderOutboxService
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
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Полный жизненный цикл биржевых защитных заявок [ProtectionOrderManager]
 * (`slCancelPending`/`slPendingReplace` покрыты отдельным тестом): постановка
 * SL/TP, разрешение outbox-строк, детекция исполнения/исчезновения заявки,
 * перевыставление и отказ защиты.
 *
 * Каждый тест формирует ровно одно интересующее состояние, чтобы проверять
 * наблюдаемый эффект (флаги позиции, вызовы outbox/биржа), а не логи.
 */
class ProtectionOrderManagerLifecycleTest {
    private val alorClient = mock<AlorClient>()
    private val orderOutboxService = mock<OrderOutboxService>()
    private val orderOutboxRepo = mock<OrderOutboxRepository>()
    private val positionRepo = mock<PositionRepository>()
    private val protectionFailed = AtomicReference<Position?>()
    private val appliedClose = AtomicReference<Triple<Int, BigDecimal, CloseReason>?>()

    private val manager =
        ProtectionOrderManager(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = AlorConfig().apply { maxOrderRetries = 3 },
            portfolioResolver = { "D12345" },
            onSlProtectionFailed = { protectionFailed.set(it) },
            protectionOrdersEnabled = true,
            applyCloseExecution = { pos, filled, price, reason ->
                pos.quantity -= filled
                pos.cumulativeCloseFillQty += filled
                appliedClose.set(Triple(filled, price, reason))
            },
        )

    // ─── matcher-хелперы ─────────────────────────────────────────────

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return openPosition()
    }

    @BeforeEach
    fun setUp() {
        protectionFailed.set(null)
        appliedClose.set(null)
        runBlocking {
            whenever(orderOutboxService.placeCancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = true))
            whenever(positionRepo.save(anyPosition())).thenAnswer { it.getArgument<Position>(0) }
            whenever(orderOutboxRepo.findLatestByPositionId(anyOrNull(), anyOrNull())).thenReturn(null)
            whenever(orderOutboxRepo.findLatestConfirmedCancel(anyOrNull(), anyOrNull())).thenReturn(null)
            // SL/TP по умолчанию «не подтверждается» — тесты, которым нужен orderId, перекрывают стаб
            stubPlaceOrder(null)
            Mockito.clearInvocations(orderOutboxService, alorClient, positionRepo)
        }
    }

    private fun openPosition(
        direction: PositionDirection = PositionDirection.LONG,
        quantity: Int = 10,
        status: PositionStatus = PositionStatus.OPEN,
        stopLoss: String? = "91500",
        takeProfit: String? = "93000",
        slOrderId: String? = null,
        slOrderPrice: String? = null,
        tpOrderId: String? = null,
        tpOrderPrice: String? = null,
        slPendingReplace: Boolean = false,
        tpPendingReplace: Boolean = false,
        pendingClose: Boolean = false,
        closeCancelPending: Boolean = false,
        closeOrderId: String? = null,
        id: Long? = 1L,
    ): Position =
        Position(
            id = id,
            ticker = "CNYRUB_TOM",
            direction = direction,
            quantity = quantity,
            entryPrice = BigDecimal("91600"),
            currentPrice = BigDecimal("91600"),
            instrumentType = InstrumentType.FX,
            status = status,
            stopLoss = stopLoss?.toBigDecimal(),
            takeProfit = takeProfit?.toBigDecimal(),
            slOrderId = slOrderId,
            slOrderPrice = slOrderPrice?.toBigDecimal(),
            tpOrderId = tpOrderId,
            tpOrderPrice = tpOrderPrice?.toBigDecimal(),
            slPendingReplace = slPendingReplace,
            tpPendingReplace = tpPendingReplace,
            pendingClose = pendingClose,
            closeCancelPending = closeCancelPending,
            closeOrderId = closeOrderId,
        )

    private suspend fun stubPlaceOrder(orderId: String?) {
        whenever(
            orderOutboxService.placeOrder(
                anyOrNull(),
                anyOrNull(),
                Mockito.anyInt(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            ),
        ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), orderId, success = orderId != null))
    }

    /**
     * `verifyOrder` вызывается дважды за цикл: сначала [checkProtectionFills] (состояние
     * «жив»), затем — при разборе отказа отмены в [finishProtectionReplacement]
     * (терминальное состояние). Возвращаем ответы по порядку.
     */
    private suspend fun stubVerifyOrderSequence(vararg executions: AlorClient.OrderExecution) {
        val calls = AtomicInteger(0)
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            val index = calls.getAndIncrement().coerceAtMost(executions.size - 1)
            executions[index]
        }
    }

    private fun outbox(
        status: OutboxStatus,
        orderId: String? = null,
        retryCount: Int = 0,
    ): OrderOutbox =
        OrderOutbox(
            payloadJson = "{}",
            status = status,
            alorOrderId = orderId,
            retryCount = retryCount,
            positionId = 1L,
        )

    // ─── attach ─────────────────────────────────────────────────────

    @Test
    fun `short position places protection orders on buy side`(): Unit =
        runBlocking {
            stubPlaceOrder("ORD-1")
            val pos = openPosition(direction = PositionDirection.SHORT)

            manager.attachProtectionOrders(pos)

            verify(orderOutboxService).placeOrder(
                eq("CNYRUB_TOM"),
                eq("buy"),
                eq(10),
                anyOrNull(),
                eq("stop"),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                eq("sl"),
                anyOrNull(),
            )
            assertEquals("ORD-1", pos.slOrderId)
            assertEquals("ORD-1", pos.tpOrderId)
        }

    @Test
    fun `attach skips position without id`(): Unit =
        runBlocking {
            val pos = openPosition(id = null)

            manager.attachProtectionOrders(pos)

            verify(orderOutboxService, never()).placeOrder(
                anyOrNull(),
                anyOrNull(),
                Mockito.anyInt(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            )
        }

    @Test
    fun `unconfirmed placement leaves protection unarmed for reconciliation`(): Unit =
        runBlocking {
            stubPlaceOrder(null)
            val pos = openPosition()

            manager.attachProtectionOrders(pos)

            assertNull(pos.slOrderId)
            assertNull(pos.tpOrderId)
            verify(positionRepo, never()).save(anyPosition())
        }

    @Test
    fun `attach skips when outbox row is still pending`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.PENDING))
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.PENDING))
            val pos = openPosition()

            manager.attachProtectionOrders(pos)

            verify(orderOutboxService, never()).placeOrder(
                anyOrNull(),
                anyOrNull(),
                Mockito.anyInt(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            )
        }

    @Test
    fun `attach retries when failed row is below retry limit`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.FAILED, retryCount = 1))
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.FAILED, retryCount = 1))
            val pos = openPosition()

            manager.attachProtectionOrders(pos)

            verify(orderOutboxService, never()).placeOrder(
                anyOrNull(),
                anyOrNull(),
                Mockito.anyInt(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            )
        }

    @Test
    fun `attach places new order when sent row was already cancelled`(): Unit =
        runBlocking {
            stubPlaceOrder("ORD-2")
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "ORD-1"))
            whenever(orderOutboxRepo.findLatestConfirmedCancel(1L, "ORD-1"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "ORD-1"))
            val pos = openPosition(takeProfit = null)

            manager.attachProtectionOrders(pos)

            assertEquals("ORD-2", pos.slOrderId)
        }

    @Test
    fun `attach does not block on sent row without order id`(): Unit =
        runBlocking {
            stubPlaceOrder("ORD-2")
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = null))
            val pos = openPosition(takeProfit = null)

            manager.attachProtectionOrders(pos)

            assertEquals("ORD-2", pos.slOrderId)
        }

    // ─── onProtectionLevelsChanged ──────────────────────────────────

    @Test
    fun `tighter stop loss schedules replacement`(): Unit =
        runBlocking {
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(null)
            val pos =
                openPosition(
                    stopLoss = "91800",
                    takeProfit = null,
                    slOrderId = "SL-1",
                    slOrderPrice = "91500",
                )

            manager.onProtectionLevelsChanged(pos)

            assertTrue(pos.slPendingReplace)
            verify(positionRepo).save(pos)
        }

    @Test
    fun `take profit change schedules replacement`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    stopLoss = null,
                    takeProfit = "93500",
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                )

            manager.onProtectionLevelsChanged(pos)

            assertTrue(pos.tpPendingReplace)
            verify(positionRepo).save(pos)
        }

    @Test
    fun `unchanged levels do not schedule replacement`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    slOrderId = "SL-1",
                    slOrderPrice = "91500",
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                )

            manager.onProtectionLevelsChanged(pos)

            assertFalse(pos.slPendingReplace)
            assertFalse(pos.tpPendingReplace)
            verify(positionRepo, never()).save(anyPosition())
        }

    @Test
    fun `levels changed on closed position are ignored`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    status = PositionStatus.CLOSED,
                    stopLoss = "91800",
                    slOrderId = "SL-1",
                    slOrderPrice = "91500",
                )

            manager.onProtectionLevelsChanged(pos)

            assertFalse(pos.slPendingReplace)
            verify(positionRepo, never()).save(anyPosition())
        }

    // ─── reconcile: orphan protection ───────────────────────────────

    @Test
    fun `orphan protection on non-open position is cancelled via outbox`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    status = PositionStatus.CLOSED,
                    slOrderId = "SL-1",
                    tpOrderId = "TP-1",
                )

            manager.reconcileProtectionOrders(pos)

            assertTrue(pos.slCancelPending)
            assertTrue(pos.tpCancelPending)
            verify(orderOutboxService).placeCancelOrder(1L, "SL-1", null)
            verify(orderOutboxService).placeCancelOrder(1L, "TP-1", null)
        }

    @Test
    fun `closed position without protection orders is untouched`(): Unit =
        runBlocking {
            val pos = openPosition(status = PositionStatus.CLOSED)

            manager.reconcileProtectionOrders(pos)

            verify(positionRepo, never()).save(anyPosition())
            verify(orderOutboxService, never()).placeCancelOrder(anyOrNull(), anyOrNull(), anyOrNull())
        }

    // ─── reconcile: SL outbox resolution ────────────────────────────

    @Test
    fun `sent SL row is adopted as armed protection`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-1"))
            val pos = openPosition(takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals("SL-1", pos.slOrderId)
            assertEquals(0, BigDecimal("91500").compareTo(pos.slOrderPrice!!))
        }

    @Test
    fun `cancelled SL row is not re-armed`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-1"))
            whenever(orderOutboxRepo.findLatestConfirmedCancel(1L, "SL-1"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-1"))
            val pos = openPosition(takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertNull(pos.slOrderId)
        }

    @Test
    fun `permanently failed SL row escalates to protection failure`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.FAILED, retryCount = 3))
            val pos = openPosition(takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(pos.ticker, protectionFailed.get()?.ticker)
        }

    @Test
    fun `blocked SL row escalates to protection failure`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.BLOCKED))
            val pos = openPosition(takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(pos.ticker, protectionFailed.get()?.ticker)
        }

    @Test
    fun `rejected SL row escalates to protection failure`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.REJECTED))
            val pos = openPosition(takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(pos.ticker, protectionFailed.get()?.ticker)
        }

    // ─── reconcile: TP outbox resolution ────────────────────────────

    @Test
    fun `sent TP row is adopted as armed protection`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "TP-1"))
            val pos = openPosition(stopLoss = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals("TP-1", pos.tpOrderId)
            assertEquals(0, BigDecimal("93000").compareTo(pos.tpOrderPrice!!))
        }

    @Test
    fun `cancelled TP row is not re-armed`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "TP-1"))
            whenever(orderOutboxRepo.findLatestConfirmedCancel(1L, "TP-1"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "TP-1"))
            val pos = openPosition(stopLoss = null)

            manager.reconcileProtectionOrders(pos)

            assertNull(pos.tpOrderId)
        }

    @Test
    fun `permanently failed TP row escalates to protection failure`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.FAILED, retryCount = 3))
            val pos = openPosition(stopLoss = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(pos.ticker, protectionFailed.get()?.ticker)
        }

    @Test
    fun `blocked TP row escalates to protection failure`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.BLOCKED))
            val pos = openPosition(stopLoss = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(pos.ticker, protectionFailed.get()?.ticker)
        }

    @Test
    fun `rejected TP row escalates to protection failure`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.REJECTED))
            val pos = openPosition(stopLoss = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(pos.ticker, protectionFailed.get()?.ticker)
        }

    // ─── reconcile: замены заявок ───────────────────────────────────

    @Test
    fun `SL replacement without live order clears pending flag`(): Unit =
        runBlocking {
            val pos = openPosition(slPendingReplace = true, slOrderId = null)

            manager.reconcileProtectionOrders(pos)

            assertFalse(pos.slPendingReplace)
        }

    @Test
    fun `SL replacement cancel exception keeps old order for retry`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("exchange timeout"))
            val pos =
                openPosition(
                    stopLoss = "91500",
                    takeProfit = null,
                    slOrderId = "SL-1",
                    slOrderPrice = "91500",
                    slPendingReplace = true,
                )

            manager.reconcileProtectionOrders(pos)

            assertTrue(pos.slPendingReplace)
            assertEquals("SL-1", pos.slOrderId)
        }

    @Test
    fun `SL replacement cancel rejected but old order filled applies protection close`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            stubVerifyOrderSequence(
                AlorClient.OrderExecution("WORKING", 0, null),
                AlorClient.OrderExecution("FILLED", 10, BigDecimal("91400")),
            )
            val pos =
                openPosition(
                    takeProfit = null,
                    slOrderId = "SL-1",
                    slOrderPrice = "91500",
                    slPendingReplace = true,
                )

            manager.reconcileProtectionOrders(pos)

            assertEquals(CloseReason.STOP_LOSS, appliedClose.get()?.third)
            assertEquals(10, appliedClose.get()?.first)
            assertNull(pos.slOrderId)
        }

    @Test
    fun `SL replacement cancel rejected but old order gone clears state`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            stubVerifyOrderSequence(
                AlorClient.OrderExecution("WORKING", 0, null),
                AlorClient.OrderExecution("CANCELED", 0, null),
            )
            stubPlaceOrder("SL-2")
            val pos =
                openPosition(
                    takeProfit = null,
                    slOrderId = "SL-1",
                    slOrderPrice = "91500",
                    slPendingReplace = true,
                )

            manager.reconcileProtectionOrders(pos)

            // перевыставление завершено, защита поставлена заново
            assertEquals("SL-2", pos.slOrderId)
            assertFalse(pos.slPendingReplace)
        }

    @Test
    fun `SL replacement cancel rejected with unknown state retries next cycle`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(null)
            val pos =
                openPosition(
                    takeProfit = null,
                    slOrderId = "SL-1",
                    slOrderPrice = "91500",
                    slPendingReplace = true,
                )

            manager.reconcileProtectionOrders(pos)

            assertTrue(pos.slPendingReplace)
            assertEquals("SL-1", pos.slOrderId)
        }

    @Test
    fun `TP replacement without live order clears pending flag`(): Unit =
        runBlocking {
            val pos = openPosition(tpPendingReplace = true, tpOrderId = null)

            manager.reconcileProtectionOrders(pos)

            assertFalse(pos.tpPendingReplace)
        }

    @Test
    fun `TP replacement cancel exception keeps old order for retry`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("exchange timeout"))
            val pos =
                openPosition(
                    stopLoss = null,
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                    tpPendingReplace = true,
                )

            manager.reconcileProtectionOrders(pos)

            assertTrue(pos.tpPendingReplace)
            assertEquals("TP-1", pos.tpOrderId)
        }

    @Test
    fun `TP replacement cancel rejected but old order filled applies take profit`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            stubVerifyOrderSequence(
                AlorClient.OrderExecution("WORKING", 0, null),
                AlorClient.OrderExecution("FILLED", 10, BigDecimal("93100")),
            )
            val pos =
                openPosition(
                    stopLoss = null,
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                    tpPendingReplace = true,
                )

            manager.reconcileProtectionOrders(pos)

            assertEquals(CloseReason.TAKE_PROFIT, appliedClose.get()?.third)
            assertNull(pos.tpOrderId)
        }

    @Test
    fun `TP replacement cancel rejected but old order gone clears state`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            stubVerifyOrderSequence(
                AlorClient.OrderExecution("WORKING", 0, null),
                AlorClient.OrderExecution("EXPIRED", 0, null),
            )
            stubPlaceOrder("TP-2")
            val pos =
                openPosition(
                    stopLoss = null,
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                    tpPendingReplace = true,
                )

            manager.reconcileProtectionOrders(pos)

            assertEquals("TP-2", pos.tpOrderId)
            assertFalse(pos.tpPendingReplace)
        }

    @Test
    fun `TP replacement confirmed replaces old order and resets fill counter`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.CONFIRMED)
            stubVerifyOrderSequence(AlorClient.OrderExecution("WORKING", 0, null))
            stubPlaceOrder("TP-2")
            val pos =
                openPosition(
                    stopLoss = null,
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                    tpPendingReplace = true,
                )
            pos.cumulativeTpFillQty = 3

            manager.reconcileProtectionOrders(pos)

            // старый TP отменён, новый выставлен, счётчик дельты старого ордера сброшен
            assertEquals("TP-2", pos.tpOrderId)
            assertEquals(0, pos.cumulativeTpFillQty)
            assertFalse(pos.tpPendingReplace)
            assertFalse(pos.tpCancelPending)
        }

    @Test
    fun `TP replacement uncertain cancel keeps old order`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.UNCERTAIN)
            val pos =
                openPosition(
                    stopLoss = null,
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                    tpPendingReplace = true,
                )

            manager.reconcileProtectionOrders(pos)

            assertTrue(pos.tpPendingReplace)
            assertEquals("TP-1", pos.tpOrderId)
        }

    // ─── исполнение защитных заявок ─────────────────────────────────

    @Test
    fun `partial take profit fill re-arms protection for the remainder`(): Unit =
        runBlocking {
            stubPlaceOrder("TP-2")
            val pos =
                openPosition(
                    stopLoss = null,
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                )

            manager.applyExchangeProtectionClosePublic(
                pos,
                AlorClient.OrderExecution("FILLED", 4, BigDecimal("92900")),
                CloseReason.TAKE_PROFIT,
            )

            assertEquals(CloseReason.TAKE_PROFIT, appliedClose.get()?.third)
            assertEquals(4, appliedClose.get()?.first)
            assertEquals(6, pos.quantity)
            assertFalse(pos.tpPendingReplace)
            // защита переставлена на остаток; новый ордер — счётчик дельты сброшен
            assertEquals("TP-2", pos.tpOrderId)
            assertEquals(0, pos.cumulativeTpFillQty)
        }

    @Test
    fun `duplicate protection fill does not close twice`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    stopLoss = null,
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                )
            pos.cumulativeTpFillQty = 4

            manager.applyExchangeProtectionClosePublic(
                pos,
                AlorClient.OrderExecution("FILLED", 4, BigDecimal("92900")),
                CloseReason.TAKE_PROFIT,
            )

            assertNull(appliedClose.get())
            assertEquals(10, pos.quantity)
        }

    @Test
    fun `protection fill with other reason counts full fill as delta`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    stopLoss = null,
                    takeProfit = null,
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                )

            manager.applyExchangeProtectionClosePublic(
                pos,
                AlorClient.OrderExecution("FILLED", 10, BigDecimal("93000")),
                CloseReason.FORCE_CLOSE,
            )

            assertEquals(CloseReason.FORCE_CLOSE, appliedClose.get()?.third)
            assertEquals(10, appliedClose.get()?.first)
            // для «прочих» причин id защитных заявок не трогаются
            assertEquals("TP-1", pos.tpOrderId)
        }

    @Test
    fun `protection fill cancels pending close and defers re-arm`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    stopLoss = null,
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                    pendingClose = true,
                    closeOrderId = "CLOSE-1",
                    quantity = 10,
                )

            manager.applyExchangeProtectionClosePublic(
                pos,
                AlorClient.OrderExecution("FILLED", 4, BigDecimal("92900")),
                CloseReason.TAKE_PROFIT,
            )

            assertTrue(pos.closeCancelPending)
            verify(orderOutboxService).placeCancelOrder(1L, "CLOSE-1", null)
            // пока отмена close не подтверждена, защита не переставляется
            verify(orderOutboxService, never()).placeOrder(
                anyOrNull(),
                anyOrNull(),
                Mockito.anyInt(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                eq("tp"),
                anyOrNull(),
            )
        }
}
