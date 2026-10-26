package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorClient.ReconcileResult
import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsConnectionEvent
import com.trading.bot.client.WsConnectionStatus
import com.trading.bot.client.WsStream
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.TradingAccount
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant

/**
 * [StateReconciliationService] — оставшиеся ветки сверки: рабочие заявки без
 * биржевой позиции, восстановление цены выхода из REST-сделок (в т.ч. SHORT),
 * pendingEntry без позиции на бирже, multi-account единицы сверки, дебаунс
 * переподключений WS и fail-soft на исключении внутри обработчика события.
 */
class StateReconciliationServicePathsTest {
    private val webSocketManager = mock<WebSocketManager>()
    private val alorClient = mock<AlorClient>()
    private val positionRepo = mock<PositionRepository>()
    private val tradingConfig = TradingConfig().apply { mode = "LIVE" }
    private val alorConfig = AlorConfig().apply { wsReconcileOnReconnect = true }
    private val eventPublisher = mock<TradingEventPublisher>()
    private val meterRegistry = SimpleMeterRegistry()
    private val tradingAccountService = mock<TradingAccountService>()
    private val recoveryGate = mock<EntryLeaseRecoveryGate>()
    private val tradeEventService = mock<TradeEventService>()

    private fun service(): StateReconciliationService {
        runBlocking {
            whenever(tradingAccountService.findEnabled()).thenReturn(emptyList())
            whenever(positionRepo.save(anyPosition())).thenAnswer { it.getArgument<Position>(0) }
        }
        return StateReconciliationService(
            webSocketManager,
            alorClient,
            positionRepo,
            tradingConfig,
            alorConfig,
            eventPublisher,
            meterRegistry,
            tradingAccountService,
            recoveryGate,
            InstrumentsConfig(),
            tradeEventService,
        )
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return openPosition()
    }

    private fun openPosition(
        ticker: String = "SBER",
        direction: PositionDirection = PositionDirection.LONG,
        pendingClose: Boolean = false,
        pendingEntry: Boolean = false,
    ): Position =
        Position(
            id = 1L,
            ticker = ticker,
            direction = direction,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            pendingClose = pendingClose,
            pendingEntry = pendingEntry,
        )

    private suspend fun stubLocal(vararg positions: Position) {
        whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(positions.toList())
    }

    private suspend fun stubExchange(
        orders: List<AlorClient.ExchangeOrder> = emptyList(),
        positions: List<AlorClient.ExchangePosition> = emptyList(),
        trades: List<AlorClient.ExchangeTrade> = emptyList(),
        portfolio: String = "D12345",
    ) {
        whenever(alorClient.getOpenOrders(portfolio)).thenReturn(ReconcileResult.Ok(orders))
        whenever(alorClient.getPositions(portfolio)).thenReturn(ReconcileResult.Ok(positions))
        whenever(alorClient.getRecentTrades(portfolio)).thenReturn(ReconcileResult.Ok(trades))
    }

    private fun workingOrder(
        ticker: String = "SBER",
        status: String = "new",
        orderId: String = "O-1",
    ): AlorClient.ExchangeOrder =
        AlorClient.ExchangeOrder(
            orderId = orderId,
            ticker = ticker,
            side = "sell",
            status = status,
            quantity = 10,
            filledQty = 0,
            avgPrice = null,
        )

    private fun trade(
        ticker: String = "SBER",
        side: String = "sell",
        price: String = "115",
    ): AlorClient.ExchangeTrade =
        AlorClient.ExchangeTrade(
            id = "t-1",
            orderId = null,
            ticker = ticker,
            side = side,
            quantity = 10,
            price = BigDecimal(price),
            time = Instant.parse("2026-10-25T10:00:00Z"),
        )

    private fun awaitUntil(
        description: String,
        timeoutMs: Long = 4_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timeout waiting for: $description")
    }

    // ─── рабочие заявки ──────────────────────────────────────────────

    @Test
    fun `working order without exchange position leaves local position untouched`(): Unit =
        runBlocking {
            stubLocal(openPosition())
            stubExchange(orders = listOf(workingOrder()))
            val service = service()

            service.reconcile()

            Mockito.verify(positionRepo, never()).save(anyPosition())
            assertEquals(
                1.0,
                meterRegistry.counter("alor.reconcile.orders_open", "ticker", "SBER").count(),
            )
            verify(recoveryGate).recoverAll()
            Mockito.verify(eventPublisher, never()).publishTradingHalted(anyOrNull())
        }

    @Test
    fun `terminal order statuses are not treated as working`(): Unit =
        runBlocking {
            stubLocal(openPosition())
            stubExchange(
                orders =
                    listOf(
                        workingOrder(status = "filled", orderId = "O-1"),
                        workingOrder(status = "cancelled", orderId = "O-2"),
                        workingOrder(status = "rejected", orderId = "O-3"),
                        workingOrder(status = "expired", orderId = "O-4"),
                        workingOrder(status = "done", orderId = "O-5"),
                    ),
                trades = listOf(trade(price = "114")),
            )
            val service = service()

            service.reconcile()

            // терминальные заявки не считаются рабочими → позиция «фантомная» и закрывается
            assertEquals(
                1.0,
                meterRegistry.counter("alor.reconcile.discrepancy", "kind", "PHANTOM", "ticker", "SBER").count(),
            )
            assertNull(meterRegistry.find("alor.reconcile.orders_open").counter())
        }

    // ─── восстановление цены из сделок ───────────────────────────────

    @Test
    fun `pending close phantom is finalized at recovered trade price`(): Unit =
        runBlocking {
            stubLocal(openPosition(pendingClose = true))
            stubExchange(trades = listOf(trade(price = "116.5")))
            val service = service()

            service.reconcile()

            val captor = argumentCaptor<Position>()
            verify(positionRepo).save(captor.capture())
            assertEquals(PositionStatus.CLOSED, captor.firstValue.status)
            assertEquals(CloseReason.RECONCILE_CLOSED_ON_EXCHANGE, captor.firstValue.closeReason)
            assertEquals(0, BigDecimal("116.5").compareTo(captor.firstValue.closePrice!!))
            verify(eventPublisher).publishTradingHalted(any())
        }

    @Test
    fun `short phantom recovers buy side price`(): Unit =
        runBlocking {
            stubLocal(openPosition(direction = PositionDirection.SHORT))
            stubExchange(
                trades = listOf(trade(side = "sell", price = "120"), trade(side = "buy", price = "97.5")),
            )
            val service = service()

            service.reconcile()

            val captor = argumentCaptor<Position>()
            verify(positionRepo).save(captor.capture())
            assertEquals(PositionStatus.CLOSED, captor.firstValue.status)
            assertEquals(0, BigDecimal("97.5").compareTo(captor.firstValue.closePrice!!))
        }

    @Test
    fun `pending entry without exchange position is left for outbox reconciler`(): Unit =
        runBlocking {
            stubLocal(openPosition(pendingEntry = true))
            stubExchange()
            val service = service()

            service.reconcile()

            Mockito.verify(positionRepo, never()).save(anyPosition())
            assertEquals(
                1.0,
                meterRegistry.counter("alor.reconcile.entry_unconfirmed", "ticker", "SBER").count(),
            )
            Mockito.verify(eventPublisher, never()).publishTradingHalted(anyOrNull())
        }

    @Test
    fun `position failing to persist does not abort the whole reconciliation`(): Unit =
        runBlocking {
            stubLocal(openPosition(), openPosition(ticker = "GAZP"))
            stubExchange(trades = listOf(trade(ticker = "SBER"), trade(ticker = "GAZP", price = "300")))
            val service = service()
            whenever(positionRepo.save(anyPosition())).thenThrow(IllegalStateException("db down"))

            // исключение внутри цикла перехватывается — сверка продолжается и не падает
            service.reconcile()

            verify(positionRepo, Mockito.atLeastOnce()).save(anyPosition())
            verify(recoveryGate, never()).recoverAll()
        }

    @Test
    fun `phantom close books realized pnl and releases the entry slot`(): Unit =
        runBlocking {
            stubLocal(openPosition())
            stubExchange(trades = listOf(trade(price = "94.0")))
            val service = service()

            service.reconcile()

            val captor = argumentCaptor<Position>()
            verify(positionRepo).save(captor.capture())
            val closed = captor.firstValue
            assertEquals(PositionStatus.CLOSED, closed.status)
            // P&L считается по спеке инструмента (lotSize/комиссия как в TradingBotService):
            // LONG 10 @100 закрыт по 94 → (94−100)·10·lotSize минус round-trip комиссия.
            val spec = InstrumentsConfig().find("SBER")!!
            val expected =
                BigDecimal("94")
                    .subtract(BigDecimal("100"))
                    .multiply(BigDecimal(10))
                    .multiply(BigDecimal(spec.lotSize))
                    .subtract((spec.totalCommissionPerLotSide() ?: BigDecimal.ZERO).multiply(BigDecimal(10)).multiply(BigDecimal(2)))
            assertEquals(0, expected.compareTo(closed.pnl!!), "реализованный P&L записан: ${closed.pnl} (ожидалось $expected)")
            verify(tradeEventService).recordPositionClosed(anyPosition(), Mockito.anyString())
            // Дневной лимит/просадка копят P&L из событий — публикация обязательна.
            verify(eventPublisher).publishPositionClosed(anyPosition())
            verify(positionRepo).releaseEntry("SBER", null)
        }

    // ─── multi-account ──────────────────────────────────────────────

    @Test
    fun `each enabled account is reconciled against its own portfolio`(): Unit =
        runBlocking {
            val service = service()
            whenever(tradingAccountService.findEnabled()).thenReturn(
                listOf(TradingAccount(id = 7L, name = "acc-7", alorPortfolio = "D7")),
            )
            stubLocal()
            stubExchange(portfolio = "D12345")
            stubExchange(portfolio = "D7")

            service.reconcile()

            verify(alorClient).getPositions("D12345")
            verify(alorClient).getPositions("D7")
            verify(alorClient).getOpenOrders("D7")
            verify(alorClient).getRecentTrades("D7")
        }

    // ─── WS-события ─────────────────────────────────────────────────

    private fun connected(stream: WsStream = WsStream.ORDERS): WsConnectionEvent =
        WsConnectionEvent(stream = stream, status = WsConnectionStatus.CONNECTED)

    private fun disconnected(stream: WsStream = WsStream.QUOTES): WsConnectionEvent =
        WsConnectionEvent(stream = stream, status = WsConnectionStatus.DISCONNECTED)

    @Test
    fun `ws events trigger one debounced reconciliation and count disconnect window`() {
        whenever(webSocketManager.events)
            .thenReturn(flowOf(disconnected(), connected(), connected(WsStream.QUOTES)))
        val service = service()
        runBlocking {
            stubLocal()
            stubExchange()
        }

        service.start()

        awaitUntil("reconciliation triggered") {
            meterRegistry.find("alor.reconcile.run").counter() != null
        }
        awaitUntil("disconnect window counted") {
            meterRegistry.find("alor.ws.disconnect_window").counter()?.count() == 1.0
        }
        Thread.sleep(300)
        // оба CONNECTED в окне 5 c → сверка выполнена один раз (дедупликация)
        assertEquals(1.0, meterRegistry.find("alor.reconcile.run").counter()?.count())
        service.close()
    }

    @Test
    fun `ws events are ignored when reconcile on reconnect is disabled`() {
        alorConfig.wsReconcileOnReconnect = false
        whenever(webSocketManager.events).thenReturn(flowOf(disconnected(), connected()))
        val service = service()
        runBlocking {
            stubLocal()
            stubExchange()
        }

        service.start()
        Thread.sleep(400)

        assertNull(meterRegistry.find("alor.reconcile.run").counter())
        assertNull(meterRegistry.find("alor.ws.disconnect_window").counter())
        service.close()
    }

    @Test
    fun `handler failure is contained by the event collector`() {
        val events = MutableSharedFlow<WsConnectionEvent>(extraBufferCapacity = 8)
        whenever(webSocketManager.events).thenReturn(events)
        runBlocking {
            whenever(alorClient.getOpenOrders(any())).thenThrow(IllegalStateException("rest down"))
        }
        val service = service()

        service.start()
        Thread.sleep(200)
        events.tryEmit(connected())
        Thread.sleep(200)
        events.tryEmit(disconnected())

        // сборщик событий жив после исключения в обработчике: следующий event учтён
        awaitUntil("collector survived handler failure") {
            meterRegistry.find("alor.ws.disconnect_window").counter()?.count() == 1.0
        }
        assertNull(meterRegistry.find("alor.reconcile.run").counter())
        service.close()
    }
}
