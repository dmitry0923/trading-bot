package com.trading.bot.service

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ветви [StrategyService.processTicker] / [StrategyService.loadCandles], не покрытые
 * основным [StrategyServiceTest] (скан-19: 58/146 ветвей): причины пропуска тикера
 * (пауза adaptive risk, нет снапшота, пауза Meta-Agent, режимный блок), VETO советника,
 * staleness-гейт после LLM (возраст/отклонение цены/спред/нет свежего снапшота),
 * frozen-порог, HOLD без записи в Redis, A/B-эксперимент и shadow-исполнение,
 * ошибки feedback/стратегий и три источника свечей (кэш → БД → MOEX).
 */
class StrategyServiceCycleBranchesTest {
    private val tradingConfig =
        TradingConfig().apply {
            tickers = listOf("SBER")
            timeframes = listOf("MINUTE_10")
        }
    private val riskConfig = RiskConfig().apply { perTickerRegimeEnabled = false }
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

    // ---------------------------------------------------------------- helpers

    /** Прогоняет один цикл и ждёт его завершения (планировщик не запускается). */
    private fun runCycle() {
        val done = CountDownLatch(1)
        runBlocking {
            Mockito
                .`when`(
                    lockService.runExclusive(
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.anyBoolean(),
                        anyBlock(),
                    ),
                ).thenAnswer { invocation ->
                    val block = invocation.getArgument<suspend () -> Unit>(3)
                    runBlocking { block() }
                    done.countDown()
                    DistributedLockService.LockExecutionResult.COMPLETED
                }
        }
        service.run()
        assertTrue(done.await(15, TimeUnit.SECONDS), "cycle must complete")
    }

    private fun anyBlock(): suspend () -> Unit {
        Mockito.any<suspend () -> Unit>()
        return {}
    }

    private fun stubSettings(timeframes: List<String> = listOf("MINUTE_10")) {
        Mockito.`when`(settingsService.getSettings()).thenReturn(BotSettings(timeframes = timeframes))
    }

    /** Дефолтный «пустой» рынок: кэш/БД/MOEX без свечей, MOEX без ответа. */
    private fun stubEmptyCandles() {
        Mockito
            .`when`(candleCache.getRecentCandles(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(emptyList())
        runBlocking {
            Mockito
                .`when`(
                    candleRepo.findByTickerAndTimeframeAndTimeBetween(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        anyLocalDateTime(),
                        anyLocalDateTime(),
                    ),
                ).thenReturn(emptyList())
            Mockito.`when`(moexClient.getCandles(Mockito.anyString(), anyLocalDateTime())).thenReturn(emptyList())
        }
    }

    private fun anyLocalDateTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return LocalDateTime.of(2026, 1, 1, 0, 0)
    }

    /** Свежие свечи (последняя — «сейчас»), чтобы не отсеклись как STALE_CANDLES. */
    private fun freshCandles(
        ticker: String = "SBER",
        rising: Boolean = false,
        timeframe: String = "MINUTE_10",
    ): List<Candle> {
        val now = LocalDateTime.now(ZoneId.of("Europe/Moscow")).withSecond(0).withNano(0)
        return (59 downTo 0).map { barsAgo ->
            val offset = if (rising) BigDecimal(59 - barsAgo).multiply(BigDecimal("0.1")) else BigDecimal.ZERO
            val price = BigDecimal("100").add(offset)
            Candle(
                ticker = ticker,
                timeframe = timeframe,
                openPrice = price,
                highPrice = price.add(BigDecimal("0.5")),
                lowPrice = price.subtract(BigDecimal("0.5")),
                closePrice = price,
                volume = if (rising) 1_000L + (59 - barsAgo) else 1_000L,
                time = now.minusMinutes(barsAgo * 10L),
            )
        }
    }

    private fun snapshot(
        price: String = "100",
        bid: String? = "99.95",
        ask: String? = "100.05",
        timestamp: Instant = Instant.now(),
    ): MarketSnapshot =
        MarketSnapshot(
            ticker = "SBER",
            currentPrice = BigDecimal(price),
            bid = bid?.let(::BigDecimal),
            ask = ask?.let(::BigDecimal),
            timestamp = timestamp,
        )

    /** Успешный конвейер: BUY 0.8, советник нейтрален, стратегия сохраняется. */
    private fun stubStrategyPipeline(
        decision: StrategyDecision =
            StrategyDecision(
                action = StrategyAction.BUY,
                targetPrice = BigDecimal("100"),
                signalStrength = 0.8,
                reasoning = "unit-test signal",
            ),
        resultTransform: (StrategyResult) -> StrategyResult = { it },
    ) {
        val result =
            resultTransform(
                StrategyResult(
                    winnerId = "TEST_STRATEGY",
                    decision = decision,
                    all = mapOf("TEST_STRATEGY" to decision),
                ),
            )
        runBlocking {
            Mockito.`when`(adaptiveRisk.shouldPauseTrading(Mockito.anyString())).thenReturn(false)
            Mockito.`when`(adaptiveRisk.getAdaptiveConfidenceThreshold(Mockito.anyString())).thenReturn(0.5)
            Mockito.`when`(feedbackAgent.generateFeedback(Mockito.anyString(), Mockito.anyString())).thenReturn(null)
            Mockito.`when`(strategyRunner.runAll(anyContext())).thenReturn(result)
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
            Mockito.`when`(paperTradingService.isExperimentEnabled()).thenReturn(false)
        }
    }

    private fun stubMarketSnapshot(snapshot: MarketSnapshot?) {
        runBlocking {
            Mockito.`when`(alorClient.getMarketSnapshot("SBER")).thenReturn(snapshot)
        }
    }

    private fun stubCachedCandles(candles: List<Candle> = freshCandles()) {
        Mockito
            .`when`(candleCache.getRecentCandles(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(candles)
    }

    private fun strategySaved(ticker: String = "SBER") =
        meterRegistry
            .find("strategy.saved")
            .tag("ticker", ticker)
            .counter()
            ?.count() ?: 0.0

    private fun counter(
        name: String,
        vararg tags: String,
    ) = meterRegistry.counter(name, *tags).count()

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

    private fun feedback(pause: Boolean): PerformanceFeedbackAgent.StrategyFeedback =
        PerformanceFeedbackAgent.StrategyFeedback(
            ticker = "SBER",
            confidenceAdjustment = 0.0,
            slAdjustmentPercent = 0.0,
            tpAdjustmentPercent = 0.0,
            contextPrompt = "context",
            agentSpecificNotes = emptyMap(),
            shouldPauseTrading = pause,
            rawJson = "{}",
        )

    // ------------------------------------------------------------ skip paths

    @Test
    fun `adaptive risk pause skips the ticker before market data`() {
        stubSettings()
        stubEmptyCandles()
        runBlocking {
            Mockito.`when`(adaptiveRisk.shouldPauseTrading("SBER")).thenReturn(true)
        }

        runCycle()

        assertEquals(1.0, counter("strategy.skipped", "ticker", "SBER"))
        Mockito.verifyNoInteractions(strategyRunner)
        Mockito.verifyNoInteractions(strategyRepo)
    }

    @Test
    fun `missing or non positive snapshot is skipped`() {
        stubSettings()
        stubCachedCandles()
        stubStrategyPipeline()
        stubMarketSnapshot(null)

        runCycle()

        assertEquals(1.0, counter("strategy.skipped", "ticker", "SBER", "reason", "STALE_SNAPSHOT"))
        Mockito.verifyNoInteractions(strategyRunner)

        // Цена <= 0 — тот же путь (защита от «нулевого» снимка рынка).
        stubMarketSnapshot(snapshot(price = "0"))
        runCycle()
        assertEquals(2.0, counter("strategy.skipped", "ticker", "SBER", "reason", "STALE_SNAPSHOT"))
    }

    @Test
    fun `meta agent pause recommendation skips the ticker`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()
        runBlocking {
            Mockito.`when`(feedbackAgent.generateFeedback(Mockito.anyString(), Mockito.anyString())).thenReturn(feedback(pause = true))
        }

        runCycle()

        assertEquals(1.0, counter("strategy.pause", "ticker", "SBER"))
        Mockito.verifyNoInteractions(strategyRunner)
    }

    @Test
    fun `regime gate blocks entry on flat candles`() {
        riskConfig.perTickerRegimeEnabled = true
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()

        runCycle()

        assertEquals(1.0, counter("strategy.skipped", "ticker", "SBER", "reason", "REGIME_BLOCKED"))
        Mockito.verifyNoInteractions(strategyRunner)
    }

    @Test
    fun `passing regime is recorded and does not block the entry`() {
        riskConfig.perTickerRegimeEnabled = true
        stubSettings()
        stubCachedCandles(freshCandles(rising = true))
        stubMarketSnapshot(snapshot(price = "105"))
        stubStrategyPipeline(
            decision =
                StrategyDecision(
                    action = StrategyAction.BUY,
                    targetPrice = BigDecimal("105"),
                    signalStrength = 0.8,
                    reasoning = "trend",
                ),
        )

        runCycle()

        assertEquals(0.0, counter("strategy.skipped", "ticker", "SBER", "reason", "REGIME_BLOCKED"))
        assertEquals(1.0, strategySaved(), "проходящий режим не должен блокировать вход")
        assertTrue(
            meterRegistry.find("market.regime.level").tag("ticker", "SBER").gauge() != null,
            "уровень режима должен публиковаться в gauge",
        )
    }

    // ------------------------------------------------------------- veto/gates

    @Test
    fun `advisor veto converts the decision to hold and keeps redis untouched`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()
        runBlocking {
            Mockito
                .`when`(
                    llmAdvisor.advise(
                        anyContext(),
                        anyDecision(),
                        Mockito.anyDouble(),
                        Mockito.anyString(),
                    ),
                ).thenReturn(AdvisorVerdict.veto("critical exposure"))
        }

        runCycle()

        assertEquals(1.0, counter("advisor.blocked", "ticker", "SBER"))
        runBlocking {
            Mockito.verify(strategyRepo).save(anyStrategy())
            Mockito.verify(redis, Mockito.never()).saveStrategy(anyStrategy())
        }
        Mockito.verify(eventPublisher).publishStrategyGenerated(anySignal())
    }

    @Test
    fun `hold decision is persisted but not written to redis`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline(decision = StrategyDecision.hold(BigDecimal("100"), "no setup"))

        runCycle()

        assertEquals(1.0, strategySaved())
        runBlocking {
            Mockito.verify(strategyRepo).save(anyStrategy())
            Mockito.verify(redis, Mockito.never()).saveStrategy(anyStrategy())
        }
    }

    @Test
    fun `frozen confidence threshold overrides the adaptive one`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()
        Mockito
            .`when`(liveFrozenStrategyResolver.resolveActive("SBER"))
            .thenReturn(
                com.trading.bot.backtest.FrozenStrategy(
                    ticker = "SBER",
                    strategyVersion = "v1",
                    gitCommitSha = null,
                    slPercent = 0.02,
                    tpPercent = 0.06,
                    slPoints = null,
                    tpPoints = null,
                    confidenceThreshold = 0.9,
                    leverage = 1.0,
                    riskPerTradePercent = 1.0,
                    futuresMaxContractsPerPosition = null,
                ),
            )

        runCycle()

        // 0.8 < 0.9 (frozen) → вход снят в HOLD несмотря на adaptive 0.5.
        assertEquals(1.0, counter("strategy.low_confidence", "ticker", "SBER"))
        runBlocking { Mockito.verify(redis, Mockito.never()).saveStrategy(anyStrategy()) }
    }

    @Test
    fun `stale snapshot age after advisor downgrades the signal`() {
        tradingConfig.signalMaxAgeMs = 1_000
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot(timestamp = Instant.now().minusSeconds(60)))
        stubStrategyPipeline()

        runCycle()

        assertEquals(1.0, counter("strategy.signal_stale", "ticker", "SBER"))
        runBlocking { Mockito.verify(redis, Mockito.never()).saveStrategy(anyStrategy()) }
    }

    @Test
    fun `price deviation after advisor downgrades the signal`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot(price = "105"))
        stubStrategyPipeline()

        runCycle()

        // target 100 против свежих 105: ATR-гейт недоступен, тик-гейт и %-кап < 5.
        assertEquals(1.0, counter("strategy.signal_stale", "ticker", "SBER"))
    }

    @Test
    fun `wide spread after advisor downgrades the signal`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot(bid = "100", ask = "102"))
        stubStrategyPipeline()

        runCycle()

        assertEquals(1.0, counter("strategy.signal_stale", "ticker", "SBER"))
    }

    @Test
    fun `missing or crossed quotes after advisor downgrades the signal`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot(bid = "101", ask = "100"))
        stubStrategyPipeline()

        runCycle()
        assertEquals(0.0, counter("strategy.signal_stale", "ticker", "SBER"), "инвертированные котировки не считаются спредом")
        assertEquals(1.0, strategySaved())

        // Свежий снапшот вообще не пришёл — fail-closed (первый снимок есть, второй null).
        runBlocking {
            Mockito
                .`when`(alorClient.getMarketSnapshot("SBER"))
                .thenReturn(snapshot(bid = "101", ask = "100"), null)
        }
        runCycle()
        assertEquals(1.0, counter("strategy.signal_stale", "ticker", "SBER"))
    }

    @Test
    fun `atr based deviation gate limits the allowed price move`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot(price = "100.2"))
        stubStrategyPipeline(
            decision =
                StrategyDecision(
                    action = StrategyAction.BUY,
                    targetPrice = BigDecimal("100"),
                    signalStrength = 0.8,
                    reasoning = "atr gate",
                ),
        )
        Mockito.`when`(candleCache.calculateAtr("SBER", "MINUTE_10", riskConfig.futuresAtrStopPeriod)).thenReturn(BigDecimal("0.1"))

        runCycle()

        // ATR 0.1 × 0.25 = 0.025 < отклонения 0.2 → сигнал снимается.
        assertEquals(1.0, counter("strategy.signal_stale", "ticker", "SBER"))
    }

    // ------------------------------------------------------ experiment/shadow

    @Test
    fun `shadow experiment records variant but does not publish the signal`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()
        val variant = StrategyDecision(StrategyAction.SELL, BigDecimal("99"), 0.7, "variant")
        runBlocking {
            Mockito.`when`(paperTradingService.isExperimentEnabled()).thenReturn(true)
            Mockito.`when`(paperTradingService.inExperiment(Mockito.anyString())).thenReturn(true)
            Mockito.`when`(paperTradingService.isShadowExecution()).thenReturn(true)
            Mockito.`when`(paperTradingService.variantVersion()).thenReturn("v2")
            Mockito.`when`(discretionaryStrategy.produceVariant(anyContext(), Mockito.anyString())).thenReturn(variant)
            Mockito
                .`when`(paperTradingService.recordControlDecision(anyRisk(), Mockito.anyString(), Mockito.anyBoolean()))
                .thenReturn(null)
            Mockito
                .`when`(paperTradingService.recordVariantDecision(anyRisk(), Mockito.nullable(String::class.java)))
                .thenReturn(null)
        }

        runCycle()

        runBlocking {
            Mockito.verify(paperTradingService).recordControlDecision(anyRisk(), Mockito.anyString(), Mockito.eq(false))
            Mockito.verify(paperTradingService).recordVariantDecision(anyRisk(), Mockito.nullable(String::class.java))
            Mockito.verify(discretionaryStrategy).produceVariant(anyContext(), Mockito.anyString())
        }
        Mockito.verify(eventPublisher, Mockito.never()).publishStrategyGenerated(anySignal())
    }

    @Test
    fun `experiment without variant version records control as the variant`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()
        runBlocking {
            Mockito.`when`(paperTradingService.isExperimentEnabled()).thenReturn(true)
            Mockito.`when`(paperTradingService.inExperiment(Mockito.anyString())).thenReturn(true)
            Mockito.`when`(paperTradingService.isShadowExecution()).thenReturn(false)
            Mockito.`when`(paperTradingService.variantVersion()).thenReturn(null)
            Mockito
                .`when`(paperTradingService.recordControlDecision(anyRisk(), Mockito.anyString(), Mockito.anyBoolean()))
                .thenReturn(null)
            Mockito
                .`when`(paperTradingService.recordVariantDecision(anyRisk(), Mockito.nullable(String::class.java)))
                .thenReturn(null)
        }

        runCycle()

        runBlocking {
            Mockito.verify(paperTradingService).recordControlDecision(anyRisk(), Mockito.anyString(), Mockito.eq(true))
            Mockito.verify(discretionaryStrategy, Mockito.never()).produceVariant(anyContext(), Mockito.anyString())
        }
        Mockito.verify(eventPublisher).publishStrategyGenerated(anySignal())
    }

    @Test
    fun `shadowed llm winner is logged while the deterministic decision is executed`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline(
            resultTransform = { it.copy(shadowed = true, shadowedDecision = StrategyDecision.hold(BigDecimal("100"), "llm")) },
        )

        runCycle()

        assertEquals(1.0, strategySaved())
        Mockito.verify(eventPublisher).publishStrategyGenerated(anySignal())
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `feedback failure does not abort the cycle`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()
        runBlocking {
            Mockito
                .`when`(feedbackAgent.generateFeedback(Mockito.anyString(), Mockito.anyString()))
                .thenThrow(IllegalStateException("llm down"))
        }

        runCycle()

        assertEquals(1.0, counter("strategy.feedback.error", "ticker", "SBER"))
        assertEquals(1.0, strategySaved(), "тикер должен обработаться без feedback")
    }

    @Test
    fun `strategy runner failure is reported and nothing is persisted`() {
        stubSettings()
        stubCachedCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()
        runBlocking {
            Mockito.`when`(strategyRunner.runAll(anyContext())).thenThrow(IllegalStateException("runner down"))
        }

        runCycle()

        assertEquals(1.0, counter("strategy.error", "ticker", "SBER"))
        runBlocking { Mockito.verify(strategyRepo, Mockito.never()).save(anyStrategy()) }
    }

    // ----------------------------------------------------------- candle load

    @Test
    fun `db candles are promoted into the cache`() {
        stubSettings()
        stubEmptyCandles()
        stubStrategyPipeline()
        stubMarketSnapshot(snapshot())
        runBlocking {
            Mockito
                .`when`(
                    candleRepo.findByTickerAndTimeframeAndTimeBetween(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        anyLocalDateTime(),
                        anyLocalDateTime(),
                    ),
                ).thenReturn(freshCandles())
        }

        runCycle()

        assertEquals(1.0, strategySaved())
        Mockito.verify(candleCache).addCandles(Mockito.anyList())
        runBlocking { Mockito.verify(moexClient, Mockito.never()).getCandles(Mockito.anyString(), anyLocalDateTime()) }
    }

    @Test
    fun `moex candles are persisted and cached on cold start`() {
        stubSettings()
        stubEmptyCandles()
        stubStrategyPipeline()
        stubMarketSnapshot(snapshot())
        runBlocking {
            Mockito.`when`(moexClient.getCandles(Mockito.anyString(), anyLocalDateTime())).thenReturn(freshCandles())
            Mockito.`when`(candleRepo.saveAll(Mockito.anyList())).thenReturn(freshCandles().size)
        }

        runCycle()

        assertEquals(1.0, strategySaved())
        runBlocking { Mockito.verify(candleRepo).saveAll(Mockito.anyList()) }
        Mockito.verify(candleCache).addCandles(Mockito.anyList())
    }

    @Test
    fun `paired ticker quote failure does not break the ticker`() {
        tradingConfig.pairs = mapOf("SBER" to "GAZP")
        stubSettings()
        stubCachedCandles()
        stubStrategyPipeline()
        runBlocking {
            Mockito.doReturn(snapshot()).`when`(alorClient).getMarketSnapshot("SBER")
            Mockito.doThrow(IllegalStateException("quote down")).`when`(alorClient).getMarketSnapshot("GAZP")
        }

        runCycle()

        assertEquals(1.0, strategySaved(), "сбой парного тикера не должен ломать обработку")
        assertEquals(0.0, counter("strategy.error", "ticker", "SBER"))
    }

    // ------------------------------------------------------------ timeframe

    @Test
    fun `timeframes fall back to the primary timeframe when nothing is configured`() {
        stubSettings(timeframes = emptyList())
        tradingConfig.timeframes = emptyList()
        tradingConfig.timeframe = "DAY_1"
        stubCachedCandles(freshCandles(timeframe = "DAY_1"))
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()

        runCycle()

        assertEquals(
            1.0,
            meterRegistry
                .find("strategy.saved")
                .tag("timeframe", "DAY_1")
                .counter()
                ?.count(),
            "должен использоваться основной таймфрейм",
        )
    }

    @Test
    fun `every supported timeframe maps to its own staleness window`() {
        // Ветви `when` в timeframeDurationMs: для каждого ТФ цикл доходит до проверки
        // свежести; «старые» свечи отсекаются (тикер пропускается, стратегии не идут).
        val timeframes =
            listOf(
                "MINUTE_1",
                "M1",
                "MINUTE_5",
                "M5",
                "MINUTE_10",
                "M10",
                "MINUTE_15",
                "M15",
                "MINUTE_30",
                "M30",
                "HOUR_1",
                "H1",
                "HOUR_2",
                "H2",
                "HOUR_4",
                "H4",
                "DAY_1",
                "D1",
                "WEEK_1",
            )
        stubSettings(timeframes = timeframes)
        stubCachedCandles(emptyList())
        stubEmptyCandles()
        stubMarketSnapshot(snapshot())
        stubStrategyPipeline()

        runCycle()

        assertEquals(
            timeframes.size.toDouble(),
            counter("strategy.skipped", "ticker", "SBER", "reason", "STALE_CANDLES"),
            "каждый таймфрейм должен быть проверен на свежесть",
        )
        Mockito.verifyNoInteractions(strategyRunner)
    }

    /** Helper-паттерн Mockito для suspend-метода с non-null параметром. */
    private fun anyRisk(): com.trading.bot.domain.risk.TradeRiskDecision {
        Mockito.any(com.trading.bot.domain.risk.TradeRiskDecision::class.java)
        return com.trading.bot.domain.risk.TradeRiskDecision.of(
            Signal(
                ticker = "SBER",
                action = StrategyAction.BUY,
                targetPrice = BigDecimal("100"),
                signalStrength = 0.8,
                reasoning = "matcher",
                timeframe = "MINUTE_10",
                cycleId = "cycle-1",
            ),
        )
    }
}
