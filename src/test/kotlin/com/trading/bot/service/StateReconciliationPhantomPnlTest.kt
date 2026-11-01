package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorClient.ReconcileResult
import com.trading.bot.client.WebSocketManager
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.InstrumentsConfig.InstrumentSpec
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant

/**
 * [StateReconciliationService] — P&L фантомного закрытия: фьючерс по
 * `pointValue` (а не `lotSize`), неизвестный тикер с fallback размеров лота,
 * проигранная гонка `transitionToClosed` и позиция без id; плюс fail-soft
 * отмены осиротевших защитных заявок.
 */
class StateReconciliationPhantomPnlTest {
    private val webSocketManager = mock<WebSocketManager>()
    private val alorClient = mock<AlorClient>()
    private val positionRepo = mock<PositionRepository>()
    private val tradingConfig = TradingConfig().apply { mode = "LIVE" }
    private val alorConfig = AlorConfig()
    private val eventPublisher = mock<TradingEventPublisher>()
    private val meterRegistry = SimpleMeterRegistry()
    private val tradingAccountService = mock<TradingAccountService>()
    private val recoveryGate = mock<EntryLeaseRecoveryGate>()
    private val tradeEventService = mock<TradeEventService>()

    private fun service(instruments: InstrumentsConfig = InstrumentsConfig()): StateReconciliationService {
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
            instruments,
            tradeEventService,
        )
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return position()
    }

    private fun position(
        id: Long? = 1L,
        ticker: String = "SBER",
        entryPrice: String = "100",
        quantity: Int = 10,
    ): Position =
        Position(
            id = id,
            ticker = ticker,
            direction = PositionDirection.LONG,
            quantity = quantity,
            entryPrice = BigDecimal(entryPrice),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
        )

    private fun stubTransition(updated: Boolean) {
        runBlocking {
            whenever(
                positionRepo.transitionToClosed(
                    Mockito.anyLong(),
                    anyStatus(),
                    anyBigDecimal(),
                    anyReason(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                ),
            ).thenReturn(updated)
        }
    }

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
        return CloseReason.RECONCILE_PHANTOM
    }

    private fun trade(
        ticker: String,
        price: String,
    ): AlorClient.ExchangeTrade =
        AlorClient.ExchangeTrade(
            id = "t-1",
            orderId = null,
            ticker = ticker,
            side = "sell",
            quantity = 10,
            price = BigDecimal(price),
            time = Instant.parse("2026-10-25T10:00:00Z"),
        )

    private suspend fun stubFlatExchange(trade: AlorClient.ExchangeTrade) {
        whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(listOf(position()))
        whenever(alorClient.getOpenOrders("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
        whenever(alorClient.getPositions("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
        whenever(alorClient.getRecentTrades("D12345")).thenReturn(ReconcileResult.Ok(listOf(trade)))
    }

    @Test
    fun `futures phantom close uses point value not lot size`(): Unit =
        runBlocking {
            val pos = position(ticker = "Si", entryPrice = "83200", quantity = 1)
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(listOf(pos))
            whenever(alorClient.getOpenOrders("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            whenever(alorClient.getPositions("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            whenever(alorClient.getRecentTrades("D12345")).thenReturn(ReconcileResult.Ok(listOf(trade("Si", "83000"))))
            stubTransition(true)

            service().reconcile()

            // (83000 - 83200) × pointValue(1000) = -200 000 (не ×lotSize)
            assertEquals(0, BigDecimal("-200000").compareTo(pos.pnl))
            assertEquals(PositionStatus.CLOSED, pos.status)
        }

    @Test
    fun `unknown ticker phantom uses unit lot size and zero commission`(): Unit =
        runBlocking {
            val pos = position(ticker = "ZZZZ", entryPrice = "100", quantity = 10)
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(listOf(pos))
            whenever(alorClient.getOpenOrders("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            whenever(alorClient.getPositions("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            whenever(alorClient.getRecentTrades("D12345")).thenReturn(ReconcileResult.Ok(listOf(trade("ZZZZ", "115"))))
            stubTransition(true)

            service().reconcile()

            // (115 - 100) × 10 × lotSize(fallback 1) = 150, комиссия неизвестна → 0
            assertEquals(0, BigDecimal("150").compareTo(pos.pnl))
        }

    @Test
    fun `phantom close ignores trades without a side and tolerates a missing time`(): Unit =
        runBlocking {
            val pos = position(ticker = "SBER", entryPrice = "100", quantity = 10)
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(listOf(pos))
            whenever(alorClient.getOpenOrders("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            whenever(alorClient.getPositions("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            val noSide =
                AlorClient.ExchangeTrade(
                    id = "t-0",
                    orderId = null,
                    ticker = "SBER",
                    side = null,
                    quantity = 10,
                    price = BigDecimal("1"),
                    time = null,
                )
            val noTime =
                AlorClient.ExchangeTrade(
                    id = "t-2",
                    orderId = null,
                    ticker = "SBER",
                    side = "sell",
                    quantity = 10,
                    price = BigDecimal("110"),
                    time = null,
                )
            whenever(alorClient.getRecentTrades("D12345")).thenReturn(ReconcileResult.Ok(listOf(noSide, noTime)))
            stubTransition(true)

            service().reconcile()

            assertEquals(PositionStatus.CLOSED, pos.status)
            assertNotNull(pos.pnl, "цена восстановлена из сделки без времени")
        }

    @Test
    fun `phantom without id is not finalized`(): Unit =
        runBlocking {
            val pos = position(id = null)
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(listOf(pos))
            whenever(alorClient.getOpenOrders("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            whenever(alorClient.getPositions("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            whenever(alorClient.getRecentTrades("D12345")).thenReturn(ReconcileResult.Ok(listOf(trade("SBER", "115"))))
            stubTransition(true)

            service().reconcile()

            verify(positionRepo, never()).transitionToClosed(
                anyLongArg(),
                anyStatus(),
                anyBigDecimal(),
                anyReason(),
                anyBigDecimal(),
                Mockito.anyInt(),
            )
            assertNull(pos.closePrice)
        }

    @Test
    fun `phantom lost race does not publish a duplicate close`(): Unit =
        runBlocking {
            val pos = position()
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(listOf(pos))
            whenever(alorClient.getOpenOrders("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            whenever(alorClient.getPositions("D12345")).thenReturn(ReconcileResult.Ok(emptyList()))
            whenever(alorClient.getRecentTrades("D12345")).thenReturn(ReconcileResult.Ok(listOf(trade("SBER", "115"))))
            stubTransition(false)

            service().reconcile()

            verify(eventPublisher, never()).publishPositionClosed(anyPosition())
            assertNull(pos.closePrice)
            assertEquals(PositionStatus.OPEN, pos.status)
        }

    @Test
    fun `orphan cancel exception keeps ids for the next sweep`(): Unit =
        runBlocking {
            val closed =
                Position(
                    id = 9L,
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 0,
                    entryPrice = BigDecimal("100"),
                    instrumentType = InstrumentType.STOCK,
                    status = PositionStatus.CLOSED,
                    slOrderId = "sl-9",
                )
            whenever(positionRepo.findClosedWithProtectionOrders(Mockito.anyInt())).thenReturn(listOf(closed))
            Mockito
                .doAnswer { throw IllegalStateException("exchange timeout") }
                .`when`(alorClient)
                .cancelOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())

            service().cancelOrphanProtectionOrders()

            verify(positionRepo, never()).clearProtectionOrderIds(Mockito.anyLong())
        }

    @Test
    fun `orphan sweep tolerates a position without id`(): Unit =
        runBlocking {
            val closed =
                Position(
                    id = null,
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 0,
                    entryPrice = BigDecimal("100"),
                    instrumentType = InstrumentType.STOCK,
                    status = PositionStatus.CLOSED,
                    slOrderId = "sl-x",
                )
            whenever(positionRepo.findClosedWithProtectionOrders(Mockito.anyInt())).thenReturn(listOf(closed))
            Mockito
                .doAnswer { AlorClient.CancelResult.CONFIRMED }
                .`when`(alorClient)
                .cancelOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())

            service().cancelOrphanProtectionOrders()

            verify(positionRepo, never()).clearProtectionOrderIds(Mockito.anyLong())
        }

    private fun anyLongArg(): Long {
        Mockito.anyLong()
        return 1L
    }
}
