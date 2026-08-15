package com.trading.bot.application

import com.trading.bot.application.decision.DecisionEngine
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.event.PriceChangedEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.DistributedLockService
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import com.trading.bot.service.TradingAccountService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * Unit-тест partial fills при закрытии фьючерсной позиции (дозакрытие остатка):
 *
 * - SL-тик → market close на qty=3 → Alor исполнил только 2 → [Position.realizedPnl]
 *   фиксирует P&L закрытой части, quantity уменьшается до 1, стейт-машина сбрасывается
 *   (pendingClose=false, closeOrderId=null) → остаток дозакрывается новой итерацией;
 * - повторный SL-тик → новый close-ордер на остаток (qty=1) → полное исполнение →
 *   итоговый pnl = realizedPnl (partial) + P&L остатка.
 *
 * Защита от double execution: пока pendingClose=true новый ордер НЕ создаётся —
 * только сверка через verifyOrder.
 */
class FuturesTradingBotServicePartialCloseTest {
    private val futuresRiskEngine = Mockito.mock(FuturesRiskEngine::class.java)
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val instrumentsConfig = Mockito.mock(InstrumentsConfig::class.java)
    private val riskConfig = Mockito.mock(RiskConfig::class.java)
    private val alorConfig = Mockito.mock(AlorConfig::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val eventPublisher = Mockito.mock(TradingEventPublisher::class.java)
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val tradingGate = Mockito.mock(TradingGate::class.java)
    private val decisionEngine = Mockito.mock(DecisionEngine::class.java)
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val distributedLockConfig = DistributedLockConfig().apply { enabled = false }
    private val distributedLockService =
        DistributedLockService(distributedLockConfig, Mockito.mock(StringRedisTemplate::class.java), meterRegistry)

    private val service =
        FuturesTradingBotService(
            futuresRiskEngine,
            alorClient,
            orderOutboxService,
            positionRepo,
            orderOutboxRepo,
            instrumentsConfig,
            riskConfig,
            alorConfig,
            objectMapper,
            eventPublisher,
            tradeEventService,
            tradingGate,
            decisionEngine,
            distributedLockService,
            distributedLockConfig,
            tradingAccountService,
            meterRegistry,
        )

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return Position(ticker = "Si", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal.ZERO)
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyStatus(): PositionStatus {
        Mockito.any(PositionStatus::class.java)
        return PositionStatus.OPEN
    }

    private fun anyString(): String {
        Mockito.any(String::class.java)
        return "Si"
    }

    private fun pos(): Position =
        Position(
            id = 1L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 3,
            entryPrice = BigDecimal("90000"),
            currentPrice = BigDecimal("90000"),
            stopLoss = BigDecimal("89500"),
            takeProfit = BigDecimal("91000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
        )

    private fun stubCommon(pos: Position) {
        runBlocking {
            Mockito
                .`when`(tradingAccountService.portfolioOf(Mockito.nullable(Long::class.java)))
                .thenReturn("D12345")
            Mockito.`when`(tradingAccountService.hasEnabledAccounts()).thenReturn(false)
            Mockito
                .`when`(positionRepo.findByStatus(PositionStatus.OPEN))
                .thenReturn(listOf(pos))
            Mockito
                .`when`(positionRepo.claimForClose(Mockito.anyLong()))
                .thenReturn(true)
            Mockito
                .`when`(positionRepo.findById(Mockito.anyLong()))
                .thenReturn(pos)
            Mockito
                .`when`(
                    positionRepo.transitionToClosed(
                        Mockito.anyLong(),
                        anyStatus(),
                        anyBigDecimal(),
                        anyString(),
                        anyBigDecimal(),
                    ),
                ).thenReturn(true)
        }
        Mockito
            .`when`(futuresRiskEngine.checkLiquidationDistance(anyPosition(), anyBigDecimal()))
            .thenReturn(FuturesRiskEngine.LiquidationStatus.SAFE)
        Mockito.`when`(instrumentsConfig.pointValue(Mockito.anyString())).thenReturn(BigDecimal("1000"))
        runBlocking {
            Mockito
                .`when`(positionRepo.save(anyPosition()))
                .thenAnswer { inv -> inv.getArgument<Position>(0) }
        }
    }

    @Test
    fun `partial close realizes pnl on filled part and re-closes the remainder`() {
        val pos = pos()
        stubCommon(pos)
        runBlocking {
            Mockito
                .`when`(alorClient.verifyOrder(Mockito.anyString(), anyBigDecimal(), Mockito.anyString()))
                .thenReturn(AlorClient.OrderExecution(status = "PARTIALLY_FILLED", filledQuantity = 2, avgPrice = BigDecimal("90100")))
            Mockito
                .`when`(
                    orderOutboxService.placeOrder(
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
                    ),
                ).thenAnswer { inv ->
                    val qty = inv.getArgument<Int>(2)
                    val orderId = if (qty == 3) "ord-close-1" else "ord-close-2"
                    OrderOutboxService.PlaceOrderResult(
                        outboxId = UUID.randomUUID(),
                        alorOrderId = orderId,
                        success = true,
                    )
                }
        }

        // Фаза 1: SL-тик → market close qty=3, Alor исполнил только 2 (partial).
        service.onPriceChanged(PriceChangedEvent("Si", BigDecimal("89400")))
        awaitUntil { pos.quantity == 1 }

        assertEquals(1, pos.quantity)
        assertEquals(0, BigDecimal("200000").compareTo(pos.realizedPnl))
        assertEquals(0, BigDecimal("90100").compareTo(pos.currentPrice!!))
        assertTrue(!pos.pendingClose)
        assertNull(pos.closeOrderId)
        runBlocking {
            Mockito.verify(positionRepo, Mockito.timeout(3000)).findByStatus(PositionStatus.OPEN)
            Mockito.verify(alorClient, Mockito.timeout(3000)).verifyOrder(Mockito.anyString(), anyBigDecimal(), Mockito.anyString())
            Mockito
                .verify(orderOutboxService, Mockito.timeout(3000))
                .placeOrder(
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

        // Фаза 2: следующий SL-тик дозакрывает остаток (qty=1) полностью.
        runBlocking {
            Mockito
                .`when`(alorClient.verifyOrder(Mockito.anyString(), anyBigDecimal(), Mockito.anyString()))
                .thenReturn(AlorClient.OrderExecution(status = "FILLED", filledQuantity = 1, avgPrice = BigDecimal("89800")))
        }
        service.onPriceChanged(PriceChangedEvent("Si", BigDecimal("89400")))
        awaitUntil { pos.status == PositionStatus.CLOSED }

        assertEquals(PositionStatus.CLOSED, pos.status)
        assertEquals(0, BigDecimal.ZERO.compareTo(pos.pnl))
        assertEquals("STOP_LOSS", pos.closeReason)
        assertEquals(1, pos.quantity)
        assertTrue(!pos.pendingClose)
        assertNull(pos.closeOrderId)
        runBlocking {
            Mockito.verify(positionRepo, Mockito.atLeastOnce()).save(anyPosition())
        }
    }

    @Test
    fun `while pendingClose is in flight no second order is created`() {
        val pos =
            pos().apply {
                pendingClose = true
                closeOrderId = "ord-inflight"
                closeReason = "STOP_LOSS"
            }
        stubCommon(pos)
        runBlocking {
            Mockito
                .`when`(alorClient.verifyOrder(Mockito.anyString(), anyBigDecimal(), Mockito.anyString()))
                .thenReturn(AlorClient.OrderExecution(status = "PARTIALLY_FILLED", filledQuantity = 1, avgPrice = BigDecimal("90050")))
        }

        service.onPriceChanged(PriceChangedEvent("Si", BigDecimal("89400")))
        awaitUntil { !pos.pendingClose }

        runBlocking {
            Mockito
                .verify(orderOutboxService, Mockito.never())
                .placeOrder(
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
            Mockito.verify(alorClient, Mockito.timeout(3000)).verifyOrder(eq("ord-inflight"), anyBigDecimal(), Mockito.anyString())
        }
        assertEquals(2, pos.quantity)
        assertEquals(0, BigDecimal("50000").compareTo(pos.realizedPnl))
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
