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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delta-модель close fills (EXEC-008): WS Alor присылает кумулятивный
 * filledQtyBatch. Только инкремент (дельта) с момента последнего применения
 * уменьшает позицию. Дублирующие WS events НЕ должны закрывать позицию повторно.
 *
 * Ключевые инварианты:
 * - delta = cumulativeFilledQty - cumulativeCloseFillQty;
 * - delta <= 0 → skip (дубликат или out-of-order event);
 * - delta > 0 && delta < quantity → partial close (quantity -= delta, pendingClose=false);
 * - delta >= quantity → full finalize (status= CLOSED).
 */
class OrderExecutionEngineDeltaModelTest {
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
        quantity: Int = 5,
        pendingClose: Boolean = true,
        closeOrderId: String = "close-1",
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

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return openPos()
    }

    private fun anyString(): String {
        Mockito.any(String::class.java)
        return ""
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
                ),
            ).thenReturn(true)
        }
    }

    private fun stubFindCloseOrderId(pos: Position) {
        runBlocking {
            Mockito.`when`(positionRepo.findByCloseOrderId(Mockito.anyString())).thenReturn(pos)
            Mockito.`when`(positionRepo.findByAlorOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.findBySlOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.findByTpOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.findById(Mockito.anyLong())).thenReturn(pos)
        }
    }

    private fun report(
        orderId: String = "close-1",
        cumulativeFilledQty: Int,
        avgPrice: BigDecimal = BigDecimal("110"),
    ) =
        ExecutionReport(
            orderId = orderId,
            status = OrderStatus.FILLED,
            cumulativeFilledQty = cumulativeFilledQty,
            avgPrice = avgPrice,
            ticker = "SBER",
            side = "sell",
        )

    // ─── Core delta model tests ──────────────────────────────────────

    @Test
    fun `duplicate WS event with same cumulative does not double-close`() {
        val pos = openPos(quantity = 5)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        // First event: cumulative=3, delta=3, partial close (3/5) → quantity=2
        runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 3)) }
        assertEquals(2, pos.quantity)
        assertEquals(3, pos.cumulativeCloseFillQty)

        // Duplicate event: cumulative=3, delta=3-3=0, skip
        runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 3)) }
        assertEquals(2, pos.quantity)
        assertEquals(3, pos.cumulativeCloseFillQty)
    }

    @Test
    fun `full fill in single event finalizes position`() {
        val pos = openPos(quantity = 5)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()

        // cumulative=5, prevApplied=0, delta=5 >= quantity=5 → finalize
        runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 5)) }
        assertEquals(5, pos.cumulativeCloseFillQty)
        assertEquals(1, closedPositions.size)
        assertEquals(PositionStatus.CLOSED, closedPositions.first().status)
    }

    @Test
    fun `partial fill reduces quantity and clears pendingClose`() {
        val pos = openPos(quantity = 10)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        // cumulative=4, delta=4, 4 < 10 → partial close
        runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 4)) }
        assertEquals(6, pos.quantity)
        assertEquals(4, pos.cumulativeCloseFillQty)
        assertEquals(0, BigDecimal("40").compareTo(pos.realizedPnl))
        assertFalse(pos.pendingClose)
    }

    @Test
    fun `event with cumulative less than already applied is ignored`() {
        val pos = openPos(quantity = 5, cumulativeCloseFillQty = 3)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        // Out-of-order: cumulative=1, already applied 3, delta=-2, skip
        runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 1)) }
        assertEquals(5, pos.quantity)
        assertEquals(3, pos.cumulativeCloseFillQty)
    }

    @Test
    fun `event with cumulative equal to applied is skipped`() {
        val pos = openPos(quantity = 5, cumulativeCloseFillQty = 5)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        // Already fully applied, delta=0, skip
        val handled = runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 5)) }
        assertTrue(handled)
        assertEquals(5, pos.quantity)
        assertEquals(0, closedPositions.size)
    }

    // ─── Restart/reconnect scenario ──────────────────────────────────

    @Test
    fun `reconnect with existing cumulativeCloseFillQty applies only new delta`() {
        // Simulates restart: position reloaded from DB with cumulativeCloseFillQty=3
        val pos = openPos(quantity = 2, cumulativeCloseFillQty = 3)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()

        // New event after restart: cumulative=5, delta=5-3=2 >= quantity=2 → finalize
        runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 5)) }
        assertEquals(5, pos.cumulativeCloseFillQty)
        assertEquals(1, closedPositions.size)
        assertEquals(PositionStatus.CLOSED, closedPositions.first().status)
    }

    @Test
    fun `restart with cumulativeCloseFillQty at full amount skips old event`() {
        // After restart, position already fully closed: cumulativeCloseFillQty=5, quantity=5
        val pos = openPos(quantity = 5, cumulativeCloseFillQty = 5)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        // Stale event arrives: cumulative=5, delta=0, skip
        val handled = runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 5)) }
        assertTrue(handled)
        assertEquals(5, pos.quantity)
        assertEquals(0, closedPositions.size)
    }

    // ─── Cumulative reset on new close order ─────────────────────────

    @Test
    fun `new close order with fresh cumulative applies full delta from zero`() {
        val pos = openPos(quantity = 3, cumulativeCloseFillQty = 0)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()

        runBlocking {
            engine.handleExecutionReport(
                report(orderId = "close-2", cumulativeFilledQty = 3),
            )
        }
        assertEquals(3, pos.cumulativeCloseFillQty)
        assertEquals(1, closedPositions.size)
    }

    // ─── REST path delta model ───────────────────────────────────────

    @Test
    fun `REST confirmCloseFill uses delta model`() {
        val pos = openPos(quantity = 5, cumulativeCloseFillQty = 2)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()
        runBlocking {
            Mockito.`when`(
                alorClient.verifyOrder(anyString(), anyBigDecimal(), anyString()),
            ).thenReturn(
                AlorClient.OrderExecution(status = "FILLED", filledQuantity = 5, avgPrice = BigDecimal("110")),
            )
        }

        // REST: cumulative=5, prevApplied=2, delta=3, 3 < 5 → partial close
        runBlocking { engine.reconcilePosition(pos) }
        assertEquals(5, pos.cumulativeCloseFillQty)
        assertEquals(2, pos.quantity) // 5 - 3 = 2
        assertEquals(0, BigDecimal("30").compareTo(pos.realizedPnl))
        assertFalse(pos.pendingClose)
    }

    @Test
    fun `REST confirmCloseFill cumulative equal to applied skips`() {
        val pos = openPos(quantity = 2, cumulativeCloseFillQty = 5)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        runBlocking {
            Mockito.`when`(
                alorClient.verifyOrder(anyString(), anyBigDecimal(), anyString()),
            ).thenReturn(
                AlorClient.OrderExecution(status = "FILLED", filledQuantity = 5, avgPrice = BigDecimal("110")),
            )
        }

        // REST: cumulative=5, prevApplied=5, delta=0, skip
        runBlocking { engine.reconcilePosition(pos) }
        assertEquals(2, pos.quantity)
        assertEquals(0, closedPositions.size)
    }

    // ─── Concurrency: WS + REST race ────────────────────────────────

    /**
     * The critical race: WS and REST verifyOrder both read cumulativeCloseFillQty=0
     * concurrently. Without the mutex, both would compute delta=cumulative and apply
     * duplicate partial closes (doubled P&L, wrong quantity). With the mutex, the
     * second coroutine re-reads from DB under lock and gets delta=0 (skip).
     *
     * Scenario:
     *   DB: qty=10, cumulativeCloseFillQty=0, pendingClose=true
     *   Coroutine A (WS): cumulative=4, delta=4, partial close → qty=6
     *   Coroutine B (REST): cumulative=4, but re-reads cumulative=4 (already applied) → delta=0 → skip
     */
    @Test
    fun `concurrent WS and REST close fills do not double-close`() {
        val pos = openPos(quantity = 10, cumulativeCloseFillQty = 0)

        // findById returns the same mutable object — simulates DB returning updated state
        runBlocking {
            Mockito.`when`(positionRepo.findById(Mockito.anyLong())).thenAnswer { pos }
            Mockito.`when`(positionRepo.findByCloseOrderId(Mockito.anyString())).thenReturn(pos)
            Mockito.`when`(positionRepo.findByAlorOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.findBySlOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.findByTpOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.save(anyPosition())).thenAnswer { it.getArgument<Position>(0) }
            Mockito.`when`(
                alorClient.verifyOrder(anyString(), anyBigDecimal(), anyString()),
            ).thenReturn(
                AlorClient.OrderExecution(status = "FILLED", filledQuantity = 4, avgPrice = BigDecimal("110")),
            )
        }

        // Both coroutines see cumulative=4 from REST/WS
        // Mutex ensures second re-reads and sees cumulative already advanced
        val latch = CountDownLatch(1)
        runBlocking {
            val ws = async {
                latch.await()
                engine.handleExecutionReport(report(cumulativeFilledQty = 4))
            }
            val rest = async {
                latch.await()
                // REST path: reconcilePosition → confirmCloseFill
                engine.reconcilePosition(pos)
            }
            latch.countDown()
            awaitAll(ws, rest)
        }

        // Cumulative should be 4 (applied once), quantity should be 10-4=6
        assertEquals(4, pos.cumulativeCloseFillQty)
        assertEquals(6, pos.quantity)
        // Partial close applied only once
        assertFalse(pos.pendingClose)
    }

    /**
     * Two concurrent WS events with the same cumulative value.
     * Mutex ensures only one applies the delta.
     */
    @Test
    fun `concurrent duplicate WS events apply delta only once`() {
        val pos = openPos(quantity = 5, cumulativeCloseFillQty = 0)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        val latch = CountDownLatch(1)
        runBlocking {
            val a = async {
                latch.await()
                engine.handleExecutionReport(report(cumulativeFilledQty = 3))
            }
            val b = async {
                latch.await()
                engine.handleExecutionReport(report(cumulativeFilledQty = 3))
            }
            latch.countDown()
            awaitAll(a, b)
        }

        // delta=3 applied once; quantity=5-3=2
        assertEquals(3, pos.cumulativeCloseFillQty)
        assertEquals(2, pos.quantity)
    }

    /**
     * Concurrent WS events with DIFFERENT cumulative values (partial then full fill).
     * Mutex ensures the second coroutine re-reads from DB and sees the first coroutine's
     * partial close (pendingClose=false, closeOrderId=null) — skips because position
     * is no longer pendingClose. No duplicate delta applied.
     *
     * This matches real architecture: partial close clears pendingClose, the remainder
     * is re-closed by a fresh close order on the next cycle.
     */
    @Test
    fun `concurrent WS events with different cumulative values are serialized correctly`() {
        val pos = openPos(quantity = 5, cumulativeCloseFillQty = 0)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()

        val latch = CountDownLatch(1)
        runBlocking {
            val a = async {
                latch.await()
                engine.handleExecutionReport(report(cumulativeFilledQty = 3))
            }
            val b = async {
                latch.await()
                engine.handleExecutionReport(report(cumulativeFilledQty = 5))
            }
            latch.countDown()
            awaitAll(a, b)
        }

        // First event applied delta=3 → qty=2, pendingClose=false, cumul=3
        // Second event re-reads: pendingClose=false → skip (no duplicate)
        assertEquals(3, pos.cumulativeCloseFillQty)
        assertEquals(2, pos.quantity)
        assertFalse(pos.pendingClose)
        assertEquals(0, closedPositions.size)
    }
}
