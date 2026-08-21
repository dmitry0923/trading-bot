package com.trading.bot.service

import com.trading.bot.application.MarketDataGate
import com.trading.bot.application.TradingGate
import com.trading.bot.application.decision.DecisionEngine
import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorWebSocketClient
import com.trading.bot.client.WebSocketManager
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.ExecutionReportEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * Unit-тесты пути исполнения акций через WS ExecutionReportEvent (EXEC-1, roadmap 13.27):
 * - WS-fill ВХОДНОГО ордера на non-pending OPEN позиции НЕ закрывает её (фикс ложного закрытия);
 * - WS-fill close-ордера по-прежнему закрывает позицию (WS-путь закрытия, fallback engine).
 */
class TradingBotServiceExecutionReportTest {
    private val tradingConfig = TradingConfig().apply { wsQuotesEnabled = false }
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val alorWsClient = Mockito.mock(AlorWebSocketClient::class.java)
    private val webSocketManager = Mockito.mock(WebSocketManager::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val redis = Mockito.mock(ReactiveRedisCacheService::class.java)
    private val riskConfig = RiskConfig()
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val alorConfig = AlorConfig()
    private val objectMapper = jacksonObjectMapper()
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val eventPublisher = Mockito.mock(TradingEventPublisher::class.java)
    private val tradingGate = Mockito.mock(TradingGate::class.java)
    private val marketDataGate = Mockito.mock(MarketDataGate::class.java)
    private val decisionEngine = Mockito.mock(DecisionEngine::class.java)
    private val distributedLockService = Mockito.mock(DistributedLockService::class.java)
    private val distributedLockConfig = DistributedLockConfig()
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)
    private val instrumentsConfig = Mockito.mock(com.trading.bot.config.InstrumentsConfig::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var service: TradingBotService

    @BeforeEach
    fun setUp() {
        runBlocking {
            Mockito.`when`(tradingAccountService.findEnabled()).thenReturn(emptyList())
        }
        Mockito.`when`(alorWsClient.subscribeToOrders(Mockito.anyString())).thenReturn(emptyFlow())
        Mockito.`when`(webSocketManager.events).thenReturn(emptyFlow())
        service =
            TradingBotService(
                tradingConfig,
                alorClient,
                alorWsClient,
                webSocketManager,
                orderOutboxService,
                redis,
                riskConfig,
                positionRepo,
                orderOutboxRepo,
                alorConfig,
                objectMapper,
                tradeEventService,
                eventPublisher,
                tradingGate,
                marketDataGate,
                decisionEngine,
                distributedLockService,
                distributedLockConfig,
                tradingAccountService,
                instrumentsConfig,
                meterRegistry,
            )
    }

    private fun stockPosition(
        alorOrderId: String?,
        closeOrderId: String? = null,
        pendingClose: Boolean = false,
    ): Position =
        Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("100"),
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            alorOrderId = alorOrderId,
            closeOrderId = closeOrderId,
            pendingClose = pendingClose,
        )

    @Test
    fun `entry order fill does not close open stock position (EXEC-1)`() {
        val pos = stockPosition(alorOrderId = "ord-entry-1")
        runBlocking {
            Mockito.`when`(positionRepo.findByAlorOrderId("ord-entry-1")).thenReturn(pos)
        }
        val report =
            ExecutionReport(
                orderId = "ord-entry-1",
                status = OrderStatus.FILLED,
                cumulativeFilledQty = 10,
                avgPrice = BigDecimal("105"),
                ticker = "SBER",
                side = "buy",
            )

        service.onExecutionReport(ExecutionReportEvent(report))

        runBlocking {
            verify(positionRepo, Mockito.timeout(3000)).findByAlorOrderId("ord-entry-1")
            verify(positionRepo, never()).save(any())
            verify(eventPublisher, never()).publishPositionClosed(any())
            verify(tradeEventService, never()).recordPositionClosed(any(), any())
        }
        assertEquals(PositionStatus.OPEN, pos.status)
    }

    @Test
    fun `entry order partial fill does not close open stock position (EXEC-1)`() {
        val pos = stockPosition(alorOrderId = "ord-entry-2")
        runBlocking {
            Mockito.`when`(positionRepo.findByAlorOrderId("ord-entry-2")).thenReturn(pos)
        }
        val report =
            ExecutionReport(
                orderId = "ord-entry-2",
                status = OrderStatus.PARTIALLY_FILLED,
                cumulativeFilledQty = 5,
                avgPrice = BigDecimal("104"),
                ticker = "SBER",
                side = "buy",
            )

        service.onExecutionReport(ExecutionReportEvent(report))

        runBlocking {
            verify(positionRepo, Mockito.timeout(3000)).findByAlorOrderId("ord-entry-2")
            verify(positionRepo, never()).save(any())
        }
        assertEquals(PositionStatus.OPEN, pos.status)
    }

    @Test
    fun `close order fill still closes open stock position via WS fallback`() {
        val pos = stockPosition(alorOrderId = "ord-entry-1", closeOrderId = "ord-close-1")
        runBlocking {
            Mockito.`when`(positionRepo.findByAlorOrderId("ord-close-1")).thenReturn(null)
            Mockito.`when`(positionRepo.findByCloseOrderId("ord-close-1")).thenReturn(pos)
            Mockito.`when`(positionRepo.findById(1L)).thenReturn(pos)
            Mockito
                .`when`(positionRepo.save(any()))
                .thenAnswer { inv -> inv.getArgument<Position>(0) }
        }
        runBlocking {
            Mockito.`when`(
                positionRepo.transitionToClosed(any(), any(), any(), any(), any(), any()),
            ).thenReturn(true)
        }
        val report =
            ExecutionReport(
                orderId = "ord-close-1",
                status = OrderStatus.FILLED,
                cumulativeFilledQty = 10,
                avgPrice = BigDecimal("110"),
                ticker = "SBER",
                side = "sell",
            )

        service.onExecutionReport(ExecutionReportEvent(report))

        runBlocking {
            verify(positionRepo, Mockito.timeout(3000)).findByAlorOrderId("ord-close-1")
        }
        awaitUntil { pos.status == PositionStatus.CLOSED }

        assertEquals(PositionStatus.CLOSED, pos.status)
        assertEquals(CloseReason.EXECUTION_FILL, pos.closeReason)
        runBlocking {
            verify(eventPublisher, Mockito.timeout(3000)).publishPositionClosed(any())
            verify(tradeEventService, Mockito.timeout(3000)).recordPositionClosed(any(), any())
        }
    }

    /**
     * Partial close fill delegated to engine's delta model (fix: partial-close fallback).
     * Previously, partial fills were silently ignored — position stayed at full quantity.
     * Now, the delta (cumulative - applied) is correctly applied to the position,
     * and closeOrderId/pendingClose are preserved for subsequent fills.
     */
    @Test
    fun `close order partial fill applies delta via engine fallback`() {
        val pos = stockPosition(alorOrderId = "ord-entry-1", closeOrderId = "ord-close-1")
        runBlocking {
            Mockito.`when`(positionRepo.findByAlorOrderId("ord-close-1")).thenReturn(null)
            Mockito.`when`(positionRepo.findByCloseOrderId("ord-close-1")).thenReturn(pos)
            Mockito.`when`(positionRepo.findById(1L)).thenReturn(pos)
            Mockito
                .`when`(positionRepo.save(any()))
                .thenAnswer { inv -> inv.getArgument<Position>(0) }
        }
        val report =
            ExecutionReport(
                orderId = "ord-close-1",
                status = OrderStatus.PARTIALLY_FILLED,
                cumulativeFilledQty = 5,
                avgPrice = BigDecimal("110"),
                ticker = "SBER",
                side = "sell",
            )

        service.onExecutionReport(ExecutionReportEvent(report))

        runBlocking {
            verify(positionRepo, Mockito.timeout(3000).atLeastOnce()).findByCloseOrderId("ord-close-1")
        }
        awaitUntil { pos.quantity != 10 }

        // Delta applied: 5 - 0 = 5, quantity = 10 - 5 = 5
        assertEquals(5, pos.quantity)
        assertEquals(5, pos.cumulativeCloseFillQty)
        assertEquals(PositionStatus.OPEN, pos.status)
        // closeOrderId preserved for subsequent fills
        assertEquals("ord-close-1", pos.closeOrderId)
        // P&L applied for the partial fill (delta=5 at price 110 vs entry 100)
        assertEquals(0, BigDecimal("50").compareTo(pos.realizedPnl))
    }

    private fun awaitUntil(
        timeoutMs: Long = 4000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }
}
