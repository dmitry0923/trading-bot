package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorClient.ReconcileResult
import com.trading.bot.client.WebSocketManager
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
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

    private val service =
        StateReconciliationService(
            webSocketManager,
            alorClient,
            positionRepo,
            tradingConfig,
            alorConfig,
            eventPublisher,
            meterRegistry,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(webSocketManager, alorClient, positionRepo, eventPublisher)
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
            Mockito.`when`(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(positions.toList())
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
            Mockito.`when`(alorClient.getOpenOrders()).thenReturn(ReconcileResult.Ok(orders))
            Mockito.`when`(alorClient.getPositions()).thenReturn(ReconcileResult.Ok(positions))
            Mockito.`when`(alorClient.getRecentTrades()).thenReturn(ReconcileResult.Ok(trades))
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
        assertEquals("RECONCILE_PHANTOM", saved.closeReason)
        assertFalse(saved.pendingClose)
        verify(eventPublisher).publishTradingHalted(anyTradingHaltedEvent())
    }

    @Test
    fun `pendingClose position without exchange position is finalized as closed on exchange`() {
        val pos =
            openPos("Si", 2).apply {
                pendingClose = true
                closeReason = "STOP_LOSS"
            }
        stubOpenPositions(pos)
        stubReconcileOk()

        runBlocking { service.reconcile() }

        val captor = argumentCaptor<Position>()
        runBlocking { verify(positionRepo).save(captor.capture()) }
        assertEquals(PositionStatus.CLOSED, captor.firstValue.status)
        assertEquals("RECONCILE_CLOSED_ON_EXCHANGE", captor.firstValue.closeReason)
    }

    @Test
    fun `quantity is adjusted to exchange value on partial close during gap`() {
        val pos = openPos("SBER", 10)
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
        assertEquals(4, captor.firstValue.quantity)
        assertEquals(PositionStatus.OPEN, captor.firstValue.status)
        verify(eventPublisher, never()).publishTradingHalted(anyTradingHaltedEvent())
    }

    @Test
    fun `reconciliation is aborted on fetch failure without mutating local state`() {
        stubOpenPositions(openPos("SBER", 10))
        runBlocking {
            Mockito.`when`(alorClient.getOpenOrders()).thenReturn(ReconcileResult.Ok(emptyList()))
            Mockito.`when`(alorClient.getPositions()).thenReturn(ReconcileResult.Failed)
            Mockito.`when`(alorClient.getRecentTrades()).thenReturn(ReconcileResult.Ok(emptyList()))
        }

        runBlocking { service.reconcile() }

        runBlocking { verify(positionRepo, never()).save(anyPosition()) }
        verify(eventPublisher, never()).publishTradingHalted(anyTradingHaltedEvent())
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

        runBlocking { verify(alorClient, never()).getOpenOrders() }
        runBlocking { verify(positionRepo, never()).save(anyPosition()) }
    }
}
