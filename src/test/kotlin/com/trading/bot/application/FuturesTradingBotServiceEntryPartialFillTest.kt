package com.trading.bot.application

import com.trading.bot.application.decision.DecisionEngine
import com.trading.bot.application.decision.FuturesEntryProfile
import com.trading.bot.application.decision.NetEvGate
import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.FuturesStopResolver
import com.trading.bot.domain.risk.PortfolioRiskEngine
import com.trading.bot.domain.risk.PortfolioRiskReport
import com.trading.bot.domain.risk.PortfolioRiskRequest
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.signal.Signal
import com.trading.bot.event.PriceChangedEvent
import com.trading.bot.event.StrategyGeneratedEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.DegenerateCaseGuard
import com.trading.bot.service.DistributedLockService
import com.trading.bot.service.HigherTfTrendFilter
import com.trading.bot.service.MlEntryFilter
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import com.trading.bot.service.TradingAccountService
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit-тесты частичного исполнения входа (Gap A): остаток лимитки, «висящий» на бирже.
 *
 * - PARTIAL fill входа → позиция создаётся в pendingEntry с фактическим qty,
 *   событие PositionOpened НЕ публикуется до подтверждения;
 * - кумулятивный fill добивает до полного объёма → вход фиксируется на фактическом qty;
 * - остаток, не заполненный в течение entryPartialFillCancelAfterMs → cancelOrder +
 *   финализация входа на фактическом объёме (защита от скрытого роста позиции);
 * - до истечения порога отмена НЕ производится.
 */
class FuturesTradingBotServiceEntryPartialFillTest {
    private val futuresRiskEngine = Mockito.mock(FuturesRiskEngine::class.java)
    private val futuresPositionSizer = Mockito.mock(FuturesPositionSizer::class.java)
    private val orderBuilder = Mockito.mock(OrderBuilder::class.java)
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val alorFuturesClient = Mockito.mock(AlorFuturesClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val instrumentsConfig = Mockito.mock(InstrumentsConfig::class.java)
    private val leverageConfig = Mockito.mock(LeverageConfig::class.java)
    private val riskConfig = Mockito.mock(RiskConfig::class.java)
    private val alorConfig = AlorConfig().apply { entryPartialFillCancelAfterMs = 30_000L }
    private val objectMapper = jacksonObjectMapper()
    private val eventPublisher = Mockito.mock(TradingEventPublisher::class.java)
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val tradingGate = Mockito.mock(TradingGate::class.java)
    private val marketDataGate = Mockito.mock(MarketDataGate::class.java)
    private val portfolioRiskEngine = Mockito.mock(PortfolioRiskEngine::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val distributedLockConfig = DistributedLockConfig().apply { enabled = false }
    private val distributedLockService =
        DistributedLockService(distributedLockConfig, Mockito.mock(ReactiveStringRedisTemplate::class.java), meterRegistry)
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)
    private val mlEntryFilter = Mockito.mock(MlEntryFilter::class.java)
    private val higherTfTrendFilter = Mockito.mock(HigherTfTrendFilter::class.java)
    private val degenerateCaseGuard = Mockito.mock(DegenerateCaseGuard::class.java)
    private val netEvGate = Mockito.mock(NetEvGate::class.java)

    private val futuresEntryProfile =
        FuturesEntryProfile(
            futuresRiskEngine,
            futuresPositionSizer,
            orderBuilder,
            alorFuturesClient,
            riskConfig,
            leverageConfig,
            instrumentsConfig,
            meterRegistry,
            tradingAccountService,
            Mockito.mock(CandleCacheService::class.java),
            FuturesStopResolver(),
        )
    private val decisionEngine =
        DecisionEngine(
            marketDataGate,
            alorClient,
            orderBuilder,
            portfolioRiskEngine,
            positionRepo,
            meterRegistry,
            listOf(futuresEntryProfile),
            distributedLockService,
            distributedLockConfig,
            tradingAccountService,
            mlEntryFilter,
            higherTfTrendFilter,
            degenerateCaseGuard,
            instrumentsConfig,
            netEvGate,
            Mockito.mock(com.trading.bot.service.AdaptiveRiskService::class.java),
        )

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

    private val savedPositions = java.util.concurrent.CopyOnWriteArrayList<Position>()

    @BeforeEach
    fun stubAccount() {
        Mockito.`when`(instrumentsConfig.isFutures(Mockito.anyString())).thenReturn(true)
        runBlocking {
            Mockito.`when`(degenerateCaseGuard.check(any(), any())).thenReturn(DegenerateCaseGuard.GuardResult.Allowed)
            Mockito
                .`when`(tradingAccountService.portfolioOf(Mockito.nullable(Long::class.java)))
                .thenReturn(alorConfig.portfolio)
            Mockito.`when`(tradingAccountService.hasEnabledAccounts()).thenReturn(false)
        }
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return Position(ticker = "Si", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal.ZERO)
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyDirection(): PositionDirection {
        Mockito.any(PositionDirection::class.java)
        return PositionDirection.LONG
    }

    private fun anyEntryRequest(): EntryRequest {
        Mockito.any(EntryRequest::class.java)
        return EntryRequest(
            ticker = "Si",
            action = StrategyAction.BUY,
            entryPrice = BigDecimal.ZERO,
            direction = PositionDirection.LONG,
            portfolioMoney = BigDecimal.ZERO,
            currentGo = BigDecimal.ZERO,
            openPositions = emptyList(),
        )
    }

    private fun anyPortfolioRiskRequest(): PortfolioRiskRequest {
        Mockito.any(PortfolioRiskRequest::class.java)
        return PortfolioRiskRequest(
            candidateTicker = "Si",
            candidateDirection = PositionDirection.LONG,
            candidateNotionalRub = BigDecimal.ZERO,
            openPositions = emptyList(),
            aum = BigDecimal.ZERO,
        )
    }

    private fun anyPositionSizeResult(): PositionSizeResult {
        Mockito.any(PositionSizeResult::class.java)
        return PositionSizeResult(
            quantity = 1,
            marginRequired = BigDecimal.ZERO,
            riskAmount = BigDecimal.ZERO,
            liquidationPrice = BigDecimal.ZERO,
            reason = null,
        )
    }

    private fun signal(): Signal =
        Signal(
            ticker = "Si",
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("92000"),
            signalStrength = 0.8,
            reasoning = "test",
            timeframe = "MINUTE_10",
            cycleId = "cycle-1",
        )

    private fun outbox(
        alorOrderId: String,
        key: String,
        qty: Int,
        createdAt: LocalDateTime = LocalDateTime.now(),
    ): OrderOutbox =
        OrderOutbox(
            id = UUID.randomUUID(),
            payloadJson =
                objectMapper.writeValueAsString(
                    mapOf(
                        "ticker" to "Si",
                        "side" to "buy",
                        "qty" to qty,
                        "price" to "92000",
                        "type" to "limit",
                        "idempotencyKey" to key,
                    ),
                ),
            status = OutboxStatus.SENT,
            alorOrderId = alorOrderId,
            idempotencyKey = key,
            createdAt = createdAt,
        )

    private fun entryPosition(): Position =
        Position(
            id = 1L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 2,
            entryPrice = BigDecimal("92000"),
            currentPrice = BigDecimal("92000"),
            stopLoss = BigDecimal("91500"),
            takeProfit = BigDecimal("93000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
            alorOrderId = "ord-entry-1",
            pendingEntry = true,
        )

    private fun stubEntryAllowed() {
        Mockito.`when`(tradingGate.isTradingEnabled()).thenReturn(true)
        Mockito.`when`(instrumentsConfig.isFutures("Si")).thenReturn(true)
        Mockito.`when`(instrumentsConfig.find("Si")).thenReturn(
            InstrumentsConfig.InstrumentSpec(ticker = "Si", type = "FUTURES", lotSize = 1),
        )
        Mockito.`when`(marketDataGate.isPriceDataFresh(Mockito.anyString())).thenReturn(true)
        runBlocking {
            Mockito.`when`(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(emptyList())
            Mockito
                .`when`(
                    positionRepo.reserveEntry(
                        Mockito.anyString(),
                        anyDirection(),
                        Mockito.nullable(Long::class.java),
                    ),
                ).thenReturn(1L)
            Mockito
                .`when`(alorClient.getLastPrice(Mockito.anyString()))
                .thenReturn(BigDecimal("92000"))
            Mockito
                .`when`(alorFuturesClient.getFuturesGO(Mockito.anyString()))
                .thenReturn(BigDecimal("1000"))
            Mockito
                .`when`(alorFuturesClient.getPortfolioMoney(Mockito.anyString()))
                .thenReturn(BigDecimal("100000"))
            Mockito.`when`(leverageConfig.effective()).thenReturn(BigDecimal("2.0"))
            Mockito
                .`when`(futuresRiskEngine.canEnter(anyEntryRequest()))
                .thenReturn(RiskVerdict.Allowed)
            Mockito
                .`when`(portfolioRiskEngine.evaluate(anyPortfolioRiskRequest()))
                .thenReturn(PortfolioRiskReport(allowed = true))
            Mockito
                .`when`(
                    futuresPositionSizer.calculateContracts(
                        Mockito.anyString(),
                        anyBigDecimal(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.nullable(BigDecimal::class.java),
                        Mockito.nullable(PositionDirection::class.java),
                    ),
                ).thenReturn(
                    PositionSizeResult(
                        quantity = 3,
                        marginRequired = BigDecimal("1000"),
                        riskAmount = BigDecimal("500"),
                        liquidationPrice = BigDecimal("70000"),
                        reason = null,
                    ),
                )
            Mockito
                .`when`(
                    orderBuilder.buildFuturesOrderParams(
                        Mockito.anyString(),
                        anyDirection(),
                        anyBigDecimal(),
                        anyBigDecimal(),
                        anyPositionSizeResult(),
                        anyBigDecimal(),
                        Mockito.anyInt(),
                    ),
                ).thenReturn(
                    OrderParams(
                        direction = PositionDirection.LONG,
                        quantity = 3,
                        stopLossPrice = BigDecimal("91500"),
                        takeProfitPrice = BigDecimal("93000"),
                        marginRequired = BigDecimal("1000"),
                        liquidationPrice = BigDecimal("70000"),
                        leverage = BigDecimal("2.0"),
                        goPerContract = BigDecimal("1000"),
                        stopLossPoints = 50,
                        trailingStopPrice = BigDecimal("91500"),
                    ),
                )
            Mockito
                .`when`(
                    orderOutboxService.placeOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.nullable(BigDecimal::class.java),
                        Mockito.anyString(),
                        Mockito.nullable(Long::class.java),
                        Mockito.nullable(String::class.java),
                        Mockito.nullable(BigDecimal::class.java),
                        Mockito.nullable(String::class.java),
                        Mockito.nullable(Long::class.java),
                    ),
                ).thenReturn(
                    OrderOutboxService.PlaceOrderResult(
                        outboxId = UUID.randomUUID(),
                        alorOrderId = "ord-entry-1",
                        success = true,
                    ),
                )
            Mockito
                .`when`(positionRepo.save(anyPosition()))
                .thenAnswer { inv ->
                    val p = inv.getArgument<Position>(0)
                    savedPositions += p
                    p
                }
            Mockito
                .`when`(netEvGate.check(any(), any(), any()))
                .thenReturn(NetEvGate.GateResult.Pass)
        }
    }

    private fun stubEntryResolution(
        pos: Position,
        outboxRow: OrderOutbox,
        execution: AlorClient.OrderExecution,
    ) {
        runBlocking {
            Mockito
                .`when`(positionRepo.findByStatus(PositionStatus.OPEN))
                .thenReturn(listOf(pos))
            Mockito
                .`when`(orderOutboxRepo.findLatestByPositionId(1L))
                .thenReturn(outboxRow)
            Mockito
                .`when`(alorClient.verifyOrder(Mockito.anyString(), Mockito.nullable(BigDecimal::class.java), Mockito.anyString()))
                .thenReturn(execution)
            Mockito
                .`when`(positionRepo.save(anyPosition()))
                .thenAnswer { inv -> inv.getArgument<Position>(0) }
        }
    }

    private fun awaitUntil(
        timeoutMs: Long = 8000,
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

    @Test
    fun `partial entry fill creates pendingEntry position with actual qty`() {
        stubEntryAllowed()
        runBlocking {
            Mockito
                .`when`(
                    alorClient.verifyOrder(
                        Mockito.anyString(),
                        Mockito.nullable(BigDecimal::class.java),
                        Mockito.anyString(),
                    ),
                ).thenReturn(
                    AlorClient.OrderExecution(
                        status = "PARTIALLY_FILLED",
                        filledQuantity = 2,
                        avgPrice = BigDecimal("92000"),
                    ),
                )
        }

        val testEngine =
            OrderExecutionEngine(
                alorClient = alorClient,
                orderOutboxService = orderOutboxService,
                orderOutboxRepo = orderOutboxRepo,
                positionRepo = positionRepo,
                alorConfig = alorConfig,
                objectMapper = objectMapper,
                tradeEventService = tradeEventService,
                meterRegistry = meterRegistry,
                pnlCalculator = PnlCalculator.futures { _ -> instrumentsConfig.pointValue("Si") },
                instrumentFilter = { it.instrumentType == InstrumentType.FUTURES },
                metricPrefix = "futures",
                onEntryOpened = { eventPublisher.publishPositionOpened(it) },
                onPositionClosed = { eventPublisher.publishPositionClosed(it) },
                protectionOrdersEnabled = false,
                portfolioResolver = { tradingAccountService.portfolioOf(it) },
            )

        val result =
            runBlocking {
                testEngine.placeEntryOrder(
                    "Si",
                    PositionDirection.LONG,
                    3,
                    BigDecimal("92000"),
                ) { orderId, pending, fillPrice, qty ->
                    Position(
                        ticker = "Si",
                        direction = PositionDirection.LONG,
                        quantity = qty,
                        entryPrice = fillPrice,
                        currentPrice = fillPrice,
                        stopLoss = BigDecimal("91500"),
                        takeProfit = BigDecimal("93000"),
                        instrumentType = InstrumentType.FUTURES,
                        pendingEntry = pending,
                        alorOrderId = orderId,
                    )
                }
            }

        assertNull(result)

        val partial = savedPositions.first { it.pendingEntry }
        assertEquals(2, partial.quantity)
        assertEquals("ord-entry-1", partial.alorOrderId)
        assertEquals(InstrumentType.FUTURES, partial.instrumentType)
        runBlocking {
            verify(eventPublisher, Mockito.never()).publishPositionOpened(anyPosition())
            verify(tradeEventService, Mockito.never()).recordPositionOpened(anyPosition())
        }
    }

    @Test
    fun `stale market data blocks futures entry`() {
        Mockito.`when`(tradingGate.isTradingEnabled()).thenReturn(true)
        Mockito.`when`(instrumentsConfig.isFutures("Si")).thenReturn(true)
        Mockito.`when`(marketDataGate.isPriceDataFresh("Si")).thenReturn(false)

        service.onStrategyGenerated(StrategyGeneratedEvent(signal()))

        awaitUntil {
            meterRegistry
                .counter("futures.entry.rejected", Tags.of("ticker", "Si", "reason", "STALE_DATA"))
                .count() == 1.0
        }
        assertTrue(savedPositions.isEmpty())
        runBlocking {
            Mockito.verify(futuresRiskEngine, Mockito.never()).canEnter(anyEntryRequest())
        }
    }

    @Test
    fun `pending entry resolved to full fill`() {
        val pos = entryPosition()
        stubEntryResolution(
            pos,
            outbox("ord-entry-1", "idem-1", qty = 3),
            AlorClient.OrderExecution(status = "FILLED", filledQuantity = 3, avgPrice = BigDecimal("92100")),
        )

        service.onPriceChanged(PriceChangedEvent("Si", BigDecimal("92000")))
        awaitUntil { !pos.pendingEntry }

        assertEquals(3, pos.quantity)
        assertEquals("ord-entry-1", pos.alorOrderId)
        assertEquals(0, BigDecimal("92100").compareTo(pos.entryPrice))
        runBlocking {
            verify(eventPublisher, Mockito.timeout(3000)).publishPositionOpened(anyPosition())
            verify(tradeEventService, Mockito.timeout(3000)).recordPositionOpened(anyPosition())
        }
    }

    @Test
    fun `partial entry with stale remainder cancels order and finalizes`() {
        val pos = entryPosition()
        val staleOutbox = outbox("ord-entry-1", "idem-1", qty = 3, createdAt = LocalDateTime.now().minusSeconds(40))
        stubEntryResolution(
            pos,
            staleOutbox,
            AlorClient.OrderExecution(status = "PARTIALLY_FILLED", filledQuantity = 2, avgPrice = BigDecimal("92000")),
        )
        runBlocking {
            Mockito
                .`when`(alorClient.cancelOrder("ord-entry-1", "idem-1", alorConfig.portfolio))
                .thenReturn(AlorClient.CancelResult.CONFIRMED)
        }

        service.onPriceChanged(PriceChangedEvent("Si", BigDecimal("92000")))
        awaitUntil { !pos.pendingEntry }

        assertEquals(2, pos.quantity)
        runBlocking {
            verify(alorClient, Mockito.timeout(3000)).cancelOrder(eq("ord-entry-1"), eq("idem-1"), eq(alorConfig.portfolio))
            verify(eventPublisher, Mockito.timeout(3000)).publishPositionOpened(anyPosition())
        }
    }

    @Test
    fun `partial entry before cancel threshold stays pending without cancel`() {
        val pos = entryPosition()
        val freshOutbox = outbox("ord-entry-1", "idem-1", qty = 3, createdAt = LocalDateTime.now().minusSeconds(5))
        stubEntryResolution(
            pos,
            freshOutbox,
            AlorClient.OrderExecution(status = "PARTIALLY_FILLED", filledQuantity = 2, avgPrice = BigDecimal("92000")),
        )

        service.onPriceChanged(PriceChangedEvent("Si", BigDecimal("92000")))
        awaitUntil { pos.quantity == 2 && pos.pendingEntry }

        assertTrue(pos.pendingEntry)
        runBlocking {
            Mockito.verify(alorClient, Mockito.never()).cancelOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
            Mockito.verify(eventPublisher, Mockito.never()).publishPositionOpened(anyPosition())
        }
    }

    @Test
    fun `cancel rejection keeps entry pending for next cycle`() {
        val pos = entryPosition()
        val staleOutbox = outbox("ord-entry-1", "idem-1", qty = 3, createdAt = LocalDateTime.now().minusSeconds(40))
        stubEntryResolution(
            pos,
            staleOutbox,
            AlorClient.OrderExecution(status = "PARTIALLY_FILLED", filledQuantity = 2, avgPrice = BigDecimal("92000")),
        )
        runBlocking {
            Mockito
                .`when`(alorClient.cancelOrder("ord-entry-1", "idem-1", alorConfig.portfolio))
                .thenReturn(AlorClient.CancelResult.REJECTED)
        }

        service.onPriceChanged(PriceChangedEvent("Si", BigDecimal("92000")))
        awaitUntil { pos.quantity == 2 }

        assertTrue(pos.pendingEntry)
        runBlocking {
            Mockito.verify(eventPublisher, Mockito.never()).publishPositionOpened(anyPosition())
        }
    }
}
