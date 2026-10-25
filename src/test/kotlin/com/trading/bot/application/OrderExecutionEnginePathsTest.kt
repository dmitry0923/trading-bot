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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * [OrderExecutionEngine] — краевые ветки оркестратора исполнения:
 *
 * - отказ входа с неположительным количеством;
 * - `FILLED` без `filledQuantity` (API не вернул поле) НЕ должен создавать фантом;
 * - компенсация исключения до/после записи outbox (E1);
 * - close-claim: передача в reconcile/confirm без второго ордера;
 * - SHORT-закрытие через buy-сторону;
 * - WS-подтверждение входа (pendingEntry → открытая позиция).
 */
class OrderExecutionEnginePathsTest {
    private val alorClient = mock<AlorClient>()
    private val orderOutboxService = mock<OrderOutboxService>()
    private val orderOutboxRepo = mock<OrderOutboxRepository>()
    private val positionRepo = mock<PositionRepository>()
    private val tradeEventService = mock<TradeEventService>()
    private val meterRegistry = SimpleMeterRegistry()
    private val alorConfig = AlorConfig().apply { maxOrderRetries = 3 }
    private val opened = AtomicInteger(0)
    private val closed = AtomicInteger(0)
    private val linkedOutbox = AtomicInteger(0)

    private val engine =
        OrderExecutionEngine(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = alorConfig,
            objectMapper = jacksonObjectMapper(),
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            pnlCalculator = PnlCalculator.plain(),
            instrumentFilter = { true },
            metricPrefix = "test.engine",
            onEntryOpened = { opened.incrementAndGet() },
            onPositionClosed = { closed.incrementAndGet() },
            protectionOrdersEnabled = false,
            portfolioResolver = { "D12345" },
        )

    @BeforeEach
    fun setUp() {
        runBlocking {
            whenever(positionRepo.save(anyPosition())).thenAnswer { it.getArgument<Position>(0) }
            whenever(positionRepo.reserveEntry(anyOrNull(), anyDirection(), anyOrNull())).thenReturn(11L)
            whenever(orderOutboxService.linkPosition(anyOrNull(), Mockito.anyLong())).thenAnswer {
                linkedOutbox.incrementAndGet()
            }
        }
        Mockito.clearInvocations(positionRepo, orderOutboxService, alorClient)
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return openPosition()
    }

    private fun anyDirection(): PositionDirection {
        Mockito.any(PositionDirection::class.java)
        return PositionDirection.LONG
    }

    private fun openPosition(direction: PositionDirection = PositionDirection.LONG): Position =
        Position(
            id = 1L,
            ticker = "SBER",
            direction = direction,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
        )

    private fun buildPosition(
        orderId: String?,
        pending: Boolean,
        fillPrice: BigDecimal,
        qty: Int,
    ): Position =
        Position(
            id = 5L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = qty,
            entryPrice = fillPrice,
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            alorOrderId = orderId,
            pendingEntry = pending,
        )

    private fun placed(
        orderId: String?,
        success: Boolean = true,
        uncertain: Boolean = false,
    ): OrderOutboxService.PlaceOrderResult =
        OrderOutboxService.PlaceOrderResult(
            outboxId = UUID.randomUUID(),
            alorOrderId = orderId,
            success = success,
            uncertain = uncertain,
        )

    // ─── вход ────────────────────────────────────────────────────────

    @Test
    fun `entry with non-positive quantity is rejected before reservation`(): Unit =
        runBlocking {
            val pos =
                engine.placeEntryOrder(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    qty = 0,
                    entryPrice = BigDecimal("100"),
                    buildPosition = ::buildPosition,
                )

            assertNull(pos)
            verify(positionRepo, never()).reserveEntry(anyOrNull(), anyDirection(), anyOrNull())
            assertEquals(1.0, meterRegistry.counter("test.engine.entry.rejected", "ticker", "SBER", "reason", "INVALID_QTY").count())
        }

    /**
     * `FILLED` без `filledQuantity` (поле не пришло) — доверяем статусу и открываем
     * позицию на полный объём. Раньше 0 проваливался в ветку «полный филл» и создавал
     * фантомную позицию с SL/TP, которых нет на бирже.
     */
    @Test
    fun `filled status without filled quantity opens full position`(): Unit =
        runBlocking {
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
            ).thenReturn(placed("ORDER-1"))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("FILLED", 0, BigDecimal("100.4")))

            val pos =
                engine.placeEntryOrder(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    qty = 10,
                    entryPrice = BigDecimal("100"),
                    buildPosition = ::buildPosition,
                )

            assertEquals(10, pos?.quantity)
            assertEquals(0, BigDecimal("100.4").compareTo(pos!!.entryPrice))
            assertFalse(pos.pendingEntry)
            assertEquals(1, opened.get())
        }

    @Test
    fun `entry exception after outbox commit keeps pending entry for reconciliation`(): Unit =
        runBlocking {
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
            ).thenReturn(placed("ORDER-1"))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("rest timeout"))

            val pos =
                engine.placeEntryOrder(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    qty = 10,
                    entryPrice = BigDecimal("100"),
                    buildPosition = ::buildPosition,
                )

            assertNull(pos)
            val captor = org.mockito.kotlin.argumentCaptor<Position>()
            verify(positionRepo).save(captor.capture())
            assertTrue(captor.firstValue.pendingEntry, "ордер мог уйти на биржу — нужен pendingEntry")
            assertEquals(1, linkedOutbox.get())
            verify(positionRepo, never()).releaseEntry(anyOrNull(), anyOrNull())
            assertEquals(
                1.0,
                meterRegistry.counter("test.engine.entry.compensated", "ticker", "SBER", "stage", "after_outbox").count(),
            )
        }

    @Test
    fun `entry exception before outbox commit releases reservation`(): Unit =
        runBlocking {
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
            ).thenThrow(IllegalStateException("serialization failed"))

            val pos =
                engine.placeEntryOrder(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    qty = 10,
                    entryPrice = BigDecimal("100"),
                    buildPosition = ::buildPosition,
                )

            assertNull(pos)
            verify(positionRepo).releaseEntry("SBER", null)
            assertEquals(
                1.0,
                meterRegistry.counter("test.engine.entry.compensated", "ticker", "SBER", "stage", "before_outbox").count(),
            )
        }

    @Test
    fun `outbox link failure does not prevent entry`(): Unit =
        runBlocking {
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
            ).thenReturn(placed("ORDER-1"))
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("FILLED", 10, BigDecimal("100")))
            whenever(orderOutboxService.linkPosition(anyOrNull(), Mockito.anyLong()))
                .thenThrow(IllegalStateException("db down"))

            val pos =
                engine.placeEntryOrder(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    qty = 10,
                    entryPrice = BigDecimal("100"),
                    buildPosition = ::buildPosition,
                )

            assertFalse(pos!!.pendingEntry)
            assertEquals(1, opened.get())
        }

    @Test
    fun `entry slot release failure is contained`(): Unit =
        runBlocking {
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
            ).thenThrow(IllegalStateException("outbox down"))
            whenever(positionRepo.releaseEntry(anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("db down"))

            val pos =
                engine.placeEntryOrder(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    qty = 10,
                    entryPrice = BigDecimal("100"),
                    buildPosition = ::buildPosition,
                )

            assertNull(pos)
            verify(positionRepo).releaseEntry("SBER", null)
        }

    @Test
    fun `protection level change is delegated to manager`(): Unit =
        runBlocking {
            engine.onProtectionLevelsChanged(openPosition())
        }

    // ─── закрытие ────────────────────────────────────────────────────

    @Test
    fun `close without id is rejected`(): Unit =
        runBlocking {
            engine.closePosition(openPosition().copy(id = null), BigDecimal("100"), CloseReason.STOP_LOSS)

            verify(positionRepo, never()).claimForClose(Mockito.anyLong())
        }

    @Test
    fun `short position closes via buy side`(): Unit =
        runBlocking {
            val pos = openPosition(direction = PositionDirection.SHORT)
            whenever(positionRepo.claimForClose(1L)).thenReturn(true)
            whenever(positionRepo.findById(1L)).thenReturn(pos)
            whenever(alorClient.getPositions(anyOrNull())).thenReturn(AlorClient.ReconcileResult.Failed)
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
            ).thenReturn(placed("CLOSE-1"))

            engine.closePosition(pos, BigDecimal("100"), CloseReason.STOP_LOSS)

            verify(orderOutboxService).placeOrder(
                eq("SBER"),
                eq("buy"),
                eq(10),
                anyOrNull(),
                eq("market"),
                eq(1L),
                eq(CloseReason.STOP_LOSS.code),
                anyOrNull(),
                eq("close"),
                anyOrNull(),
            )
            assertEquals("CLOSE-1", pos.closeOrderId)
            assertTrue(pos.pendingClose)
        }

    @Test
    fun `lost close claim delegates to outbox reconciliation`(): Unit =
        runBlocking {
            val pos = openPosition().copy(pendingClose = true, closeOrderId = null)
            whenever(positionRepo.claimForClose(1L)).thenReturn(false)
            whenever(positionRepo.findById(1L)).thenReturn(pos)
            whenever(orderOutboxRepo.findLatestByPositionId(1L, "close")).thenReturn(null)

            engine.closePosition(pos, BigDecimal("100"), CloseReason.STOP_LOSS)

            // реконсилятор сбросил pendingClose: новая заявка будет создана следующим тиком
            assertFalse(pos.pendingClose)
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
    fun `lost close claim confirms existing close order instead of duplicating`(): Unit =
        runBlocking {
            val pos = openPosition().copy(pendingClose = true, closeOrderId = "CLOSE-1")
            whenever(positionRepo.claimForClose(1L)).thenReturn(false)
            whenever(positionRepo.findById(1L)).thenReturn(pos)
            whenever(alorClient.verifyOrder(anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(AlorClient.OrderExecution("FILLED", 10, BigDecimal("110")))
            whenever(
                positionRepo.transitionToClosed(
                    Mockito.anyLong(),
                    anyStatus(),
                    anyBigDecimal(),
                    anyReason(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                ),
            ).thenReturn(true)

            engine.closePosition(pos, BigDecimal("110"), CloseReason.STOP_LOSS)

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
            assertEquals(PositionStatus.CLOSED, pos.status)
            assertEquals(1, closed.get())
        }

    @Test
    fun `lost close claim waits when cancel is already pending`(): Unit =
        runBlocking {
            val pos =
                openPosition().copy(
                    pendingClose = true,
                    closeOrderId = "CLOSE-1",
                    closeCancelPending = true,
                )
            whenever(positionRepo.claimForClose(1L)).thenReturn(false)
            whenever(positionRepo.findById(1L)).thenReturn(pos)

            engine.closePosition(pos, BigDecimal("100"), CloseReason.STOP_LOSS)

            verify(alorClient, never()).verifyOrder(anyOrNull(), anyOrNull(), anyOrNull())
            assertTrue(pos.pendingClose)
        }

    // ─── WS dispatch ─────────────────────────────────────────────────

    @Test
    fun `ws entry fill opens pending entry position`(): Unit =
        runBlocking {
            val pos = openPosition().copy(pendingEntry = true, closeOrderId = null)
            whenever(positionRepo.findByAlorOrderId("ENTRY-1")).thenReturn(pos)

            val handled =
                engine.handleExecutionReport(
                    ExecutionReport(
                        orderId = "ENTRY-1",
                        status = OrderStatus.FILLED,
                        cumulativeFilledQty = 10,
                        avgPrice = BigDecimal("100.7"),
                        ticker = "SBER",
                        side = "buy",
                    ),
                )

            assertTrue(handled)
            assertFalse(pos.pendingEntry)
            assertEquals("ENTRY-1", pos.alorOrderId)
            assertEquals(10, pos.quantity)
            assertEquals(0, BigDecimal("100.7").compareTo(pos.entryPrice))
            assertEquals(1, opened.get())
            verify(tradeEventService).recordPositionOpened(pos)
        }

    @Test
    fun `ws partial entry fill keeps position pending`(): Unit =
        runBlocking {
            val pos = openPosition().copy(pendingEntry = true)
            whenever(positionRepo.findByAlorOrderId("ENTRY-1")).thenReturn(pos)

            val handled =
                engine.handleExecutionReport(
                    ExecutionReport(
                        orderId = "ENTRY-1",
                        status = OrderStatus.PARTIALLY_FILLED,
                        cumulativeFilledQty = 4,
                        avgPrice = BigDecimal("100.7"),
                        ticker = "SBER",
                        side = "buy",
                    ),
                )

            // частичный вход обрабатывается реконсилятором, а не WS-путём
            assertTrue(handled)
            assertTrue(pos.pendingEntry)
            assertEquals(0, opened.get())
        }

    @Test
    fun `ws report for unknown order is not handled`(): Unit =
        runBlocking {
            val handled =
                engine.handleExecutionReport(
                    ExecutionReport(
                        orderId = "UNKNOWN",
                        status = OrderStatus.FILLED,
                        cumulativeFilledQty = 1,
                        avgPrice = BigDecimal("100"),
                        ticker = "SBER",
                        side = "buy",
                    ),
                )

            assertFalse(handled)
        }

    // ─── matcher-хелперы ─────────────────────────────────────────────

    private fun anyStatus(): PositionStatus {
        Mockito.any(PositionStatus::class.java)
        return PositionStatus.CLOSED
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyReason(): CloseReason {
        Mockito.any(CloseReason::class.java)
        return CloseReason.STOP_LOSS
    }
}
