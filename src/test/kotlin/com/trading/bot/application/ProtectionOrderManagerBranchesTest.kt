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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Ветви [ProtectionOrderManager], не покрытые lifecycle/cancel-pending тестами
 * (скан-19: 44 из 266): пропуски по флагам (`closeCancelPending`, отсутствие уровня,
 * отсутствие id), неподтверждённая постановка SL/TP, частичные и дублирующие филлы
 * защиты, синтез «FILLED без количества», ветви отказа/неизвестности отмены при
 * перевыставлении, не-терминальный статус `verifyOrder` и причины закрытия вне SL/TP.
 */
class ProtectionOrderManagerBranchesTest {
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
        slCancelPending: Boolean = false,
        tpCancelPending: Boolean = false,
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
            slCancelPending = slCancelPending,
            tpCancelPending = tpCancelPending,
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

    private suspend fun stubVerifyOrderSequence(vararg executions: AlorClient.OrderExecution) {
        val calls = AtomicInteger(0)
        whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            val index = calls.getAndIncrement().coerceAtMost(executions.size - 1)
            executions[index]
        }
    }

    private fun execution(
        status: String,
        filled: Int,
        price: String? = "91600",
    ): AlorClient.OrderExecution = AlorClient.OrderExecution(status, filled, price?.toBigDecimal())

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

    // ─── attach: пропуски и неподтверждённая постановка ─────────────────

    @Test
    fun `attach skips a non open position`(): Unit =
        runBlocking {
            val pos = openPosition(status = PositionStatus.CLOSED)

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
    fun `cancel without a position id is a no-op`(): Unit =
        runBlocking {
            val pos = openPosition(id = null, slOrderId = "SL-1", tpOrderId = "TP-1")

            manager.cancelProtectionOrders(pos)

            verify(orderOutboxService, never()).placeCancelOrder(anyOrNull(), anyOrNull(), anyOrNull())
            assertFalse(pos.slCancelPending)
            assertFalse(pos.tpCancelPending)
        }

    @Test
    fun `reconcile without a position id leaves protection untouched`(): Unit =
        runBlocking {
            val pos = openPosition(id = null)

            manager.reconcileProtectionOrders(pos)

            // resolveProtectionOutbox выходит до обращения к outbox; финальный save — штатный.
            verify(orderOutboxRepo, never()).findLatestByPositionId(anyOrNull(), anyOrNull())
        }

    @Test
    fun `sent row without an order id does not suppress re-placement`(): Unit =
        runBlocking {
            stubPlaceOrder("SL-NEW")
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = null))
            val pos = openPosition(takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals("SL-NEW", pos.slOrderId)
            assertNull(protectionFailed.get())
        }

    @Test
    fun `take profit outbox failure below the retry limit does not fail protection`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.FAILED, retryCount = 1))
            val pos = openPosition(stopLoss = null)

            manager.reconcileProtectionOrders(pos)

            assertNull(protectionFailed.get())
        }

    @Test
    fun `partial protection fill without a close order id does not schedule a cancel`(): Unit =
        runBlocking {
            val pos = openPosition(slOrderId = "SL-1", stopLoss = "91500", pendingClose = true)

            manager.applyExchangeProtectionClosePublic(pos, execution("PARTIALLY_FILLED", 3), CloseReason.STOP_LOSS)

            assertFalse(pos.closeCancelPending)
            verify(orderOutboxService, never()).placeCancelOrder(anyOrNull(), anyOrNull(), anyOrNull())
        }

    @Test
    fun `attach skips while a close cancellation is pending`(): Unit =
        runBlocking {
            val pos = openPosition(closeCancelPending = true)

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
            assertNull(pos.slOrderId)
        }

    @Test
    fun `unconfirmed SL placement does not mutate the position`(): Unit =
        runBlocking {
            stubPlaceOrder(null)
            val pos = openPosition(takeProfit = null)

            manager.attachProtectionOrders(pos)

            assertNull(pos.slOrderId, "неподтверждённая заявка не должна фиксировать id")
            assertNull(pos.slOrderPrice)
            verify(positionRepo, never()).save(anyPosition())
        }

    @Test
    fun `position without stop loss only re-arms take profit`(): Unit =
        runBlocking {
            stubPlaceOrder("TP-1")
            val pos = openPosition(stopLoss = null)

            manager.attachProtectionOrders(pos)

            assertNull(pos.slOrderId)
            assertEquals("TP-1", pos.tpOrderId)
        }

    @Test
    fun `pending outbox row suppresses duplicate placement`(): Unit =
        runBlocking {
            stubPlaceOrder("SL-1")
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.PENDING))
            val pos = openPosition(takeProfit = null)

            manager.attachProtectionOrders(pos)

            assertNull(pos.slOrderId, "заявка ещё в outbox — дубль не нужен")
        }

    @Test
    fun `failed outbox row within retries suppresses placement`(): Unit =
        runBlocking {
            stubPlaceOrder("SL-1")
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.FAILED, retryCount = 1))
            val pos = openPosition(takeProfit = null)

            manager.attachProtectionOrders(pos)

            assertNull(pos.slOrderId)
        }

    @Test
    fun `sent outbox row with confirmed cancel does not suppress placement`(): Unit =
        runBlocking {
            stubPlaceOrder("SL-2")
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-OLD"))
            whenever(orderOutboxRepo.findLatestConfirmedCancel(1L, "SL-OLD"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-OLD"))
            val pos = openPosition(takeProfit = null)

            manager.attachProtectionOrders(pos)

            assertEquals("SL-2", pos.slOrderId, "старая заявка отменена — можно ставить новую")
        }

    // ─── onProtectionLevelsChanged ──────────────────────────────────────

    @Test
    fun `level change on a non open position is ignored`(): Unit =
        runBlocking {
            val pos = openPosition(status = PositionStatus.CLOSED, slOrderId = "SL-1", slOrderPrice = "91500")

            manager.onProtectionLevelsChanged(pos)

            assertFalse(pos.slPendingReplace)
            verify(positionRepo, never()).save(anyPosition())
        }

    @Test
    fun `changed stop schedules replacement once and skips flags`(): Unit =
        runBlocking {
            stubPlaceOrder(null)
            val pos = openPosition(slOrderId = "SL-1", slOrderPrice = "90000", stopLoss = "91500")

            manager.onProtectionLevelsChanged(pos)
            assertTrue(pos.slPendingReplace)

            pos.slPendingReplace = false
            pos.slCancelPending = true
            manager.onProtectionLevelsChanged(pos)
            assertFalse(pos.slPendingReplace, "при живой отмене замена не планируется")

            pos.slCancelPending = false
            pos.slOrderPrice = BigDecimal("91500")
            manager.onProtectionLevelsChanged(pos)
            assertFalse(pos.slPendingReplace, "уровень не изменился — замены нет")
        }

    @Test
    fun `changed take profit schedules replacement`(): Unit =
        runBlocking {
            stubPlaceOrder(null)
            val pos = openPosition(tpOrderId = "TP-1", tpOrderPrice = "92000", takeProfit = "93000")

            manager.onProtectionLevelsChanged(pos)

            assertTrue(pos.tpPendingReplace)
        }

    // ─── resolveProtectionOutbox ────────────────────────────────────────

    @Test
    fun `cancelled outbox row is not re-armed`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-1"))
            whenever(orderOutboxRepo.findLatestConfirmedCancel(1L, "SL-1"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-1"))
            val pos = openPosition()

            manager.reconcileProtectionOrders(pos)

            assertNull(pos.slOrderId, "отменённая заявка не должна воскресать")
        }

    @Test
    fun `failed outbox row below the retry limit does not fail protection`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.FAILED, retryCount = 1))
            val pos = openPosition()

            manager.reconcileProtectionOrders(pos)

            assertNull(protectionFailed.get(), "пока есть попытки — защита не считается потерянной")
        }

    @Test
    fun `rejected take profit outbox row triggers protection failure`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.REJECTED))
            val pos = openPosition()

            manager.reconcileProtectionOrders(pos)

            assertNotNull(protectionFailed.get(), "REJECTED TP = защита не выставлена")
        }

    // ─── checkProtectionFills ──────────────────────────────────────────

    @Test
    fun `unknown order state leaves protection untouched`(): Unit =
        runBlocking {
            stubVerifyOrderSequence(execution("ACTIVE", 0))
            val pos = openPosition(slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals("SL-1", pos.slOrderId)
            assertNull(appliedClose.get(), "живая заявка ничего не закрывает")
        }

    @Test
    fun `filled status without quantity closes the whole position`(): Unit =
        runBlocking {
            // Alor иногда не отдаёт filledQty при FILLED — синтезируем остаток.
            stubVerifyOrderSequence(execution("FILLED", 0))
            val pos = openPosition(slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            val applied = appliedClose.get()
            assertNotNull(applied, "FILLED без количества должен примениться")
            assertEquals(10, applied!!.first)
            assertEquals(CloseReason.STOP_LOSS, applied.third)
        }

    @Test
    fun `partial fill on a cancelled stop applies the filled part`(): Unit =
        runBlocking {
            stubVerifyOrderSequence(execution("CANCELED", 4))
            val pos = openPosition(slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(4, appliedClose.get()!!.first, "частичный филл терминальной заявки не теряется")
            assertEquals("SL-1", pos.slOrderId, "остаток ещё жив — id сохраняется")
            assertTrue(pos.slCancelPending, "остаток нужно снять перед перевыставлением")
        }

    // ─── applyExchangeProtectionClose (через публичный вход) ────────────

    @Test
    fun `duplicate protection fill is ignored`(): Unit =
        runBlocking {
            val pos = openPosition(slOrderId = "SL-1", stopLoss = "91500")
            pos.cumulativeSlFillQty = 5

            manager.applyExchangeProtectionClosePublic(pos, execution("FILLED", 5), CloseReason.STOP_LOSS)

            assertNull(appliedClose.get(), "повторный (out-of-order) филл не применяется")
        }

    @Test
    fun `partial protection fill keeps the id and cancels the remainder`(): Unit =
        runBlocking {
            val pos = openPosition(slOrderId = "SL-1", stopLoss = "91500", quantity = 10)

            manager.applyExchangeProtectionClosePublic(pos, execution("PARTIALLY_FILLED", 3), CloseReason.STOP_LOSS)

            assertEquals(3, appliedClose.get()!!.first)
            assertEquals("SL-1", pos.slOrderId, "старая заявка ещё жива — id не теряем")
            assertTrue(pos.slCancelPending)
            assertEquals(3, pos.cumulativeSlFillQty)
        }

    @Test
    fun `protection close with a foreign reason ignores the cumulative counters`(): Unit =
        runBlocking {
            val pos = openPosition(slOrderId = null, stopLoss = "91500", quantity = 10)

            manager.applyExchangeProtectionClosePublic(pos, execution("FILLED", 4), CloseReason.STRATEGY_CLOSE)

            assertEquals(4, appliedClose.get()!!.first)
            assertEquals(0, pos.cumulativeSlFillQty, "счётчик SL не трогается чужой причиной")
            assertEquals(0, pos.cumulativeTpFillQty)
        }

    @Test
    fun `already cancelling close order is not cancelled twice`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    slOrderId = "SL-1",
                    stopLoss = "91500",
                    pendingClose = true,
                    closeCancelPending = true,
                    closeOrderId = "CLOSE-1",
                )

            manager.applyExchangeProtectionClosePublic(pos, execution("FILLED", 10), CloseReason.STOP_LOSS)

            verify(orderOutboxService, never()).placeCancelOrder(anyOrNull(), anyOrNull(), anyOrNull())
            assertTrue(pos.closeCancelPending)
        }

    // ─── finishProtectionReplacement ───────────────────────────────────

    @Test
    fun `replacement without a stored order id just clears the flag`(): Unit =
        runBlocking {
            val pos = openPosition(slPendingReplace = true, slOrderId = null, takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertFalse(pos.slPendingReplace)
        }

    @Test
    fun `replacement cancel throwing keeps the pending flag`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("exchange down"))
            val pos = openPosition(slPendingReplace = true, slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertTrue(pos.slPendingReplace, "неизвестный результат отмены — повтор в следующем цикле")
            assertEquals("SL-1", pos.slOrderId)
        }

    @Test
    fun `uncertain cancel keeps the replacement pending`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.UNCERTAIN)
            val pos = openPosition(slPendingReplace = true, slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertTrue(pos.slPendingReplace)
            assertEquals("SL-1", pos.slOrderId)
        }

    @Test
    fun `rejected cancel on an already filled stop applies the close`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            stubVerifyOrderSequence(execution("FILLED", 10))
            val pos = openPosition(slPendingReplace = true, slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(CloseReason.STOP_LOSS, appliedClose.get()!!.third, "отмена отклонена — заявка уже исполнилась")
        }

    @Test
    fun `rejected cancel on a dead stop re-places the protection`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            stubVerifyOrderSequence(execution("CANCELED", 0))
            stubPlaceOrder("SL-NEW")
            val pos = openPosition(slPendingReplace = true, slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals("SL-NEW", pos.slOrderId, "старая заявка мертва — выставляем новую")
            assertFalse(pos.slPendingReplace)
        }

    @Test
    fun `rejected cancel with an unknown state retries next cycle`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            stubVerifyOrderSequence(execution("ACTIVE", 0))
            val pos = openPosition(slPendingReplace = true, slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertTrue(pos.slPendingReplace)
            assertEquals("SL-1", pos.slOrderId)
        }

    @Test
    fun `confirmed cancel clears the old order and its fill counter`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.CONFIRMED)
            stubPlaceOrder("SL-NEW")
            val pos = openPosition(slPendingReplace = true, slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)
            pos.cumulativeSlFillQty = 3

            manager.reconcileProtectionOrders(pos)

            assertEquals(0, pos.cumulativeSlFillQty, "новой заявке — новый счётчик филов")
            assertEquals("SL-NEW", pos.slOrderId)
        }

    @Test
    fun `take profit replacement without an old id clears the flag`(): Unit =
        runBlocking {
            val pos = openPosition(tpPendingReplace = true, tpOrderId = null, stopLoss = null, takeProfit = "93000")

            manager.reconcileProtectionOrders(pos)

            assertFalse(pos.tpPendingReplace)
        }

    @Test
    fun `sent outbox rows resolve ids for both protection legs`(): Unit =
        runBlocking {
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-9"))
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "TP-9"))
            val pos = openPosition()

            manager.reconcileProtectionOrders(pos)

            assertEquals("SL-9", pos.slOrderId)
            assertEquals("TP-9", pos.tpOrderId)
            runBlocking { verify(positionRepo, Mockito.atLeastOnce()).save(pos) }
        }

    @Test
    fun `blocked and permanently failed take profit trigger protection failure`(): Unit =
        runBlocking {
            for ((status, retry) in listOf(OutboxStatus.BLOCKED to 0, OutboxStatus.FAILED to 3)) {
                protectionFailed.set(null)
                whenever(orderOutboxRepo.findLatestByPositionId(1L, "tp"))
                    .thenReturn(outbox(status, retryCount = retry))
                val pos = openPosition()

                manager.reconcileProtectionOrders(pos)

                assertNotNull(protectionFailed.get(), "TP $status = защита не выставлена")
            }
        }

    @Test
    fun `filled take profit closes the position with the take profit reason`(): Unit =
        runBlocking {
            stubVerifyOrderSequence(execution("FILLED", 10))
            val pos = openPosition(tpOrderId = "TP-1", tpOrderPrice = "92000", stopLoss = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(CloseReason.TAKE_PROFIT, appliedClose.get()?.third)
            assertEquals(10, appliedClose.get()?.first)
        }

    @Test
    fun `partial take profit fill keeps the id and cancels the remainder`(): Unit =
        runBlocking {
            stubVerifyOrderSequence(execution("CANCELED", 4))
            val pos = openPosition(tpOrderId = "TP-1", tpOrderPrice = "92000", stopLoss = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals(4, appliedClose.get()!!.first)
            assertEquals("TP-1", pos.tpOrderId, "частичный фил — id сохраняется до подтверждения отмены")
            assertTrue(pos.tpCancelPending)
        }

    @Test
    fun `cancelled take profit without an extra fill re-arms the order`(): Unit =
        runBlocking {
            stubVerifyOrderSequence(execution("CANCELED", 0))
            val pos = openPosition(tpOrderId = "TP-1", tpOrderPrice = "92000", stopLoss = null)

            manager.reconcileProtectionOrders(pos)

            assertNull(appliedClose.get(), "терминальный статус без филла не закрывает позицию")
            assertNull(pos.tpOrderId)
            assertNull(pos.tpOrderPrice)
        }

    @Test
    fun `full protection fill with a live close order escalates to reconciliation`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    slOrderId = "SL-1",
                    stopLoss = "91500",
                    pendingClose = true,
                    closeOrderId = "CLOSE-9",
                )

            manager.applyExchangeProtectionClosePublic(pos, execution("FILLED", 10), CloseReason.STOP_LOSS)

            assertEquals(PositionStatus.RECONCILIATION_REQUIRED, pos.status)
            assertFalse(pos.pendingClose)
            assertFalse(pos.pendingEntry)
            runBlocking { verify(positionRepo).save(pos) }
        }

    @Test
    fun `protection fill without avg or current price falls back to the entry price`(): Unit =
        runBlocking {
            val pos = openPosition(slOrderId = "SL-1", stopLoss = "91500").copy(currentPrice = null)

            manager.applyExchangeProtectionClosePublic(pos, execution("FILLED", 10, price = null), CloseReason.STOP_LOSS)

            assertEquals(
                0,
                BigDecimal("91600").compareTo(appliedClose.get()!!.second),
                "close price falls back to the entry price",
            )
        }

    @Test
    fun `partial protection fill cancels the live close order before closing`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    slOrderId = "SL-1",
                    stopLoss = "91500",
                    pendingClose = true,
                    closeOrderId = "CLOSE-9",
                )

            manager.applyExchangeProtectionClosePublic(pos, execution("FILLED", 3), CloseReason.STOP_LOSS)

            assertEquals(3, appliedClose.get()!!.first)
            assertTrue(pos.closeCancelPending)
            runBlocking { verify(orderOutboxService).placeCancelOrder(1L, "CLOSE-9", accountId = null) }
        }

    @Test
    fun `reconcile a closed position without protection ids returns immediately`(): Unit =
        runBlocking {
            val pos = openPosition(status = PositionStatus.CLOSED, stopLoss = null, takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            verify(orderOutboxRepo, never()).findLatestByPositionId(anyOrNull(), anyOrNull())
            verify(positionRepo, never()).save(anyPosition())
        }

    @Test
    fun `cancelled SENT row does not count as an active protection order`(): Unit =
        runBlocking {
            stubPlaceOrder("SL-NEW")
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "sl"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-OLD"))
            whenever(orderOutboxRepo.findLatestConfirmedCancel(1L, "SL-OLD"))
                .thenReturn(outbox(OutboxStatus.SENT, orderId = "SL-OLD"))
            val pos = openPosition(takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals("SL-NEW", pos.slOrderId, "отменённая заявка не блокирует новую защиту")
        }

    @Test
    fun `close cancel is not scheduled twice while it is already pending`(): Unit =
        runBlocking {
            val pos =
                openPosition(
                    slOrderId = "SL-1",
                    stopLoss = "91500",
                    pendingClose = true,
                    closeCancelPending = true,
                    closeOrderId = "CLOSE-9",
                )

            manager.applyExchangeProtectionClosePublic(pos, execution("PARTIALLY_FILLED", 3), CloseReason.STOP_LOSS)

            assertTrue(pos.closeCancelPending)
            assertEquals(3, appliedClose.get()!!.first)
        }

    @Test
    fun `attach skips legs whose replacement is already pending`(): Unit =
        runBlocking {
            val pos = openPosition(slPendingReplace = true, tpPendingReplace = true)

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
            assertNull(pos.slOrderId)
            assertNull(pos.tpOrderId)
        }

    @Test
    fun `attach places only the missing leg when SL already exists`(): Unit =
        runBlocking {
            stubPlaceOrder("TP-NEW")
            val pos = openPosition(slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = "93000")

            manager.attachProtectionOrders(pos)

            assertEquals("SL-1", pos.slOrderId)
            assertEquals("TP-NEW", pos.tpOrderId)
        }

    @Test
    fun `cancel honours skip and per-leg pending flags`(): Unit =
        runBlocking {
            val skipSl = openPosition(slOrderId = "SL-1", tpOrderId = "TP-1")
            manager.cancelProtectionOrders(skipSl, skip = "SL")
            runBlocking {
                verify(orderOutboxService).placeCancelOrder(1L, "TP-1", accountId = null)
            }
            assertFalse(skipSl.slCancelPending)
            assertTrue(skipSl.tpCancelPending)

            Mockito
                .clearInvocations(orderOutboxService)
            val pending = openPosition(slOrderId = "SL-1", tpOrderId = "TP-1", slCancelPending = true, tpCancelPending = true)
            manager.cancelProtectionOrders(pending, skip = "TP")
            verify(orderOutboxService, never()).placeCancelOrder(anyOrNull(), anyOrNull(), anyOrNull())
        }

    @Test
    fun `level changes without a stored price do not schedule replacement`(): Unit =
        runBlocking {
            val pos = openPosition(slOrderId = "SL-1", slOrderPrice = null, tpOrderId = "TP-1", tpOrderPrice = null)

            manager.onProtectionLevelsChanged(pos)

            assertFalse(pos.slPendingReplace)
            assertFalse(pos.tpPendingReplace)
        }

    @Test
    fun `orphan protection of a closed position is cancelled and saved`(): Unit =
        runBlocking {
            val pos = openPosition(status = PositionStatus.CLOSED, slOrderId = "SL-1", stopLoss = "91500")

            manager.reconcileProtectionOrders(pos)

            assertTrue(pos.slCancelPending)
            runBlocking {
                verify(orderOutboxService).placeCancelOrder(1L, "SL-1", accountId = null)
                verify(positionRepo).save(pos)
            }
        }

    @Test
    fun `pending SL replacement without an order id clears the flag`(): Unit =
        runBlocking {
            val pos = openPosition(slPendingReplace = true, stopLoss = null, takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertFalse(pos.slPendingReplace)
            runBlocking { verify(positionRepo, Mockito.atLeastOnce()).save(pos) }
        }

    @Test
    fun `pending TP replacement without an order id clears the flag`(): Unit =
        runBlocking {
            val pos = openPosition(tpPendingReplace = true, stopLoss = null, takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertFalse(pos.tpPendingReplace)
            runBlocking { verify(positionRepo, Mockito.atLeastOnce()).save(pos) }
        }

    @Test
    fun `TP replacement rejection with a filled old order applies take profit`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            stubVerifyOrderSequence(execution("FILLED", 10))
            val pos =
                openPosition(
                    stopLoss = null,
                    takeProfit = "94000",
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                    tpPendingReplace = true,
                )

            manager.reconcileProtectionOrders(pos)

            assertEquals(CloseReason.TAKE_PROFIT, appliedClose.get()!!.third)
        }

    @Test
    fun `confirmed TP replacement clears the id and resets the fill counter`(): Unit =
        runBlocking {
            whenever(alorClient.cancelOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.CancelResult.CONFIRMED)
            stubPlaceOrder("TP-NEW")
            val pos =
                openPosition(
                    stopLoss = null,
                    takeProfit = "94000",
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                    tpPendingReplace = true,
                )
            pos.cumulativeTpFillQty = 2

            manager.reconcileProtectionOrders(pos)

            assertEquals("TP-NEW", pos.tpOrderId, "старый TP снят, новый выставлен")
            assertEquals(0, pos.cumulativeTpFillQty)
        }

    @Test
    fun `gone SL with a rejected status is cleared and re-placed`(): Unit =
        runBlocking {
            stubVerifyOrderSequence(execution("REJECTED", 0))
            stubPlaceOrder("SL-NEW")
            val pos = openPosition(slOrderId = "SL-1", slOrderPrice = "91500", takeProfit = null)

            manager.reconcileProtectionOrders(pos)

            assertEquals("SL-NEW", pos.slOrderId, "терминальный SL снимается и выставляется заново")
            assertEquals(0, BigDecimal("91500").compareTo(pos.slOrderPrice))
        }

    @Test
    fun `expired TP is cleared and re-placed`(): Unit =
        runBlocking {
            stubVerifyOrderSequence(execution("EXPIRED", 0))
            stubPlaceOrder("TP-NEW")
            val pos =
                openPosition(
                    stopLoss = null,
                    slOrderId = null,
                    takeProfit = "94000",
                    tpOrderId = "TP-1",
                    tpOrderPrice = "93000",
                )

            manager.reconcileProtectionOrders(pos)

            assertEquals("TP-NEW", pos.tpOrderId, "истёкший TP снимается и выставляется заново")
        }
}
