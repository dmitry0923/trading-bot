package com.trading.bot.service

import com.trading.bot.application.MarketDataGate
import com.trading.bot.application.TradingGate
import com.trading.bot.application.decision.DecisionEngine
import com.trading.bot.application.decision.ExecutionGateway
import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorWebSocketClient
import com.trading.bot.client.QuoteTick
import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsConnectionEvent
import com.trading.bot.client.WsConnectionStatus
import com.trading.bot.client.WsStream
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.signal.Signal
import com.trading.bot.event.StrategyGeneratedEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.model.StrategyAction
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TradingBotService] — WS-котировки и REST-fallback:
 *
 * - обработка [QuoteTick] (OBI и спред только при полных полях, метрика задержки,
 *   публикация цены, запись микроструктуры);
 * - сброс `lastWsTickAt` по DISCONNECTED-событию менеджера WS;
 * - [TradingBotService.pollMarketData]: пропуск «свежих» WS-тикеров, ошибка/`null`
 *   от REST-пола и успешная публикация;
 * - guard-ветви [TradingBotService.onStrategyGenerated].
 */
class TradingBotServiceQuoteAndPollBranchesTest {
    private val tradingConfig =
        TradingConfig().apply {
            wsQuotesEnabled = true
            tickers = listOf("SBER")
            monitorIntervalMs = 1_000
        }
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val alorWsClient = Mockito.mock(AlorWebSocketClient::class.java)
    private val webSocketManager = Mockito.mock(WebSocketManager::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val redis = Mockito.mock(ReactiveRedisCacheService::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val eventPublisher = Mockito.mock(TradingEventPublisher::class.java)
    private val tradingGate = Mockito.mock(TradingGate::class.java)
    private val marketDataGate = Mockito.mock(MarketDataGate::class.java)
    private val decisionEngine = Mockito.mock(DecisionEngine::class.java)
    private val distributedLockService = Mockito.mock(DistributedLockService::class.java)
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)
    private val instrumentsConfig = Mockito.mock(com.trading.bot.config.InstrumentsConfig::class.java)
    private val microstructureRecorder = Mockito.mock(MicrostructureRecorder::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val quotes = Channel<QuoteTick>(Channel.UNLIMITED)
    private val wsEvents = Channel<WsConnectionEvent>(Channel.UNLIMITED)

    private lateinit var service: TradingBotService

    @BeforeEach
    fun setUp() {
        runBlocking {
            Mockito.`when`(tradingAccountService.findEnabled()).thenReturn(emptyList())
            Mockito.`when`(alorWsClient.subscribeToOrders(Mockito.anyString())).thenReturn(emptyFlow())
            Mockito.`when`(alorWsClient.subscribeToQuotes(Mockito.anyList())).thenReturn(quotes.receiveAsFlow())
            Mockito.`when`(webSocketManager.events).thenReturn(wsEvents.receiveAsFlow())
            Mockito.`when`(positionRepo.findOpenTickersDistinct()).thenReturn(emptyList())
        }
        service =
            TradingBotService(
                tradingConfig,
                alorClient,
                alorWsClient,
                webSocketManager,
                orderOutboxService,
                redis,
                RiskConfig(),
                positionRepo,
                orderOutboxRepo,
                AlorConfig(),
                jacksonObjectMapper(),
                tradeEventService,
                eventPublisher,
                tradingGate,
                marketDataGate,
                decisionEngine,
                distributedLockService,
                DistributedLockConfig(),
                tradingAccountService,
                instrumentsConfig,
                meterRegistry,
                EntryLeaseRecoveryGate(meterRegistry),
                microstructureRecorder,
            )
    }

    @AfterEach
    fun tearDown() {
        service.close()
        quotes.close()
        wsEvents.close()
    }

    private fun awaitUntil(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        runBlocking {
            withTimeout(timeoutMs) {
                while (!condition()) delay(10)
            }
        }
    }

    @Test
    fun `full quote tick records obi spread price and microstructure`() {
        quotes.trySend(
            QuoteTick(
                ticker = "SBER",
                price = BigDecimal("100.5"),
                bid = BigDecimal("100.4"),
                ask = BigDecimal("100.6"),
                bidSize = 10,
                askSize = 20,
            ),
        )

        awaitUntil { Mockito.mockingDetails(microstructureRecorder).invocations.isNotEmpty() }
        Mockito.verify(marketDataGate).updateObi(Mockito.anyString(), anyBigDecimal())
        Mockito.verify(marketDataGate).recordSpread("SBER", BigDecimal("100.4"), BigDecimal("100.6"))
        Mockito.verify(eventPublisher).publishPriceChanged("SBER", BigDecimal("100.5"))
        Mockito.verify(microstructureRecorder).record(any())
    }

    @Test
    fun `tick without book sizes skips obi and spread`() {
        quotes.trySend(QuoteTick(ticker = "SBER", price = BigDecimal("101")))

        awaitUntil {
            Mockito
                .mockingDetails(eventPublisher)
                .invocations
                .any { it.method.name == "publishPriceChanged" }
        }
        Mockito.verify(marketDataGate, never()).updateObi(Mockito.anyString(), anyBigDecimal())
        Mockito.verify(marketDataGate, never()).recordSpread(any(), any(), any())
        Mockito.verify(eventPublisher).publishPriceChanged("SBER", BigDecimal("101"))
    }

    @Test
    fun `disconnect event re-enables the rest fallback for the ticker`() {
        val polls = AtomicInteger(0)
        runBlocking {
            Mockito
                .`when`(
                    distributedLockService.runExclusive(
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.anyBoolean(),
                        org.mockito.kotlin.any<(suspend () -> Unit)>(),
                    ),
                ).thenAnswer { invocation ->
                    val block = invocation.getArgument<suspend () -> Unit>(3)
                    runBlocking { block() }
                    polls.incrementAndGet()
                }
            Mockito.`when`(positionRepo.findOpenTickersDistinct()).thenReturn(listOf("SBER"))
            Mockito.`when`(alorClient.getLastPrice("SBER")).thenReturn(BigDecimal("100"))
        }
        quotes.trySend(QuoteTick(ticker = "SBER", price = BigDecimal("100")))
        awaitUntil { meterRegistry.find("alor.ws.message.lag").tag("ticker", "SBER").timer() != null }
        // Свежий WS-тикер не опрашивается REST-полом.
        service.pollMarketData()
        awaitUntil { polls.get() == 1 }
        runBlocking { Mockito.verify(alorClient, never()).getLastPrice("SBER") }

        // После DISCONNECTED отметка стирается → полл снова работает.
        wsEvents.trySend(WsConnectionEvent(WsStream.QUOTES, WsConnectionStatus.DISCONNECTED))
        runBlocking { delay(50) }
        service.pollMarketData()
        awaitUntil { polls.get() == 2 }
        runBlocking { Mockito.verify(alorClient).getLastPrice("SBER") }
        Mockito.verify(marketDataGate).recordRestPollSuccess("SBER")
    }

    @Test
    fun `rest fallback skips a null price and counts the poll error`() {
        val done = CountDownLatch(1)
        runBlocking {
            Mockito
                .`when`(
                    distributedLockService.runExclusive(
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.anyBoolean(),
                        org.mockito.kotlin.any<(suspend () -> Unit)>(),
                    ),
                ).thenAnswer { invocation ->
                    val block = invocation.getArgument<suspend () -> Unit>(3)
                    runBlocking { block() }
                    done.countDown()
                }
            Mockito.`when`(positionRepo.findOpenTickersDistinct()).thenReturn(listOf("SBER", "GAZP"))
            Mockito.`when`(alorClient.getLastPrice("SBER")).thenReturn(null)
            Mockito.`when`(alorClient.getLastPrice("GAZP")).thenThrow(IllegalStateException("quote down"))
        }

        service.pollMarketData()

        assertEquals(true, done.await(5, TimeUnit.SECONDS))
        Mockito.verify(eventPublisher, never()).publishPriceChanged(any(), any())
        assertEquals(1.0, meterRegistry.counter("bot.price.poll.error", "ticker", "GAZP").count())
    }

    @Test
    fun `strategy generated guards reject futures hold and a disabled gate`() {
        Mockito.`when`(instrumentsConfig.isFutures("SBER")).thenReturn(true)
        service.onStrategyGenerated(StrategyGeneratedEvent(signal(StrategyAction.BUY)))
        runBlocking { Mockito.verify(decisionEngine, never()).openPosition(any(), any()) }

        Mockito.`when`(instrumentsConfig.isFutures("SBER")).thenReturn(false)
        service.onStrategyGenerated(StrategyGeneratedEvent(signal(StrategyAction.HOLD)))
        runBlocking { Mockito.verify(decisionEngine, never()).openPosition(any(), any()) }

        Mockito.`when`(tradingGate.isTradingEnabled()).thenReturn(false)
        service.onStrategyGenerated(StrategyGeneratedEvent(signal(StrategyAction.BUY)))
        runBlocking { Mockito.verify(decisionEngine, never()).openPosition(any(), any()) }
    }

    @Test
    fun `strategy generated signal reaches the decision engine`() {
        Mockito.`when`(instrumentsConfig.isFutures("SBER")).thenReturn(false)
        Mockito.`when`(tradingGate.isTradingEnabled()).thenReturn(true)

        service.onStrategyGenerated(StrategyGeneratedEvent(signal(StrategyAction.BUY)))

        runBlocking {
            Mockito
                .verify(decisionEngine, Mockito.timeout(3000))
                .openPosition(anySignalArg(), anyGateway())
        }
    }

    @Test
    fun `decision engine failure is isolated by the coroutine`() {
        Mockito.`when`(instrumentsConfig.isFutures("SBER")).thenReturn(false)
        Mockito.`when`(tradingGate.isTradingEnabled()).thenReturn(true)
        runBlocking {
            Mockito
                .`when`(decisionEngine.openPosition(anySignalArg(), anyGateway()))
                .thenThrow(IllegalStateException("engine down"))
        }

        service.onStrategyGenerated(StrategyGeneratedEvent(signal(StrategyAction.SELL)))

        runBlocking { kotlinx.coroutines.delay(150) }
        runBlocking {
            Mockito
                .verify(decisionEngine, Mockito.timeout(3000))
                .openPosition(anySignalArg(), anyGateway())
        }
    }

    @Test
    fun `reconcile pending orders runs a clean pass and releases the latch`() {
        val done = CountDownLatch(1)
        runBlocking {
            Mockito
                .`when`(
                    distributedLockService.runExclusive(
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.anyBoolean(),
                        org.mockito.kotlin.any<(suspend () -> Unit)>(),
                    ),
                ).thenAnswer { invocation ->
                    val block = invocation.getArgument<suspend () -> Unit>(3)
                    runBlocking { block() }
                    done.countDown()
                }
            Mockito
                .`when`(positionRepo.findByStatus(com.trading.bot.model.PositionStatus.OPEN))
                .thenReturn(emptyList())
        }

        service.reconcilePendingOrders()

        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `reconcile pending orders survives a failed reconciliation pass`() {
        val done = CountDownLatch(1)
        runBlocking {
            Mockito
                .`when`(
                    distributedLockService.runExclusive(
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.anyBoolean(),
                        org.mockito.kotlin.any<(suspend () -> Unit)>(),
                    ),
                ).thenAnswer { invocation ->
                    val block = invocation.getArgument<suspend () -> Unit>(3)
                    runBlocking { block() }
                    done.countDown()
                }
            Mockito
                .`when`(positionRepo.findByStatus(com.trading.bot.model.PositionStatus.OPEN))
                .thenThrow(IllegalStateException("db down"))
        }

        service.reconcilePendingOrders()

        assertTrue(done.await(5, TimeUnit.SECONDS), "ошибка прохода не должна вешать планировщик")
    }

    @Test
    fun `force close all returns zero without open stocks`(): Unit =
        runBlocking {
            Mockito.`when`(positionRepo.findOpenStocks()).thenReturn(emptyList())

            assertEquals(0, service.forceCloseAll())
        }

    @Test
    fun `force close all swallows a quote failure and still reports the count`(): Unit =
        runBlocking {
            Mockito.`when`(positionRepo.findOpenStocks()).thenReturn(
                listOf(
                    com.trading.bot.model.entity.Position(
                        id = 1L,
                        ticker = "SBER",
                        direction = com.trading.bot.model.PositionDirection.LONG,
                        quantity = 1,
                        entryPrice = BigDecimal("100"),
                        status = com.trading.bot.model.PositionStatus.OPEN,
                    ),
                ),
            )
            Mockito.`when`(alorClient.getLastPrice("SBER")).thenThrow(IllegalStateException("quote down"))

            assertEquals(1, service.forceCloseAll(), "позиция считается даже при ошибке цены")
        }

    private fun anySignalArg(): Signal {
        Mockito.any(Signal::class.java)
        return signal(StrategyAction.BUY)
    }

    private fun anyGateway(): ExecutionGateway {
        Mockito.any(ExecutionGateway::class.java)
        return ExecutionGateway { _, _, _, _, _, _, _ -> null }
    }

    private fun signal(action: StrategyAction): Signal =
        Signal(
            ticker = "SBER",
            action = action,
            targetPrice = BigDecimal("100"),
            signalStrength = 0.8,
            reasoning = "unit",
            timeframe = "MINUTE_10",
            cycleId = "cycle-1",
        )

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }
}
