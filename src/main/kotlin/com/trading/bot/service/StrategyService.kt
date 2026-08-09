package com.trading.bot.service

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.PerformanceFeedbackAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.client.AlorClient
import com.trading.bot.client.MoexClient
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.signal.Signal
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.infrastructure.db.BlockingDb
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.Strategy
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.StrategyRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 *  3. Внутри тикера независимые LLM-агенты (Technical + Fundamental) вызываются
 *     одновременно, затем последовательно: Strategist -> Contrarian -> Arbitrator.
 *
 * Ускорение цикла на 10 тикерах: ~50-150 сек x5 агентов -> <60 сек на весь цикл.
 *
 * @see com.trading.bot.agent.TechnicalAnalysisAgent
 * @see com.trading.bot.agent.FundamentalAnalysisAgent
 * @see com.trading.bot.agent.StrategyAgent
 * @see com.trading.bot.agent.ContrarianAgent
 * @see com.trading.bot.agent.ArbitratorAgent
 */
@Service
class StrategyService(
    private val tradingConfig: TradingConfig,
    private val alorClient: AlorClient,
    private val moexClient: MoexClient,
    private val techAgent: TechnicalAnalysisAgent,
    private val fundAgent: FundamentalAnalysisAgent,
    private val stratAgent: StrategyAgent,
    private val contrAgent: ContrarianAgent,
    private val arbAgent: ArbitratorAgent,
    private val feedbackAgent: PerformanceFeedbackAgent,
    private val adaptiveRisk: AdaptiveRiskService,
    private val redis: RedisCacheService,
    private val candleCache: CandleCacheService,
    private val strategyRepo: StrategyRepository,
    private val candleRepo: CandleRepository,
    private val eventPublisher: TradingEventPublisher,
    private val settingsService: SettingsService,
    private val paperTradingService: PaperTradingService,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Scheduled-точка входа стратегического цикла. Не блокирует поток планировщика:
     * вся работа выполняется в фоновом CoroutineScope.
     */
    @Scheduled(fixedDelayString = "#{@tradingConfig.strategyIntervalMs}")
    fun run() {
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
        scope.launch(TraceContext.mdcContext()) { executeCycle(cycleId) }
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

        val (candles, snapshot, fb) =
            coroutineScope {
                val candlesDeferred = async { loadCandles(ticker, timeframe) }
                val snapshotDeferred = async { alorClient.getMarketSnapshot(ticker) }
                Triple(candlesDeferred.await(), snapshotDeferred.await(), feedbackDeferred.await())
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

        // Независимые агенты Tech + Fund — параллельно (экономит ~1 LLM-вызов за такт)
        val (tech, fund) =
            coroutineScope {
                val techDeferred = async { techAgent.analyze(ticker, candles, snapshot, cycleId) }
                val fundDeferred = async { fundAgent.analyze(ticker, cycleId) }
                techDeferred.await() to fundDeferred.await()
            }

        val adaptiveConf = adaptiveRisk.getAdaptiveConfidenceThreshold(ticker)
        val draft = stratAgent.formulate(ticker, tech, fund, snapshot, cycleId, adaptiveThreshold = adaptiveConf)
        val challenge = contrAgent.challenge(draft, tech, fund, snapshot, cycleId)
        val final =
            arbAgent.adjudicate(
                draft,
                challenge,
                tech,
                fund,
                snapshot,
                cycleId,
                contextPrompt = fb?.contextPrompt,
                adaptiveConfidence = adaptiveConf,
            )

        // Сигнал стратегического этапа — чистое направление (без quantity/SL/TP).
        val effectiveConfidence = final.confidence.coerceAtLeast(adaptiveConf)
        val signal =
            Signal(
                ticker = ticker,
                action = final.action,
                targetPrice = final.targetPrice,
                confidence = effectiveConfidence,
                reasoning = final.reasoning + " | Meta: confAdj=${fb?.confidenceAdjustment ?: 0.0}",
                timeframe = timeframe,
                cycleId = cycleId,
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
                confidence = signal.confidence,
                reasoning = signal.reasoning,
                rawJson = objectMapper.writeValueAsString(final),
                cycleId = cycleId,
                timeframe = timeframe,
                validUntil = LocalDateTime.now().plusMinutes(10),
            )

        strategyRepo.save(strategy)
        BlockingDb.io { redis.saveStrategy(strategy) }

        val experimentEnabled = paperTradingService.isExperimentEnabled()
        val inExperiment = experimentEnabled && paperTradingService.inExperiment(cycleId)
        val shadowExecution = inExperiment && paperTradingService.isShadowExecution()
        if (inExperiment) {
            paperTradingService.recordControlDecision(cycleId, ticker, timeframe, signal, strategy.rawJson, executed = !shadowExecution)
            paperTradingService.produceVariantDecision(
                cycleId = cycleId,
                ticker = ticker,
                timeframe = timeframe,
                draft = draft,
                challenge = challenge,
                tech = tech,
                fund = fund,
                snapshot = snapshot,
                control = signal,
                contextPrompt = fb?.contextPrompt,
                adaptiveConfidence = adaptiveConf,
            )
        }

        if (shadowExecution) {
            logger.info { "SHADOW: $ticker/$timeframe decision=${final.action} recorded but NOT executed" }
        } else {
            eventPublisher.publishStrategyGenerated(signal)
        }
        meterRegistry
            .counter(
                "strategy.saved",
                Tags.of("ticker", ticker, "timeframe", timeframe, "action", final.action.name),
            ).increment()
        logger.info {
            "Strategy $ticker/$timeframe: ${final.action} @ ${final.targetPrice} (adaptive conf=$adaptiveConf)"
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
}
