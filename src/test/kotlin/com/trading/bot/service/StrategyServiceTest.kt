package com.trading.bot.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.agent.PerformanceFeedbackAgent
import com.trading.bot.application.StrategyResult
import com.trading.bot.application.StrategyRunner
import com.trading.bot.application.advisor.LlmAdvisor
import com.trading.bot.application.strategy.DiscretionaryStrategy
import com.trading.bot.client.AlorClient
import com.trading.bot.client.MoexClient
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.advisor.AdvisorVerdict
import com.trading.bot.domain.signal.Signal
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.Strategy
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.StrategyRepository
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit-тесты оркестратора [StrategyService] на замоканных зависимостях:
 *
 * - emergency stop полностью пропускает цикл (лок не берётся, LLM/рынок не трогаются);
 * - цикл выполняется внутри distributed lock и публикует метрики цикла;
 * - устаревшие/пустые свечи тикера отсекаются до стратегий (STALE_CANDLES).
 *
 * Планировщик не запускается: вызывается публичная точка входа [StrategyService.runStrategyCycle],
 * а завершение фоновой корутины синхронизируется CountDownLatch'ем.
 */
class StrategyServiceTest {
    private val tradingConfig =
        TradingConfig().apply {
            tickers = listOf("SBER")
            timeframes = listOf("MINUTE_10")
        }
    private val riskConfig = RiskConfig()
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val moexClient = Mockito.mock(MoexClient::class.java)
    private val strategyRunner = Mockito.mock(StrategyRunner::class.java)
    private val discretionaryStrategy = Mockito.mock(DiscretionaryStrategy::class.java)
    private val llmAdvisor = Mockito.mock(LlmAdvisor::class.java)
    private val feedbackAgent = Mockito.mock(PerformanceFeedbackAgent::class.java)
    private val adaptiveRisk = Mockito.mock(AdaptiveRiskService::class.java)
    private val redis = Mockito.mock(ReactiveRedisCacheService::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val instrumentsConfig = InstrumentsConfig()
    private val strategyRepo = Mockito.mock(StrategyRepository::class.java)
    private val candleRepo = Mockito.mock(CandleRepository::class.java)
    private val eventPublisher = Mockito.mock(TradingEventPublisher::class.java)
    private val settingsService = Mockito.mock(SettingsService::class.java)
    private val paperTradingService = Mockito.mock(PaperTradingService::class.java)
    private val emergencyStopService = Mockito.mock(EmergencyStopService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val objectMapper = jacksonObjectMapper()
    private val lockService = Mockito.mock(DistributedLockService::class.java)
    private val lockConfig = DistributedLockConfig()
    private val liveFrozenStrategyResolver = Mockito.mock(LiveFrozenStrategyResolver::class.java)

    private val service =
        StrategyService(
            tradingConfig = tradingConfig,
            riskConfig = riskConfig,
            alorClient = alorClient,
            moexClient = moexClient,
            strategyRunner = strategyRunner,
            discretionaryStrategy = discretionaryStrategy,
            llmAdvisor = llmAdvisor,
            feedbackAgent = feedbackAgent,
            adaptiveRisk = adaptiveRisk,
            redis = redis,
            candleCache = candleCache,
            instrumentsConfig = instrumentsConfig,
            strategyRepo = strategyRepo,
            candleRepo = candleRepo,
            eventPublisher = eventPublisher,
            settingsService = settingsService,
            paperTradingService = paperTradingService,
            emergencyStopService = emergencyStopService,
            meterRegistry = meterRegistry,
            objectMapper = objectMapper,
            distributedLockService = lockService,
            distributedLockConfig = lockConfig,
            liveFrozenStrategyResolver = liveFrozenStrategyResolver,
        )

    @Test
    fun `emergency stop skips the cycle before taking the lock`() {
        Mockito.`when`(emergencyStopService.isActive()).thenReturn(true)
        Mockito.`when`(emergencyStopService.lastReason()).thenReturn("manual stop")

        service.runStrategyCycle()

        assertEquals(
            1.0,
            meterRegistry.counter("strategy.skipped", Tags.of("reason", "EMERGENCY_STOP")).count(),
        )
        Mockito.verifyNoInteractions(lockService)
        Mockito.verifyNoInteractions(strategyRunner)
    }

    @Test
    fun `cycle runs inside distributed lock and records latency`() {
        val done = CountDownLatch(1)
        stubLock { done.countDown() }
        tradingConfig.tickers = emptyList()
        Mockito.`when`(settingsService.getSettings()).thenReturn(BotSettings(timeframes = listOf("MINUTE_10")))

        service.run()

        assertTrue(done.await(10, TimeUnit.SECONDS), "cycle must complete inside the lock")
        assertEquals(1.0, meterRegistry.counter("strategy.cycle").count())
        assertEquals(1L, meterRegistry.timer("strategy.latency").count())
    }

    @Test
    fun `ticker without fresh candles is skipped before strategies run`() {
        val done = CountDownLatch(1)
        stubLock { done.countDown() }
        Mockito.`when`(settingsService.getSettings()).thenReturn(BotSettings(timeframes = listOf("MINUTE_10")))
        stubEmptyMarketData()

        service.run()

        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(
            1.0,
            meterRegistry.counter("strategy.skipped", Tags.of("ticker", "SBER", "reason", "STALE_CANDLES")).count(),
        )
        Mockito.verifyNoInteractions(strategyRunner)
        Mockito.verifyNoInteractions(strategyRepo)
        Mockito.verifyNoInteractions(llmAdvisor)
    }

    /**
     * Пустой рынок: ни кэш свечей, ни БД, ни MOEX не отдают данных.
     * Suspend-методы стабуются явно (их JVM-возврат `Object` → дефолт Mockito был бы
     * `null`, а не пустой список), а для non-null параметров передаются реальные
     * dummy-значения — null в suspend-вызове Kotlin отбрасывает сам.
     */
    private fun stubEmptyMarketData() {
        Mockito
            .`when`(candleCache.getRecentCandles(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(emptyList())
        runBlocking {
            // Boolean-возврат suspend-функции на JVM — Object: без явного стаба
            // Mockito вернул бы null и Kotlin упал бы на unboxing.
            Mockito.`when`(adaptiveRisk.shouldPauseTrading(Mockito.anyString())).thenReturn(false)
            Mockito
                .`when`(
                    candleRepo.findByTickerAndTimeframeAndTimeBetween(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        anyLocalDateTime(),
                        anyLocalDateTime(),
                    ),
                ).thenReturn(emptyList())
            Mockito
                .`when`(
                    moexClient.getCandles(
                        Mockito.anyString(),
                        anyLocalDateTime(),
                    ),
                ).thenReturn(emptyList())
        }
    }

    /** Helper-паттерн Mockito: матчер для LocalDateTime + реальный dummy. */
    private fun anyLocalDateTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return LocalDateTime.of(2026, 1, 1, 0, 0)
    }

    @Test
    fun `full cycle publishes a buy signal and persists the strategy row`() {
        val done = CountDownLatch(1)
        stubLock { done.countDown() }
        Mockito.`when`(settingsService.getSettings()).thenReturn(BotSettings(timeframes = listOf("MINUTE_10")))
        riskConfig.perTickerRegimeEnabled = false
        stubFreshMarket()
        stubStrategyPipeline()
        val logCapture = ListAppender<ILoggingEvent>().apply { start() }
        val serviceLogger = LoggerFactory.getLogger(StrategyService::class.java) as Logger
        serviceLogger.addAppender(logCapture)
        try {
            service.run()
            assertTrue(done.await(10, TimeUnit.SECONDS), "cycle must complete")
        } finally {
            serviceLogger.detachAppender(logCapture)
        }

        val errors =
            logCapture.list
                .filter { it.level == Level.ERROR }
                .map { event -> event.formattedMessage + " :: " + (event.throwableProxy?.message ?: "") }
        assertTrue(errors.isEmpty(), "cycle must not fail, errors: $errors")
        assertEquals(
            1.0,
            meterRegistry
                .counter(
                    "strategy.saved",
                    Tags.of("ticker", "SBER", "timeframe", "MINUTE_10", "action", "BUY", "strategy", "TEST_STRATEGY"),
                ).count(),
        )
        runBlocking {
            Mockito.verify(strategyRepo).save(anyStrategy())
            Mockito.verify(redis).saveStrategy(anyStrategy())
        }
        Mockito.verify(eventPublisher).publishStrategyGenerated(anySignal())
    }

    /** Свежие свечи в кэше + валидный снапшот рынка. */
    private fun stubFreshMarket() {
        Mockito
            .`when`(candleCache.getRecentCandles(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(freshCandles())
        runBlocking {
            Mockito
                .`when`(alorClient.getMarketSnapshot("SBER"))
                .thenReturn(
                    MarketSnapshot(
                        ticker = "SBER",
                        currentPrice = BigDecimal("100"),
                        bid = BigDecimal("99.95"),
                        ask = BigDecimal("100.05"),
                    ),
                )
        }
    }

    /** Стратегии, риск и советник отдают согласованный BUY-сигнал. */
    private fun stubStrategyPipeline() {
        val decision =
            StrategyDecision(
                action = StrategyAction.BUY,
                targetPrice = BigDecimal("100"),
                signalStrength = 0.8,
                reasoning = "unit-test signal",
            )
        runBlocking {
            Mockito.`when`(adaptiveRisk.shouldPauseTrading("SBER")).thenReturn(false)
            Mockito.`when`(feedbackAgent.generateFeedback("SBER")).thenReturn(null)
            Mockito.`when`(adaptiveRisk.getAdaptiveConfidenceThreshold("SBER")).thenReturn(0.5)
            Mockito
                .`when`(strategyRunner.runAll(anyContext()))
                .thenReturn(
                    StrategyResult(
                        winnerId = "TEST_STRATEGY",
                        decision = decision,
                        all = mapOf("TEST_STRATEGY" to decision),
                    ),
                )
            Mockito
                .`when`(
                    llmAdvisor.advise(
                        anyContext(),
                        anyDecision(),
                        Mockito.anyDouble(),
                        Mockito.anyString(),
                    ),
                ).thenReturn(AdvisorVerdict.NEUTRAL)
            Mockito.`when`(strategyRepo.save(anyStrategy())).thenAnswer { it.getArgument<Strategy>(0) }
        }
    }

    /** 60 свежих 10-минутных свечей (последняя — «сейчас», иначе тикер будет пропущен). */
    private fun freshCandles(): List<Candle> {
        val now = LocalDateTime.now(ZoneId.of("Europe/Moscow")).withSecond(0).withNano(0)
        return (59 downTo 0).map { minutesAgo ->
            val price = BigDecimal("100")
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = price,
                highPrice = price.add(BigDecimal("0.5")),
                lowPrice = price.subtract(BigDecimal("0.5")),
                closePrice = price,
                volume = 1_000,
                time = now.minusMinutes(minutesAgo * 10L),
            )
        }
    }

    private fun anyContext(): StrategyContext {
        Mockito.any(StrategyContext::class.java)
        return StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(currentPrice = BigDecimal("100")),
            candles = emptyList(),
            indicators = null,
            cycleId = "cycle-1",
        )
    }

    private fun anyDecision(): StrategyDecision {
        Mockito.any(StrategyDecision::class.java)
        return StrategyDecision.hold(BigDecimal("100"), "matcher")
    }

    private fun anyStrategy(): Strategy {
        Mockito.any(Strategy::class.java)
        return Strategy(
            ticker = "SBER",
            action = StrategyAction.HOLD,
            targetPrice = BigDecimal("100"),
            quantity = 0,
            signalStrength = 0.0,
            reasoning = "matcher",
            cycleId = "cycle-1",
            validUntil = LocalDateTime.now(),
        )
    }

    private fun anySignal(): Signal {
        Mockito.any(Signal::class.java)
        return Signal(
            ticker = "SBER",
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("100"),
            signalStrength = 0.8,
            reasoning = "matcher",
            timeframe = "MINUTE_10",
            cycleId = "cycle-1",
        )
    }

    /**
     * Подменяет distributed lock: блок выполняется синхронно внутри замоканного
     * [DistributedLockService.runExclusive], после завершения вызывается [onCompleted].
     */
    @Test
    fun `second cycle is skipped while the first is still in flight`() {
        // Redis-лок по умолчанию выключен: без in-process guard два цикла могли
        // идти параллельно (docs/28 §3.1 №36).
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        runBlocking {
            Mockito
                .`when`(lockService.runExclusive(Mockito.anyString(), Mockito.anyLong(), Mockito.anyBoolean(), anyBlock()))
                .thenAnswer { invocation ->
                    val block = invocation.getArgument<suspend () -> Unit>(3)
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    runBlocking { block() }
                    DistributedLockService.LockExecutionResult.COMPLETED
                }
        }

        service.run()
        assertTrue(started.await(10, TimeUnit.SECONDS), "first cycle must start")
        service.run()

        assertEquals(1.0, meterRegistry.counter("strategy.skipped", "reason", "IN_FLIGHT").count())
        release.countDown()
    }

    private fun stubLock(onCompleted: () -> Unit) {
        runBlocking {
            Mockito
                .`when`(lockService.runExclusive(Mockito.anyString(), Mockito.anyLong(), Mockito.anyBoolean(), anyBlock()))
                .thenAnswer { invocation ->
                    val block = invocation.getArgument<suspend () -> Unit>(3)
                    runBlocking { block() }
                    onCompleted()
                    DistributedLockService.LockExecutionResult.COMPLETED
                }
        }
    }

    /** Helper-паттерн Mockito: матчер для non-null suspend-лямбды + реальный no-op. */
    private fun anyBlock(): suspend () -> Unit {
        Mockito.any<suspend () -> Unit>()
        return {}
    }
}
