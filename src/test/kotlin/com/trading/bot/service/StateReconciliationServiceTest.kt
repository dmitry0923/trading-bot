package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorClient.ReconcileResult
import com.trading.bot.client.WebSocketManager
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.math.BigDecimal

/**
 * Unit-тесты [StateReconciliationService]: детекция «фантомных» позиций,
 * приведение qty к биржевому значению, fail-safe при недоступности REST и
 * алерт на неизвестную биржевую позицию.
 */
class StateReconciliationServiceTest {
    private val webSocketManager = Mockito.mock(WebSocketManager::class.java)
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val tradingConfig = TradingConfig().apply { mode = "LIVE" }
    private val alorConfig = AlorConfig().apply { wsReconcileOnReconnect = true }
    private val eventPublisher = Mockito.mock(TradingEventPublisher::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)

    private val service =
        StateReconciliationService(
            webSocketManager,
            alorClient,
            positionRepo,
            tradingConfig,
            alorConfig,
            eventPublisher,
            meterRegistry,
            tradingAccountService,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(webSocketManager, alorClient, positionRepo, eventPublisher, tradingAccountService)
        runBlocking {
            Mockito.`when`(tradingAccountService.findEnabled()).thenReturn(emptyList())
        }
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return Position(ticker = "x", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal.ZERO)
    }

    private fun anyTradingHaltedEvent(): TradingHaltedEvent {
        Mockito.any(TradingHaltedEvent::class.java)
        return TradingHaltedEvent("test")
    }

    private fun stubOpenPositions(vararg positions: Position) {
        runBlocking {
            Mockito.`when`(positionRepo.findOpenByAccount(null)).thenReturn(positions.toList())
            Mockito
                .`when`(positionRepo.save(anyPosition()))
                .thenAnswer { inv -> inv.getArgument<Position>(0) }
        }
    }

    private fun stubReconcileOk(
        orders: List<AlorClient.ExchangeOrder> = emptyList(),
        positions: List<AlorClient.ExchangePosition> = emptyList(),
        trades: List<AlorClient.ExchangeTrade> = emptyList(),
    ) {
        runBlocking {
            Mockito.`when`(alorClient.getOpenOrders(Mockito.anyString())).thenReturn(ReconcileResult.Ok(orders))
            Mockito.`when`(alorClient.getPositions(Mockito.anyString())).thenReturn(ReconcileResult.Ok(positions))
            Mockito.`when`(alorClient.getRecentTrades(Mockito.anyString())).thenReturn(ReconcileResult.Ok(trades))
        }
    }

    private fun openPos(
        ticker: String,
        quantity: Int,
        direction: PositionDirection = PositionDirection.LONG,
        status: PositionStatus = PositionStatus.OPEN,
    ): Position =
        Position(
            id = 1L,
            ticker = ticker,
            direction = direction,
            quantity = quantity,
            entryPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = status,
        )

    @Test
    fun `phantom position is closed when exchange is flat and no working orders`() {
        val pos = openPos("SBER", 10)
        stubOpenPositions(pos)
        stubReconcileOk()

        runBlocking { service.reconcile() }

        val captor = argumentCaptor<Position>()
        runBlocking { verify(positionRepo).save(captor.capture()) }
        val saved = captor.firstValue
        assertEquals(PositionStatus.CLOSED, saved.status)
        assertEquals(CloseReason.RECONCILE_PHANTOM, saved.closeReason)
        assertFalse(saved.pendingClose)
        verify(eventPublisher).publishTradingHalted(anyTradingHaltedEvent())
    }

    @Test
    fun `pendingClose position without exchange position is finalized as closed on exchange`() {
        val pos =
            openPos("Si", 2).apply {
                pendingClose = true
                closeReason = CloseReason.STOP_LOSS
            }
        stubOpenPositions(pos)
        stubReconcileOk()

        runBlocking { service.reconcile() }

        val captor = argumentCaptor<Position>()
        runBlocking { verify(positionRepo).save(captor.capture()) }
        assertEquals(PositionStatus.CLOSED, captor.firstValue.status)
        assertEquals(CloseReason.RECONCILE_CLOSED_ON_EXCHANGE, captor.firstValue.closeReason)
    }

    @Test
    fun `quantity mismatch marks reconciliation required and halts (fail-closed)`() {
        val pos = openPos("SBER", 10).apply { goPerContract = BigDecimal("15000") }
        stubOpenPositions(pos)
        stubReconcileOk(
            positions =
                listOf(
                    AlorClient.ExchangePosition(
                        ticker = "SBER",
                        qty = 4,
                        avgPrice = BigDecimal("100"),
                    ),
                ),
        )

        runBlocking { service.reconcile() }

        val captor = argumentCaptor<Position>()
        runBlocking { verify(positionRepo).save(captor.capture()) }
        assertEquals(PositionStatus.RECONCILIATION_REQUIRED, captor.firstValue.status)
        assertFalse(captor.firstValue.pendingClose)
        assertFalse(captor.firstValue.pendingEntry)
        verify(eventPublisher).publishTradingHalted(anyTradingHaltedEvent())
    }

    @Test
    fun `phantom position with recovered trade price gets closePrice set`() {
        val pos = openPos("SBER", 10)
        stubOpenPositions(pos)
        stubReconcileOk(
            trades =
                listOf(
                    AlorClient.ExchangeTrade(
                        id = "t1",
                        orderId = null,
                        ticker = "SBER",
                        side = "sell",
                        quantity = 10,
                        price = BigDecimal("115"),
                        time = java.time.Instant.now(),
                    ),
                ),
        )

        runBlocking { service.reconcile() }

        val captor = argumentCaptor<Position>()
        runBlocking { verify(positionRepo).save(captor.capture()) }
        assertEquals(PositionStatus.CLOSED, captor.firstValue.status)
        assertEquals(CloseReason.RECONCILE_PHANTOM, captor.firstValue.closeReason)
        assertEquals(0, BigDecimal("115").compareTo(captor.firstValue.closePrice!!))
    }

    @Test
    fun `reconciliation is aborted on fetch failure without mutating local state but halts trading`() {
        stubOpenPositions(openPos("SBER", 10))
        runBlocking {
            Mockito.`when`(alorClient.getOpenOrders(Mockito.anyString())).thenReturn(ReconcileResult.Ok(emptyList()))
            Mockito.`when`(alorClient.getPositions(Mockito.anyString())).thenReturn(ReconcileResult.Failed)
            Mockito.`when`(alorClient.getRecentTrades(Mockito.anyString())).thenReturn(ReconcileResult.Ok(emptyList()))
        }

        runBlocking { service.reconcile() }

        runBlocking { verify(positionRepo, never()).save(anyPosition()) }
        verify(eventPublisher).publishTradingHalted(anyTradingHaltedEvent())
    }

    @Test
    fun `direction mismatch marks position reconciliation required and halts`() {
        val pos = openPos("SBER", 10)
        stubOpenPositions(pos)
        stubReconcileOk(
            positions =
                listOf(
                    AlorClient.ExchangePosition(
                        ticker = "SBER",
                        qty = -10,
                        avgPrice = BigDecimal("100"),
                    ),
                ),
        )

        runBlocking { service.reconcile() }

        val captor = argumentCaptor<Position>()
        runBlocking { verify(positionRepo).save(captor.capture()) }
        assertEquals(PositionStatus.RECONCILIATION_REQUIRED, captor.firstValue.status)
        verify(eventPublisher).publishTradingHalted(anyTradingHaltedEvent())
    }

    @Test
    fun `unknown exchange position raises state desync alert`() {
        stubOpenPositions()
        stubReconcileOk(
            positions =
                listOf(
                    AlorClient.ExchangePosition(
                        ticker = "GAZP",
                        qty = 100,
                        avgPrice = BigDecimal("200"),
                    ),
                ),
        )

        runBlocking { service.reconcile() }

        verify(eventPublisher).publishTradingHalted(anyTradingHaltedEvent())
    }

    @Test
    fun `reconciliation is skipped outside live mode`() {
        tradingConfig.mode = "SIMULATION"
        stubOpenPositions()

        runBlocking { service.reconcile() }

        runBlocking { verify(alorClient, never()).getOpenOrders(Mockito.anyString()) }
        runBlocking { verify(positionRepo, never()).save(anyPosition()) }
    }
}
