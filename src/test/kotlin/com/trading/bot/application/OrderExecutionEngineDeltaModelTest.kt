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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito

import tools.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * Delta-модель close fills (EXEC-008): WS Alor присылает кумулятивный
 * filledQtyBatch. Только инкремент (дельта) с момента последнего применения
 * уменьшает позицию. Дублирующие WS events НЕ должны закрывать позицию повторно.
 *
 * Ключевые инварианты:
 * - delta = cumulativeFilledQty - cumulativeCloseFillQty;
 * - delta <= 0 → skip (дубликат или out-of-order event);
 * - delta > 0 && delta < quantity → partial close (quantity -= delta, pendingClose=false, closeOrderId kept);
 * - delta >= quantity → full finalize (status=CLOSED);
 * - после partial close: closeOrderId и pendingClose СОХРАНЯЮТСЯ —
 *   последующие fill'ы для того же close-ордера находят позицию через findByCloseOrderId.
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
        closeOrderId: String? = "close-1",
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

    private fun stubCancelOrderConfirmed() {
        runBlocking {
            Mockito.`when`(
                alorClient.cancelOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()),
            ).thenReturn(AlorClient.CancelResult.CONFIRMED)
        }
    }

    private fun stubCancelOrderUncertain() {
        runBlocking {
            Mockito.`when`(
                alorClient.cancelOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()),
            ).thenReturn(AlorClient.CancelResult.UNCERTAIN)
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
    fun `partial fill reduces quantity and keeps closeOrderId while clearing pendingClose`() {
        val pos = openPos(quantity = 10)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        // cumulative=4, delta=4, 4 < 10 → partial close
        runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 4)) }
        assertEquals(6, pos.quantity)
        assertEquals(4, pos.cumulativeCloseFillQty)
        assertEquals(0, BigDecimal("40").compareTo(pos.realizedPnl))
        // closeOrderId сохраняется — последующие fill'ы для того же close-ордера
        // должны находить позицию через findByCloseOrderId.
        // pendingClose=false — позиция готова к перезакрытию.
        assertFalse(pos.pendingClose)
        assertEquals("close-1", pos.closeOrderId)
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
        val pos = openPos(quantity = 3, cumulativeCloseFillQty = 0, closeOrderId = "close-2")
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
        // pendingClose cleared, closeOrderId preserved after partial fill
        assertFalse(pos.pendingClose)
        assertEquals("close-1", pos.closeOrderId)
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
        // Partial close applied only once — closeOrderId preserved, pendingClose cleared
        assertFalse(pos.pendingClose)
        assertEquals("close-1", pos.closeOrderId)
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
     * First event via handleExecutionReport (pendingClose=true) applies delta=3.
     * Second event via handleCloseFill (fallback path, since pendingClose=false after partial)
     * applies remaining delta=2 → finalize → CLOSED.
     *
     * In production: TradingBotService.onExecutionReport calls handleExecutionReport first;
     * if it returns false (pendingClose=false), handleRegularStockFill delegates to handleCloseFill.
     */
    @Test
    fun `concurrent WS events with different cumulative values finalize correctly`() {
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
                // Simulates production path: handleExecutionReport returns false (pendingClose=false),
                // TradingBotService delegates to handleCloseFill.
                engine.handleCloseFill(pos, report(cumulativeFilledQty = 5))
            }
            latch.countDown()
            awaitAll(a, b)
        }

        assertEquals(5, pos.cumulativeCloseFillQty)
        assertEquals(PositionStatus.CLOSED, pos.status)
        assertEquals(1, closedPositions.size)
    }

    // ─── Durable protection replacement (crash-consistency) ─────────

    /**
     * After partial close, slPendingReplace/tpPendingReplace flags are set
     * instead of calling cancelProtectionOrders + attachProtectionOrders directly.
     * This ensures that if a crash occurs after save, reconciliation will
     * detect the flags and handle cancel+replace on restart.
     *
     * Old SL/TP orders remain active on the exchange (over-protected for the
     * larger pre-close quantity) until reconciliation replaces them.
     */
    @Test
    fun `partial close sets PendingReplace flags for durable protection replacement`() {
        val engineWithProtection =
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
                protectionOrdersEnabled = true,
                portfolioResolver = { "D12345" },
            )

        val pos =
            openPos(quantity = 5).apply {
                slOrderId = "sl-1"
                slOrderPrice = BigDecimal("90")
                tpOrderId = "tp-1"
                tpOrderPrice = BigDecimal("110")
            }
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        // cumulative=3, delta=3, 3 < 5 → partial close
        runBlocking { engineWithProtection.handleExecutionReport(report(cumulativeFilledQty = 3)) }

        assertEquals(2, pos.quantity)
        assertEquals(3, pos.cumulativeCloseFillQty)
        assertTrue(pos.slPendingReplace) { "slPendingReplace should be set for durable replacement" }
        assertTrue(pos.tpPendingReplace) { "tpPendingReplace should be set for durable replacement" }
        // Old IDs preserved — reconciliation will cancel them
        assertEquals("sl-1", pos.slOrderId)
        assertEquals("tp-1", pos.tpOrderId)
        // No new protection orders placed directly — deferred to reconciliation
        runBlocking {
            Mockito.verify(orderOutboxService, Mockito.never()).placeOrder(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.nullable(BigDecimal::class.java),
                Mockito.anyString(),
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.nullable(BigDecimal::class.java),
                Mockito.nullable(String::class.java),
                Mockito.nullable(Long::class.java),
            )
        }
    }

    /**
     * Full close finalizes the position — PendingReplace flags are not set
     * because the position transitions to CLOSED and no protection is needed.
     */
    @Test
    fun `full close does not set PendingReplace flags`() {
        val engineWithProtection =
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
                protectionOrdersEnabled = true,
                portfolioResolver = { "D12345" },
            )

        val pos =
            openPos(quantity = 5).apply {
                slOrderId = "sl-1"
                tpOrderId = "tp-1"
            }
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()

        // cumulative=5, delta=5 >= quantity=5 → finalize
        runBlocking { engineWithProtection.handleExecutionReport(report(cumulativeFilledQty = 5)) }

        assertEquals(PositionStatus.CLOSED, pos.status)
        // Flags should NOT be set — position is closed, no replacement needed
        assertFalse(pos.slPendingReplace)
        assertFalse(pos.tpPendingReplace)
    }

    /**
     * Crash scenario: partial close saves PendingReplace flags, but process crashes
     * before reconciliation runs. On restart, reconciliation sees the flags and
     * handles replacement. This test verifies the flags are persisted (saved) atomically.
     */
    @Test
    fun `partial close saves PendingReplace flags atomically with quantity and P and L`() {
        val engineWithProtection =
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
                protectionOrdersEnabled = true,
                portfolioResolver = { "D12345" },
            )

        var savedPosition: Position? = null
        runBlocking {
            Mockito.`when`(positionRepo.save(anyPosition())).thenAnswer { inv ->
                val arg = inv.getArgument<Position>(0)
                savedPosition = arg
                arg
            }
        }

        val pos =
            openPos(quantity = 10).apply {
                slOrderId = "sl-1"
                tpOrderId = "tp-1"
            }
        stubFindCloseOrderId(pos)

        // cumulative=4, delta=4, 4 < 10 → partial close
        runBlocking { engineWithProtection.handleExecutionReport(report(cumulativeFilledQty = 4)) }

        // All changes saved in one atomic save
        val saved = savedPosition!!
        assertEquals(6, saved.quantity)
        assertEquals(4, saved.cumulativeCloseFillQty)
        assertTrue(saved.slPendingReplace) { "flags must be saved atomically with quantity" }
        assertTrue(saved.tpPendingReplace)
        // closeOrderId сохраняется дляsequent WS fills, pendingClose=false для перезакрытия
        assertFalse(saved.pendingClose)
        assertEquals("close-1", saved.closeOrderId)
    }

    // ─── Concurrency: partial close + SL/TP execution ───────────────

    /**
     * Race between WS close fill (partial) and SL fill on the same position.
     *
     * Scenario:
     *   DB: qty=10, pendingClose=true, slOrderId="sl-1", tpOrderId="tp-1"
     *   Coroutine A (WS): close order fills 4 → partial close → qty=6, slPendingReplace=true,
     *     pendingClose=false, closeOrderId stays "close-1"
     *   Coroutine B (WS): SL order fills → applyExchangeProtectionClose → close remainder
     *
     * Both go through the per-position mutex. The second coroutine re-reads from DB
     * and sees the updated state from the first. If the first already applied the
     * partial close, the second sees the updated quantity (6) and applies SL fill on remainder.
     *
     * Without the mutex, both could read qty=10 and apply independently,
     * resulting in double-close or incorrect quantity.
     */
    @Test
    fun `concurrent partial close and SL fill are serialized by mutex`() {
        val engineWithProtection =
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
                protectionOrdersEnabled = true,
                portfolioResolver = { "D12345" },
            )

        val pos =
            openPos(quantity = 10).apply {
                slOrderId = "sl-1"
                slOrderPrice = BigDecimal("90")
                tpOrderId = "tp-1"
                tpOrderPrice = BigDecimal("110")
            }

        val savedPositions = mutableListOf<Position>()
        runBlocking {
            Mockito.`when`(positionRepo.findById(Mockito.anyLong())).thenAnswer { pos }
            Mockito.`when`(positionRepo.findByCloseOrderId(Mockito.anyString())).thenReturn(pos)
            Mockito.`when`(positionRepo.findByAlorOrderId(Mockito.anyString())).thenReturn(null)
            Mockito.`when`(positionRepo.findBySlOrderId("sl-1")).thenReturn(pos)
            Mockito.`when`(positionRepo.findByTpOrderId("tp-1")).thenReturn(pos)
            Mockito.`when`(positionRepo.save(anyPosition())).thenAnswer {
                val arg = it.getArgument<Position>(0)
                savedPositions.add(arg)
                arg
            }
            stubTransitionToClosed()
        }

        runBlocking {
            Mockito.`when`(
                orderOutboxService.placeCancelOrder(Mockito.anyLong(), Mockito.anyString(), Mockito.any()),
            ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, true))
        }

        val latch = CountDownLatch(1)
        runBlocking {
            val closeFill = async {
                latch.await()
                // WS close fill: cumulative=4, delta=4, 4 < 10 → partial close
                engineWithProtection.handleExecutionReport(
                    report(orderId = "close-1", cumulativeFilledQty = 4),
                )
            }
            val slFill = async {
                latch.await()
                // SL fills the remaining position on the exchange.
                // The SL path goes through findBySlOrderId → applyExchangeProtectionClose
                // (NOT verifyOrder), so no Mockito.when needed here.
                // cumulativeFilledQty=20 (oversized) so it always covers remaining qty
                // regardless of whether closeFill ran first (qty=6) or not (qty=10).
                engineWithProtection.handleExecutionReport(
                    report(orderId = "sl-1", cumulativeFilledQty = 20, avgPrice = BigDecimal("89")),
                )
            }
            latch.countDown()
            awaitAll(closeFill, slFill)
        }

        // Both applied via mutex. The final position should be closed.
        // If closeFill ran first: SL closes the remainder → CLOSED.
        // If SL ran first: SL closes position, closeFill skips (pendingClose=false) → CLOSED.
        // Either way, position is fully closed and no exception was thrown.
        val finalState = savedPositions.last()
        assertTrue(
            finalState.status == PositionStatus.CLOSED ||
                finalState.status == PositionStatus.TAKE_PROFIT,
        ) { "Position should be fully closed after both fills: status=${finalState.status}" }
    }

    // ─── Multi-step partial fill: 100 → 37 → 100 → duplicate 100 ──

    /**
     * CRITICAL: multi-step partial close scenario from production audit.
     *
     * 100 лотов → 37 исполнено → позиция уменьшается до 63,
     * затем handleCloseFill (fallback path) → delta=63 → финализация → CLOSED,
     * затем дубликат → delta=0 → skip.
     *
     * P&L: 37 @ (110-100)=370, 63 @ (110-100)=630, total = 1000 (plain calculator).
     * P&L считается по частям (partial fill P&L + finalize remainder P&L), а не
     * по всему qty одной ценой.
     *
     * Flow:
     * 1. First WS fill → handleExecutionReport (pendingClose=true) → delta model
     * 2. Second fill → handleExecutionReport returns false (pendingClose=false),
     *    then TradingBotService calls engine.handleCloseFill (fallback path)
     * 3. Duplicate → handleCloseFill → delta=0 → skip
     */
    @Test
    fun `multi-step partial fill 100 then 100 then duplicate correctly closes`() {
        val pos = openPos(quantity = 100)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()

        // Step 1: WS fill via handleExecutionReport — cumulative=37, delta=37, partial close
        runBlocking { engine.handleExecutionReport(report(cumulativeFilledQty = 37)) }
        assertEquals(63, pos.quantity)
        assertEquals(37, pos.cumulativeCloseFillQty)
        assertEquals(0, BigDecimal("370").compareTo(pos.realizedPnl))
        assertFalse(pos.pendingClose) { "pendingClose cleared after partial" }
        assertEquals("close-1", pos.closeOrderId) { "closeOrderId preserved for subsequent fills" }

        // Step 2: fallback path via handleCloseFill — cumulative=100, delta=63, finalize
        runBlocking { engine.handleCloseFill(pos, report(cumulativeFilledQty = 100)) }
        assertEquals(100, pos.cumulativeCloseFillQty)
        assertEquals(1, closedPositions.size)
        assertEquals(PositionStatus.CLOSED, pos.status)

        // Step 3: duplicate cumulative=100, delta=100-100=0 → skip (already closed)
        val sizeBefore = closedPositions.size
        runBlocking { engine.handleCloseFill(pos, report(cumulativeFilledQty = 100)) }
        assertEquals(sizeBefore, closedPositions.size) { "duplicate event does not re-close" }
    }

    /**
     * Multi-step partial fill with different prices — P&L is computed per-fill, not
     * for the entire position at the last price.
     *
     * 37 @ 110 → P&L=37×(110-100)=370
     * 63 @ 108 → P&L=63×(108-100)=504
     * total = 874
     */
    @Test
    fun `multi-step partial fill with different prices computes correct P and L`() {
        val pos = openPos(quantity = 100)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()

        // Step 1: 37 @ 110 via handleExecutionReport
        runBlocking {
            engine.handleExecutionReport(
                report(cumulativeFilledQty = 37, avgPrice = BigDecimal("110")),
            )
        }
        assertEquals(63, pos.quantity)
        assertEquals(0, BigDecimal("370").compareTo(pos.realizedPnl))

        // Step 2: 63 @ 108 via handleCloseFill (fallback) → finalize
        runBlocking {
            engine.handleCloseFill(
                pos,
                report(cumulativeFilledQty = 100, avgPrice = BigDecimal("108")),
            )
        }
        // total P&L = realizedPnl(370) + remainder(63×(108-100)=504) = 874
        assertEquals(1, closedPositions.size)
        val closed = closedPositions.first()
        assertEquals(PositionStatus.CLOSED, closed.status)
        assertEquals(0, BigDecimal("874").compareTo(closed.pnl))
    }

    // ─── handleCloseFill fallback path ─────────────────────────────

    /**
     * handleCloseFill fallback: pendingClose=false, closeOrderId set (e.g. after releaseCloseClaim).
     * Delta model correctly applies fills that handleExecutionReport would skip.
     */
    @Test
    fun `handleCloseFill fallback applies delta when pendingClose is false`() {
        val pos = openPos(quantity = 10, pendingClose = false, closeOrderId = "close-1")
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubTransitionToClosed()

        // handleCloseFill: cumulative=10, delta=10-0=10 >= 10 → finalize
        runBlocking {
            engine.handleCloseFill(
                pos,
                report(cumulativeFilledQty = 10),
            )
        }
        assertEquals(10, pos.cumulativeCloseFillQty)
        assertEquals(1, closedPositions.size)
        assertEquals(PositionStatus.CLOSED, pos.status)
    }

    /**
     * handleCloseFill: delta=0 → skip (idempotent).
     */
    @Test
    fun `handleCloseFill skips when delta is zero`() {
        val pos = openPos(quantity = 5, pendingClose = false, cumulativeCloseFillQty = 5)
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        val sizeBefore = closedPositions.size
        runBlocking {
            engine.handleCloseFill(
                pos,
                report(cumulativeFilledQty = 5),
            )
        }
        assertEquals(sizeBefore, closedPositions.size)
        assertEquals(5, pos.quantity) { "position unchanged" }
    }

    /**
     * handleCloseFill: position already closed → skip.
     */
    @Test
    fun `handleCloseFill skips closed position`() {
        val pos = openPos(quantity = 5, pendingClose = false, closeOrderId = "close-1")
        pos.status = PositionStatus.CLOSED
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        runBlocking {
            engine.handleCloseFill(
                pos,
                report(cumulativeFilledQty = 5),
            )
        }
        assertEquals(0, closedPositions.size)
    }

    /**
     * handleCloseFill: null report.avgPrice → skip.
     */
    @Test
    fun `handleCloseFill skips when avgPrice is null`() {
        val pos = openPos(quantity = 5, pendingClose = false, closeOrderId = "close-1")
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        runBlocking {
            engine.handleCloseFill(
                pos,
                ExecutionReport(
                    orderId = "close-1",
                    status = OrderStatus.FILLED,
                    cumulativeFilledQty = 5,
                    avgPrice = null,
                    ticker = "SBER",
                    side = "sell",
                ),
            )
        }
        assertEquals(5, pos.quantity) { "position unchanged when avgPrice is null" }
    }

    // ─── Stale close order cancellation (closePosition) ────────────────

    /**
     * CRITICAL: closePosition() cancels the old close order before creating a replacement.
     *
     * Scenario:
     * 1. Order A partially fills 40/100 → applyPartialClose → qty=60, cumulativeFillQty=40
     * 2. SL tick → closePosition() → prevCumulativeFill=40 → must cancel A before creating B
     *
     * Without the cancel, order A remains LIVE on the exchange and could fill
     * simultaneously with B → over-sell (40 + 60 + 60 = 160 lots sold for 60 remaining).
     */
    @Test
    fun `closePosition cancels stale close order before creating replacement`() {
        val pos = openPos(
            quantity = 6,
            pendingClose = false,
            closeOrderId = "stale-order-A",
            cumulativeCloseFillQty = 40,
        )

        runBlocking {
            Mockito.`when`(positionRepo.claimForClose(pos.id!!)).thenReturn(true)
            Mockito.`when`(positionRepo.findById(pos.id!!)).thenReturn(pos)
            stubSaveReturnsArg()
            stubTransitionToClosed()

            // New order placement succeeds
            Mockito.`when`(
                orderOutboxService.placeOrder(
                    ticker = Mockito.anyString(),
                    side = Mockito.anyString(),
                    qty = Mockito.anyInt(),
                    price = Mockito.nullable(BigDecimal::class.java),
                    type = Mockito.anyString(),
                    positionId = Mockito.nullable(Long::class.java),
                    closeReason = Mockito.nullable(String::class.java),
                    stopPrice = Mockito.nullable(BigDecimal::class.java),
                    purpose = Mockito.nullable(String::class.java),
                    accountId = Mockito.nullable(Long::class.java),
                ),
            ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), "stale-order-B", success = true))

            // verifyOrder returns FILLED for the new order B
            Mockito.`when`(
                alorClient.verifyOrder(Mockito.anyString(), anyBigDecimal(), Mockito.anyString()),
            ).thenReturn(AlorClient.OrderExecution("FILLED", 6, BigDecimal("110")))

            engine.closePosition(pos, BigDecimal("110"), CloseReason.STOP_LOSS)
        }

        // CRITICAL: stale order A must be cancelled via outbox
        runBlocking {
            verify(orderOutboxService).placeCancelOrder(pos.id!!, "stale-order-A", accountId = pos.accountId)
        }

        // New order B must be placed
        runBlocking {
            verify(orderOutboxService).placeOrder(
                ticker = Mockito.anyString(),
                side = Mockito.anyString(),
                qty = Mockito.anyInt(),
                price = Mockito.nullable(BigDecimal::class.java),
                type = Mockito.anyString(),
                positionId = Mockito.nullable(Long::class.java),
                closeReason = Mockito.nullable(String::class.java),
                stopPrice = Mockito.nullable(BigDecimal::class.java),
                purpose = Mockito.nullable(String::class.java),
                accountId = Mockito.nullable(Long::class.java),
            )
        }

        // closeOrderId cleared by finalizeClosePosition — position fully closed
        assertNull(pos.closeOrderId)
        assertEquals(PositionStatus.CLOSED, pos.status)
    }

    /**
     * Late WS fill for cancelled order A arrives after B is created — must be a no-op.
     *
     * After closePosition clears A and creates B, closeOrderId="B". A late
     * ExecutionReport for order A cannot find the position via findByCloseOrderId
     * and is silently dropped.
     */
    @Test
    fun `late WS fill for stale close order is silently dropped after replacement`() {
        val pos = openPos(
            quantity = 6,
            pendingClose = true,
            closeOrderId = "new-order-B",
            cumulativeCloseFillQty = 0,
        )

        val handled = runBlocking {
            // Late fill for old order A — position lookup fails because closeOrderId is now B
            Mockito.`when`(positionRepo.findByCloseOrderId("stale-order-A")).thenReturn(null)
            Mockito.`when`(positionRepo.findByAlorOrderId("stale-order-A")).thenReturn(null)
            Mockito.`when`(positionRepo.findBySlOrderId("stale-order-A")).thenReturn(null)
            Mockito.`when`(positionRepo.findByTpOrderId("stale-order-A")).thenReturn(null)

            engine.handleExecutionReport(
                ExecutionReport(
                    orderId = "stale-order-A",
                    status = OrderStatus.FILLED,
                    cumulativeFilledQty = 100,
                    avgPrice = BigDecimal("110"),
                    ticker = "SBER",
                    side = "sell",
                ),
            )
        }

        assertFalse(handled) { "late fill for stale order returns false" }
        assertEquals(6, pos.quantity) { "position unchanged — stale fill dropped" }
    }

    // ─── P0: stale order identity in handleCloseFill ────────────────────

    /**
     * handleCloseFill receives late WS event for order A after B was created.
     * closeOrderId = B, report.orderId = A → must be ignored, no state mutation.
     */
    @Test
    fun `handleCloseFill ignores late fill from wrong order id`() {
        val pos = openPos(
            quantity = 60,
            pendingClose = false,
            closeOrderId = "order-B",
            cumulativeCloseFillQty = 0,
        )
        stubFindCloseOrderId(pos)

        val lateReport = ExecutionReport(
            orderId = "order-A",
            status = OrderStatus.PARTIALLY_FILLED,
            cumulativeFilledQty = 40,
            avgPrice = BigDecimal("110"),
            ticker = "SBER",
            side = "sell",
        )

        runBlocking { engine.handleCloseFill(pos, lateReport) }

        assertEquals(60, pos.quantity) { "quantity unchanged — stale event ignored" }
        assertEquals(0, pos.cumulativeCloseFillQty) { "cumulative unchanged — stale event ignored" }
        assertEquals("order-B", pos.closeOrderId) { "closeOrderId unchanged — stale event ignored before mutation" }
    }

    /**
     * handleCloseFill accepts event for correct order but ignores if
     * cumulative has not increased.
     */
    @Test
    fun `handleCloseFill accepts correct order id with valid delta`() {
        val pos = openPos(
            quantity = 60,
            pendingClose = false,
            closeOrderId = "order-B",
            cumulativeCloseFillQty = 0,
        )
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        val report = ExecutionReport(
            orderId = "order-B",
            status = OrderStatus.PARTIALLY_FILLED,
            cumulativeFilledQty = 20,
            avgPrice = BigDecimal("110"),
            ticker = "SBER",
            side = "sell",
        )

        runBlocking { engine.handleCloseFill(pos, report) }

        assertEquals(40, pos.quantity) { "quantity reduced by delta=20" }
        assertEquals(20, pos.cumulativeCloseFillQty) { "cumulative updated to 20" }
    }

    // ─── P0: stale order identity in handlePendingCloseReport ───────────

    /**
     * handlePendingCloseReport receives late WS event for order A after B was created.
     * closeOrderId = B, report.orderId = A → must be ignored.
     */
    @Test
    fun `handlePendingCloseReport ignores late fill from wrong order id`() {
        val pos = openPos(
            quantity = 60,
            pendingClose = true,
            closeOrderId = "order-B",
            cumulativeCloseFillQty = 0,
        )
        stubFindCloseOrderId(pos)

        val lateReport = ExecutionReport(
            orderId = "order-A",
            status = OrderStatus.PARTIALLY_FILLED,
            cumulativeFilledQty = 40,
            avgPrice = BigDecimal("110"),
            ticker = "SBER",
            side = "sell",
        )

        val handled = runBlocking { engine.handleExecutionReport(lateReport) }

        assertTrue(handled) { "report was matched by closeOrderId path" }
        assertEquals(60, pos.quantity) { "quantity unchanged — stale event ignored" }
        assertEquals(0, pos.cumulativeCloseFillQty) { "cumulative unchanged — stale event ignored" }
    }

    /**
     * handlePendingCloseReport with impossible delta — cancel order then reset to clean OPEN.
     */
    @Test
    fun `handlePendingCloseReport cancels order then resets close state on impossible delta`() {
        val pos = openPos(
            quantity = 60,
            pendingClose = true,
            closeOrderId = "order-A",
            cumulativeCloseFillQty = 0,
        )
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubCancelOrderConfirmed()

        val report = ExecutionReport(
            orderId = "order-A",
            status = OrderStatus.PARTIALLY_FILLED,
            cumulativeFilledQty = 80,
            avgPrice = BigDecimal("110"),
            ticker = "SBER",
            side = "sell",
        )

        val handled = runBlocking { engine.handleExecutionReport(report) }

        assertTrue(handled) { "report matched by closeOrderId path" }
        assertEquals(60, pos.quantity) { "quantity unchanged — impossible delta rejected" }
        assertEquals(0, pos.cumulativeCloseFillQty) { "cumulative NOT mutated" }
        assertEquals(PositionStatus.OPEN, pos.status) { "position NOT finalized" }
        assertNull(pos.closeOrderId) { "closeOrderId cleared after cancel confirmed" }
        assertFalse(pos.pendingClose) { "pendingClose=false after cancel confirmed" }
    }

    // ─── P0: impossible delta > quantity ─────────────────────────────────

    /**
     * applyCloseExecution: filled > position.quantity → cancel order, reset to clean OPEN.
     */
    @Test
    fun `applyCloseExecution cancels order on filled greater than position quantity`() {
        val pos = openPos(
            quantity = 60,
            pendingClose = false,
            closeOrderId = "order-A",
            cumulativeCloseFillQty = 0,
        )
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubCancelOrderConfirmed()

        val report = ExecutionReport(
            orderId = "order-A",
            status = OrderStatus.FILLED,
            cumulativeFilledQty = 80,
            avgPrice = BigDecimal("110"),
            ticker = "SBER",
            side = "sell",
        )

        runBlocking { engine.handleCloseFill(pos, report) }

        assertEquals(60, pos.quantity) { "quantity unchanged — impossible fill rejected" }
        assertEquals(0, pos.cumulativeCloseFillQty) { "cumulative NOT mutated — impossible fill rejected before assignment" }
        assertEquals(PositionStatus.OPEN, pos.status) { "position NOT finalized" }
        assertNull(pos.closeOrderId) { "closeOrderId cleared after cancel confirmed" }
        assertFalse(pos.pendingClose) { "pendingClose=false after cancel confirmed" }
    }

    /**
     * Delta > quantity through confirmCloseFill (REST path) → cancel order, reset to clean OPEN.
     */
    @Test
    fun `confirmCloseFill cancels order on cumulative delta greater than position quantity`() {
        val pos = openPos(
            quantity = 60,
            pendingClose = false,
            closeOrderId = "order-A",
            cumulativeCloseFillQty = 0,
        )
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubCancelOrderConfirmed()

        runBlocking {
            Mockito.`when`(
                alorClient.verifyOrder(Mockito.anyString(), anyBigDecimal(), Mockito.anyString()),
            ).thenReturn(AlorClient.OrderExecution("FILLED", 80, BigDecimal("110")))
        }

        runBlocking { engine.closeFill.confirmCloseFill(pos, BigDecimal("110"), CloseReason.RECONCILIATION) }

        assertEquals(60, pos.quantity) { "quantity unchanged — impossible cumulative rejected" }
        assertEquals(0, pos.cumulativeCloseFillQty) { "cumulative NOT mutated — impossible delta rejected before assignment" }
        assertEquals(PositionStatus.OPEN, pos.status) { "position NOT finalized" }
        assertNull(pos.closeOrderId) { "closeOrderId cleared after cancel confirmed" }
    }

    /**
     * Impossible delta + cancel UNCERTAIN → closeOrderId preserved, pendingClose=true.
     * Reconciler will pick up and verify via confirmCloseFill.
     */
    @Test
    fun `impossible delta with uncertain cancel leaves state for reconciler`() {
        val pos = openPos(
            quantity = 60,
            pendingClose = true,
            closeOrderId = "order-A",
            cumulativeCloseFillQty = 0,
        )
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()
        stubCancelOrderUncertain()

        val report = ExecutionReport(
            orderId = "order-A",
            status = OrderStatus.PARTIALLY_FILLED,
            cumulativeFilledQty = 80,
            avgPrice = BigDecimal("110"),
            ticker = "SBER",
            side = "sell",
        )

        val handled = runBlocking { engine.handleExecutionReport(report) }

        assertTrue(handled) { "report matched by closeOrderId path" }
        assertEquals(60, pos.quantity) { "quantity unchanged" }
        assertEquals(0, pos.cumulativeCloseFillQty) { "cumulative NOT mutated" }
        assertEquals(PositionStatus.OPEN, pos.status) { "position NOT finalized" }
        assertEquals("order-A", pos.closeOrderId) { "closeOrderId PRESERVED — order state uncertain" }
        assertTrue(pos.pendingClose) { "pendingClose=true — reconciler must handle" }
    }

    /**
     * Impossible delta with no closeOrderId → immediate reset (no cancel needed).
     */
    @Test
    fun `impossible delta with no closeOrderId resets immediately`() {
        val pos = openPos(
            quantity = 60,
            pendingClose = true,
            closeOrderId = null,
            cumulativeCloseFillQty = 0,
        )
        stubFindCloseOrderId(pos)
        stubSaveReturnsArg()

        val report = ExecutionReport(
            orderId = "order-A",
            status = OrderStatus.PARTIALLY_FILLED,
            cumulativeFilledQty = 80,
            avgPrice = BigDecimal("110"),
            ticker = "SBER",
            side = "sell",
        )

        val handled = runBlocking { engine.handleExecutionReport(report) }

        assertTrue(handled) { "report matched by pendingClose=true path" }
        assertFalse(pos.pendingClose) { "pendingClose=false — immediate reset" }
        assertNull(pos.closeOrderId) { "closeOrderId null — no order to cancel" }
    }
}
