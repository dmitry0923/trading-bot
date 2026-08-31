package com.trading.bot.service

import com.trading.bot.agent.PerformanceFeedbackAgent
import com.trading.bot.application.StrategyRunner
import com.trading.bot.application.advisor.LlmAdvisor
import com.trading.bot.application.strategy.DiscretionaryStrategy
import com.trading.bot.client.AlorClient
import com.trading.bot.client.MoexClient
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDetector
import com.trading.bot.domain.risk.TradeRiskDecision
import com.trading.bot.domain.signal.Signal
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.infrastructure.metrics.MutableGauges
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.Strategy
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.StrategyRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Оркестратор стратегического цикла (мульти-тикер, параллельный).
 *
 * Каждый тик (cycleId) бота:
 *  1. Meta-Agent (feedback) генерируется ПАРАЛЛЕЛЬНО для всех тикеров.
 *  2. Все тикеры обрабатываются ПАРАЛЛЕЛЬНО (по одному coroutine на тикер).
 *  3. Внутри тикера StrategyRunner запускает ВСЕ стратегии параллельно
 *     (детерминированные правила + LLM-путь DiscretionaryStrategy); победитель
 *     по максимальной уверенности даёт Signal.
 *
 * Ускорение цикла на 10 тикерах: ~50-150 сек x5 агентов -> <60 сек на весь цикл.
 *
 * @see com.trading.bot.application.StrategyRunner
 * @see com.trading.bot.domain.strategy.Strategy
 * @see com.trading.bot.application.strategy.DiscretionaryStrategy
 */
@Service
class StrategyService(
    private val tradingConfig: TradingConfig,
    private val riskConfig: RiskConfig,
    private val alorClient: AlorClient,
    private val moexClient: MoexClient,
    private val strategyRunner: StrategyRunner,
    private val discretionaryStrategy: DiscretionaryStrategy,
    private val llmAdvisor: LlmAdvisor,
    private val feedbackAgent: PerformanceFeedbackAgent,
    private val adaptiveRisk: AdaptiveRiskService,
    private val redis: ReactiveRedisCacheService,
    private val candleCache: CandleCacheService,
    private val strategyRepo: StrategyRepository,
    private val candleRepo: CandleRepository,
    private val eventPublisher: TradingEventPublisher,
    private val settingsService: SettingsService,
    private val paperTradingService: PaperTradingService,
    private val emergencyStopService: EmergencyStopService,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
    private val distributedLockService: DistributedLockService,
    private val distributedLockConfig: DistributedLockConfig,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @PreDestroy
    fun close() {
        scope.cancel()
    }

    /**
     * Scheduled-точка входа стратегического цикла. Не блокирует поток планировщика:
     * вся работа выполняется в фоновом CoroutineScope.
     */
    @Scheduled(fixedDelayString = "#{@tradingConfig.strategyIntervalMs}")
    fun run() {
        // Emergency stop: проверка в начале цикла — немедленный выход (см. roadmap 13.7.1).
        if (emergencyStopService.isActive()) {
            logger.warn { "Strategy cycle skipped — EMERGENCY STOP active (reason=${emergencyStopService.lastReason()})" }
            meterRegistry.counter("strategy.skipped", Tags.of("reason", "EMERGENCY_STOP")).increment()
            return
        }
        // Мульти-реплика: стратегический цикл выполняет только лидер (взял distributed lock).
        // Конкуренция = другая реплика уже работает; сбой Redis = fail-open (цикл всё равно гоняем).
        scope.launch(TraceContext.mdcContext()) {
            distributedLockService.runExclusive(
                name = "scheduler:strategy-cycle",
                ttlSeconds = distributedLockConfig.schedulerTtlSeconds,
            ) {
                val cycleId =
                    com.trading.bot.infrastructure.UuidV7
                        .uuidString()
                logger.info { "=== STRATEGY CYCLE $cycleId ===" }
                meterRegistry.counter("strategy.cycle").increment()
                // trace_id = cycleId: единый идентификатор для всего цикла (JSON-логи,
                // agent_logs, позиции, события). MDC-контекст копируется в coroutine,
                // поэтому дочерние корутины (включая LLM-вызовы) наследуют trace_id.
                TraceContext.put(TraceContext.TRACE_ID, cycleId)
                TraceContext.put(TraceContext.CYCLE_ID, cycleId)
                executeCycle(cycleId)
            }
        }
        TraceContext.put(TraceContext.TRACE_ID, null)
        TraceContext.put(TraceContext.CYCLE_ID, null)
    }

    /**
     * Ручной триггер (API /api/v1/strategy/trigger).
     */
    fun runStrategyCycle() = run()

    /**
     * Исполняет цикл стратегии. Feedback Meta-Agent'а генерируется ПАРАЛЛЕЛЬНО
     * с обработкой тикеров: цикл больше не ждёт завершения всех feedback-вызовов,
     * каждый тикер ожидает только свой feedback внутри [processTicker].
     *
     * Поддерживается мульти-таймфрейм: каждый тикер анализируется на всех
     * настроенных таймфреймах (настройки через UI либо trading.timeframes).
     *
     * Критический путь цикла сокращается с `feedback + tickerChain` до
     * `max(feedback, tickerChain)` — экономит один LLM-roundtrip на каждый цикл.
     *
     * @param cycleId уникальный идентификатор цикла
     */
    private suspend fun executeCycle(cycleId: String) {
        val tickers = tradingConfig.tickers
        val timeframes = effectiveTimeframes()
        val cycleStart = System.nanoTime()
        try {
            coroutineScope {
                val feedback =
                    tickers.associateWith { ticker ->
                        async(TraceContext.mdcContext(mapOf(TraceContext.TICKER to ticker))) {
                            try {
                                feedbackAgent.generateFeedback(ticker)
                            } catch (e: Exception) {
                                logger.error(e) { "Feedback generation failed for $ticker" }
                                meterRegistry.counter("strategy.feedback.error", Tags.of("ticker", ticker)).increment()
                                null
                            }
                        }
                    }

                tickers
                    .flatMap { ticker -> timeframes.map { timeframe -> ticker to timeframe } }
                    .map { (ticker, timeframe) ->
                        async(TraceContext.mdcContext(mapOf(TraceContext.TICKER to ticker))) {
                            try {
                                processTicker(ticker, timeframe, cycleId, feedback.getValue(ticker))
                            } catch (e: Exception) {
                                logger.error(e) { "Strategy error $ticker/$timeframe" }
                                meterRegistry.counter("strategy.error", Tags.of("ticker", ticker)).increment()
                            }
                        }
                    }.awaitAll()
            }
        } catch (e: Exception) {
            logger.error(e) { "Strategy cycle failed" }
        } finally {
            meterRegistry
                .timer("strategy.latency")
                .record(System.nanoTime() - cycleStart, java.util.concurrent.TimeUnit.NANOSECONDS)
        }
    }

    /**
     * Активные таймфреймы: приоритет у настроек UI (SettingsService.timeframes),
     * затем trading.timeframes, затем основной trading.timeframe.
     */
    private fun effectiveTimeframes(): List<String> {
        val fromSettings = settingsService.getSettings().timeframes
        if (fromSettings.isNotEmpty()) return fromSettings.distinct()
        if (tradingConfig.timeframes.isNotEmpty()) return tradingConfig.timeframes.distinct()
        return listOf(tradingConfig.timeframe)
    }

    /**
     * Обработка одного тикера на одном таймфрейме.
     *
     * Данные для анализа (свечи, снапшот) загружаются ПАРАЛЛЕЛЬНО с ожиданием
     * feedback Meta-Agent'а — все три источника независимы, что убирает задержку
     * LLM-вызова feedback из последовательной цепочки тикера.
     *
     * @param ticker тикер инструмента
     * @param timeframe таймфрейм свечей (MINUTE_10, HOUR_1, DAY_1, ...)
     * @param cycleId идентификатор цикла
     * @param feedbackDeferred результат feedback Meta-Agent'а (может быть null)
     */
    private suspend fun processTicker(
        ticker: String,
        timeframe: String,
        cycleId: String,
        feedbackDeferred: Deferred<PerformanceFeedbackAgent.StrategyFeedback?>,
    ) {
        // Локальный adaptive risk — без LLM, проверяем первым (экономия LLM-затрат:
        // сигнал при паузе всё равно будет отклонён на этапе RiskEngine).
        if (adaptiveRisk.shouldPauseTrading(ticker)) {
            logger.info { "Skipping $ticker — trading paused by adaptive risk" }
            meterRegistry.counter("strategy.skipped", Tags.of("ticker", ticker)).increment()
            return
        }

        val relatedTicker = tradingConfig.pairs[ticker]
        val (candles, snapshot, fb, relatedQuote) =
            coroutineScope {
                val candlesDeferred = async { loadCandles(ticker, timeframe) }
                val snapshotDeferred = async { alorClient.getMarketSnapshot(ticker) }
                val relatedDeferred =
                    async {
                        if (relatedTicker == null) {
                            null
                        } else {
                            try {
                                alorClient.getMarketSnapshot(relatedTicker)?.currentPrice
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to fetch related quote $relatedTicker for $ticker" }
                                null
                            }
                        }
                    }
                TickerInputs(
                    candles = candlesDeferred.await(),
                    snapshot = snapshotDeferred.await(),
                    feedback = feedbackDeferred.await(),
                    relatedQuote = relatedDeferred.await(),
                )
            }

        // Защита от анализа на «мёртвых» свечах: если последняя свеча старше
        // 2×длительности таймфрейма + буфер — тикер пропускается (данные устарели).
        if (!isCandlesFresh(candles, timeframe)) {
            logger.warn {
                "Skipping $ticker/$timeframe — stale candles (last=${candles.lastOrNull()?.time})"
            }
            meterRegistry.counter("strategy.skipped", Tags.of("ticker", ticker, "reason", "STALE_CANDLES")).increment()
            return
        }
        if (snapshot == null || snapshot.currentPrice <= BigDecimal.ZERO) {
            logger.warn { "Skipping $ticker/$timeframe — no valid market snapshot" }
            meterRegistry.counter("strategy.skipped", Tags.of("ticker", ticker, "reason", "STALE_SNAPSHOT")).increment()
            return
        }

        if (fb?.shouldPauseTrading == true) {
            logger.warn { "PAUSE recommended for $ticker by Meta-Agent" }
            meterRegistry.counter("strategy.pause", Tags.of("ticker", ticker)).increment()
            return
        }

        // Per-ticker рыночный режим: определяется ДО запуска стратегий. Если режим
        // блокирует входы (UNKNOWN из-за недостатка данных, Crash/Pump, низкая
        // ликвидность, экстремальная волатильность) — тикер пропускается целиком
        // (экономия LLM-вызовов, нет сигнала). Режим выключен (`perTickerRegimeEnabled`
        // = false) → regime = null, старый pass-through без гейта.
        val regime: PerTickerRegime? =
            if (riskConfig.perTickerRegimeEnabled) {
                RegimeDetector.detect(candles, riskConfig.toRegimeDetectionConfig())
            } else {
                null
            }
        if (regime != null) recordRegimeMetrics(ticker, regime)
        if (regime?.blocksEntry == true) {
            logger.warn { "Skipping $ticker/$timeframe — regime blocks entry: ${regime?.describe()}" }
            meterRegistry
                .counter(
                    "strategy.skipped",
                    Tags.of("ticker", ticker, "reason", "REGIME_BLOCKED"),
                ).increment()
            return
        }

        // Все стратегии запускаются ПАРАЛЛЕЛЬНО; победитель — по максимальной
        // взвешенной уверенности. LLM-путь (DiscretionaryStrategy) — одна из реализаций.
        val context =
            StrategyContext(
                ticker = ticker,
                snapshot = snapshot,
                candles = candles,
                indicators = IndicatorCalculator.calculate(candles),
                cycleId = cycleId,
                contextPrompt = fb?.contextPrompt,
                relatedQuote = relatedQuote,
                regime = regime,
            )
        val result = strategyRunner.runAll(context)
        val adaptiveConf = adaptiveRisk.getAdaptiveConfidenceThreshold(ticker)

        // LLM-советник (C-001): оценивает детерминированное решение вне критического
        // пути. VETO (CRITICAL-риск) блокирует вход; иначе — ограниченная поправка
        // уверенности. Советник не меняет направление сделки.
        val advisorVerdict = llmAdvisor.advise(context, result.decision, adaptiveConf)
        val decision =
            if (advisorVerdict.blocksEntry) {
                meterRegistry.counter("advisor.blocked", Tags.of("ticker", ticker)).increment()
                StrategyDecision.hold(
                    snapshot.currentPrice,
                    "Advisor VETO: ${advisorVerdict.explanation}",
                )
            } else {
                result.decision.copy(
                    signalStrength = (result.decision.signalStrength + advisorVerdict.confidenceAdjustment).coerceIn(0.0, 1.0),
                )
            }

        // Адаптивный порог (13.11.8) — ГЕЙТ, а не инфлятор: BUY/SELL ниже порога
        // или с non-finite силой (NaN из LLM-советника) -> HOLD. Раньше
        // `coerceAtLeast` раздувал слабые сигналы до порога — гейт не блокировал
        // ничего, а сила сигнала в истории/Kelly-сайзинге была фальшивой.
        val gated = StrategyDecision.gatedByConfidence(decision, snapshot.currentPrice, adaptiveConf)
        if (gated.action == StrategyAction.HOLD && decision.action != StrategyAction.HOLD) {
            meterRegistry.counter("strategy.low_confidence", Tags.of("ticker", ticker)).increment()
            logger.info { "Holding $ticker/$timeframe — decision below adaptive confidence threshold $adaptiveConf" }
        }
        val signal =
            Signal(
                ticker = ticker,
                action = gated.action,
                targetPrice = gated.targetPrice,
                signalStrength = gated.signalStrength,
                reasoning =
                    gated.reasoning +
                        " | Regime: ${regime?.describe() ?: "n/a"} | Meta: confAdj=${fb?.confidenceAdjustment ?: 0.0}" +
                        " | Advisor: ${advisorVerdict.verdict} (confAdj=${advisorVerdict.confidenceAdjustment}, risk=${advisorVerdict.riskLevel})",
                timeframe = timeframe,
                cycleId = cycleId,
                strategyName = result.winnerId,
            )

        // Стратегия как история решения. Риск-поля (quantity/SL/TP/trailing)
        // заполняются на этапе OrderBuilder после исполнения входа.
        val strategy =
            Strategy(
                ticker = ticker,
                action = signal.action,
                targetPrice = signal.targetPrice,
                quantity = 0,
                stopLoss = null,
                takeProfit = null,
                trailingStop = false,
                signalStrength = signal.signalStrength,
                reasoning = signal.reasoning,
                rawJson = objectMapper.writeValueAsString(result.all),
                cycleId = cycleId,
                timeframe = timeframe,
                strategyName = result.winnerId,
                validUntil = LocalDateTime.now().plusMinutes(10),
            )

        strategyRepo.save(strategy)
        // Redis хранит ПОСЛЕДНЮЮ действующую стратегию тикера: HOLD не перезаписывает
        // последний BUY/SELL (иначе OrderBuilder.recordStrategyExecution и REST
        // «последняя стратегия» видели HOLD-строку без риск-полей).
        if (signal.action != StrategyAction.HOLD) {
            redis.saveStrategy(strategy)
        }

        val experimentEnabled = paperTradingService.isExperimentEnabled()
        val inExperiment = experimentEnabled && paperTradingService.inExperiment(cycleId)
        val shadowExecution = inExperiment && paperTradingService.isShadowExecution()
        if (inExperiment) {
            // Единое риск-решение (стратегическая стадия — риск-поля пусты, они
            // заполняются при входе) — прогон через A/B-эксперимент.
            val control = TradeRiskDecision.of(signal)
            paperTradingService.recordControlDecision(control, strategy.rawJson, executed = !shadowExecution)
            // Вариант = повторный вызов Арбитра с другим промптом (LLM A/B) либо
            // теневая копия контроля. Входы варианта берутся из DiscretionaryStrategy.
            val variantVersion = paperTradingService.variantVersion()
            val variantDecision =
                if (variantVersion != null) {
                    val variant = discretionaryStrategy.produceVariant(context, variantVersion)
                    control.copy(
                        action = variant.action,
                        targetPrice = variant.targetPrice,
                        signalStrength = variant.signalStrength,
                        reasoning = variant.reasoning,
                    )
                } else {
                    control
                }
            paperTradingService.recordVariantDecision(variantDecision, version = variantVersion)
        }

        if (shadowExecution) {
            logger.info { "SHADOW: $ticker/$timeframe decision=${result.decision.action} recorded but NOT executed" }
        } else {
            eventPublisher.publishStrategyGenerated(signal)
        }
        meterRegistry
            .counter(
                "strategy.saved",
                Tags.of("ticker", ticker, "timeframe", timeframe, "action", gated.action.name, "strategy", result.winnerId),
            ).increment()
        logger.info {
            "Strategy $ticker/$timeframe: ${gated.action} @ ${gated.targetPrice} " +
                "via ${result.winnerId} (adaptive conf=$adaptiveConf, regime=${regime?.describe() ?: "n/a"}, advisor=${advisorVerdict.verdict})"
        }
    }

    /**
     * Метрики per-ticker рыночного режима: уровень (кодированный gauge) и счётчик
     * блокировок входов по причине.
     */
    private fun recordRegimeMetrics(
        ticker: String,
        regime: PerTickerRegime,
    ) {
        MutableGauges.set(meterRegistry, "market.regime.level", regime.encodedLevel(), Tags.of("ticker", ticker))
        regime.blockReason()?.let { reason ->
            meterRegistry.counter("market.regime.blocked", Tags.of("ticker", ticker, "reason", reason)).increment()
        }
    }

    private suspend fun loadCandles(
        ticker: String,
        timeframe: String,
    ): List<Candle> {
        // 1. Redis-кэш (Sorted Set) — дешёвое чтение последних свечей.
        //    Только если последняя свеча свежая — иначе данные устарели и
        //    кэш бесполезен (перекрывает обеденный перерыв/зависший кэш).
        val cached = candleCache.getRecentCandles(ticker, timeframe, 200)
        if (cached.size >= 50 && isCandlesFresh(cached, timeframe)) return cached.sortedBy { it.time }

        // 2. PostgreSQL — долгосрочное хранение (R2DBC, без Dispatchers.IO)
        val from = LocalDateTime.now().minusDays(7)
        val db = candleRepo.findByTickerAndTimeframeAndTimeBetween(ticker, timeframe, from, LocalDateTime.now())
        if (db.size >= 50 && isCandlesFresh(db, timeframe)) {
            candleCache.addCandles(db)
            return db.sortedBy { it.time }
        }

        // 3. MOEX — последний источник, с write-through в кэш
        val moex = moexClient.getCandles(ticker, from)
        candleRepo.saveAll(moex)
        candleCache.addCandles(moex)
        return moex.sortedBy { it.time }
    }

    /**
     * Свежи ли свечи для анализа: последняя свеча не старше
     * 2×длительности таймфрейма + [TradingConfig.candleStaleBufferMs].
     * Время свечей — МСК (как возвращает MOEX ISS).
     */
    private fun isCandlesFresh(
        candles: List<Candle>,
        timeframe: String,
    ): Boolean {
        val last = candles.lastOrNull() ?: return false
        val maxAgeMs = timeframeDurationMs(timeframe) * 2 + tradingConfig.candleStaleBufferMs
        val ageMs = Duration.between(last.time.atZone(MOSCOW_ZONE).toInstant(), Instant.now()).toMillis()
        return ageMs <= maxAgeMs
    }

    private fun timeframeDurationMs(timeframe: String): Long =
        when (timeframe.uppercase()) {
            "MINUTE_1", "M1" -> 60_000L
            "MINUTE_5", "M5" -> 300_000L
            "MINUTE_10", "M10" -> 600_000L
            "MINUTE_15", "M15" -> 900_000L
            "MINUTE_30", "M30" -> 1_800_000L
            "HOUR_1", "H1" -> 3_600_000L
            "HOUR_2", "H2" -> 7_200_000L
            "HOUR_4", "H4" -> 14_400_000L
            "DAY_1", "D1" -> 86_400_000L
            else -> 600_000L
        }

    private companion object {
        private val MOSCOW_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }

    /** Параллельно загруженные входные данные тикера для стратегического этапа. */
    private data class TickerInputs(
        val candles: List<Candle>,
        val snapshot: MarketSnapshot?,
        val feedback: PerformanceFeedbackAgent.StrategyFeedback?,
        val relatedQuote: BigDecimal?,
    )
}
