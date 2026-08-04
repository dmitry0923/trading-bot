package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.PerformanceFeedbackAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.client.AlorClient
import com.trading.bot.client.MoexClient
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.infrastructure.db.BlockingDb
import com.trading.bot.model.Candle
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.RiskContext
import com.trading.bot.model.Strategy
import com.trading.bot.model.StrategyAction
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.StrategyRepository
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
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
    private val riskManagement: RiskManagementService,
    private val settingsService: SettingsService,
    private val positionRepo: PositionRepository,
    private val riskConfig: RiskConfig,
    private val redis: RedisCacheService,
    private val candleCache: CandleCacheService,
    private val strategyRepo: StrategyRepository,
    private val candleRepo: CandleRepository,
    private val eventPublisher: TradingEventPublisher,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cycleRunning = AtomicBoolean(false)

    /**
     * Scheduled-точка входа стратегического цикла. Не блокирует поток планировщика:
     * вся работа выполняется в фоновом CoroutineScope.
     */
    @Scheduled(fixedDelayString = "#{@tradingConfig.strategyIntervalMs}")
    fun run() {
        if (!settingsService.isTradingEnabled()) {
            logger.info { "Strategy cycle skipped: trading is disabled" }
            meterRegistry.counter("strategy.skipped", Tags.of("reason", "TRADING_DISABLED")).increment()
            return
        }
        if (!cycleRunning.compareAndSet(false, true)) {
            logger.warn { "Strategy cycle skipped: previous cycle is still running" }
            meterRegistry.counter("strategy.skipped", Tags.of("reason", "CYCLE_ALREADY_RUNNING")).increment()
            return
        }

        val cycleId = UUID.randomUUID().toString()
        logger.info { "=== STRATEGY CYCLE $cycleId ===" }
        meterRegistry.counter("strategy.cycle").increment()
        scope.launch {
            try {
                executeCycle(cycleId)
            } finally {
                cycleRunning.set(false)
            }
        }
    }

    /**
     * Ручной триггер (API /api/v1/strategy/trigger).
     */
    fun runStrategyCycle() = run()

    @PreDestroy
    fun shutdown() {
        scope.cancel("StrategyService is shutting down")
    }

    /**
     * Исполняет цикл стратегии. Feedback Meta-Agent'а генерируется ПАРАЛЛЕЛЬНО
     * с обработкой тикеров: цикл больше не ждёт завершения всех feedback-вызовов,
     * каждый тикер ожидает только свой feedback внутри [processTicker].
     *
     * Критический путь цикла сокращается с `feedback + tickerChain` до
     * `max(feedback, tickerChain)` — экономит один LLM-roundtrip на каждый цикл.
     *
     * @param cycleId уникальный идентификатор цикла
     */
    private suspend fun executeCycle(cycleId: String) {
        val tickers = tradingConfig.tickers
        val cycleStart = System.nanoTime()
        try {
            coroutineScope {
                val feedback =
                    tickers.associateWith { ticker ->
                        async {
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
                    .map { ticker ->
                        async {
                            try {
                                processTicker(ticker, cycleId, feedback.getValue(ticker))
                            } catch (e: Exception) {
                                logger.error(e) { "Strategy error $ticker" }
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
     * Обработка одного тикера.
     *
     * Данные для анализа (свечи, снапшот) загружаются ПАРАЛЛЕЛЬНО с ожиданием
     * feedback Meta-Agent'а — все три источника независимы, что убирает задержку
     * LLM-вызова feedback из последовательной цепочки тикера.
     *
     * @param ticker тикер инструмента
     * @param cycleId идентификатор цикла
     * @param feedbackDeferred результат feedback Meta-Agent'а (может быть null)
     */
    private suspend fun processTicker(
        ticker: String,
        cycleId: String,
        feedbackDeferred: Deferred<PerformanceFeedbackAgent.StrategyFeedback?>,
    ) {
        // Локальный adaptive risk — без LLM, проверяем первым
        if (adaptiveRisk.shouldPauseTrading(ticker)) {
            logger.info { "Skipping $ticker — trading paused by adaptive risk" }
            meterRegistry.counter("strategy.skipped", Tags.of("ticker", ticker)).increment()
            return
        }

        val (candles, snapshot, fb) =
            coroutineScope {
                val candlesDeferred = async { loadCandles(ticker) }
                val snapshotDeferred = async { alorClient.getMarketSnapshot(ticker) }
                Triple(candlesDeferred.await(), snapshotDeferred.await(), feedbackDeferred.await())
            }

        if (fb?.shouldPauseTrading == true) {
            logger.warn { "PAUSE recommended for $ticker by Meta-Agent" }
            meterRegistry.counter("strategy.pause", Tags.of("ticker", ticker)).increment()
            return
        }
        if (snapshot == null) return

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

        val riskContext =
            RiskContext(
                shouldPause = adaptiveRisk.shouldPauseTrading(ticker),
                dailyLossLimitReached = riskManagement.isDailyLossLimitReached(),
                drawdownRecovery = adaptiveRisk.isInDrawdownRecovery(),
                openPositionsCount = positionRepo.findByStatus(PositionStatus.OPEN).size,
                maxOpenPositions = riskConfig.maxOpenPositions,
            )
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
                riskContext = riskContext,
            )

        val atr = BigDecimal.valueOf(tech.atr.coerceAtLeast(0.0))

        val highVolatility =
            final.action != StrategyAction.HOLD &&
                riskManagement.isVolatilityTooHigh(atr, snapshot.currentPrice)
        val effectiveFinal =
            if (highVolatility) {
                logger.warn { "Volatility guard: $ticker ATR=$atr > ${riskConfig.maxVolatilityPercent}%, strategy -> HOLD" }
                meterRegistry.counter("strategy.volatility.blocked", Tags.of("ticker", ticker)).increment()
                final.copy(action = StrategyAction.HOLD, quantity = 0, reasoning = final.reasoning + " [VOLATILITY_GUARD]")
            } else {
                final
            }

        val direction =
            when (effectiveFinal.action) {
                StrategyAction.BUY -> PositionDirection.LONG
                StrategyAction.SELL -> PositionDirection.SHORT
                else -> null
            }
        val adaptiveSL =
            direction?.let {
                adaptiveRisk.calculateAdaptiveSL(effectiveFinal.targetPrice, it, ticker, atr)
            }
        val adaptiveTP =
            direction?.let {
                adaptiveRisk.calculateAdaptiveTP(effectiveFinal.targetPrice, it, ticker, atr)
            }
        // Не повышаем искусственно низкую уверенность до порога: это скрывало
        // фактическую оценку агента в БД и UI. Guardrails уже применил adaptiveConf.
        val effectiveConfidence = effectiveFinal.confidence.coerceIn(0.0, 1.0)

        val strategy =
            Strategy(
                ticker = ticker,
                action = effectiveFinal.action,
                targetPrice = effectiveFinal.targetPrice,
                quantity = effectiveFinal.quantity,
                stopLoss = adaptiveSL,
                takeProfit = adaptiveTP,
                trailingStop = effectiveFinal.trailingStop,
                confidence = effectiveConfidence,
                reasoning = effectiveFinal.reasoning + " | Meta: confAdj=${fb?.confidenceAdjustment ?: 0.0}, SL/TP adapted, atr=$atr",
                rawJson = objectMapper.writeValueAsString(effectiveFinal),
                cycleId = cycleId,
                validUntil = LocalDateTime.now().plusMinutes(10),
            )

        strategyRepo.save(strategy)
        BlockingDb.io { redis.saveStrategy(strategy) }
        eventPublisher.publishStrategyGenerated(strategy)
        meterRegistry.counter("strategy.saved", Tags.of("ticker", ticker, "action", effectiveFinal.action.name)).increment()
        logger.info { "Strategy $ticker: ${effectiveFinal.action} @ ${effectiveFinal.targetPrice} (adaptive conf=$adaptiveConf, atr=$atr)" }
    }

    private suspend fun loadCandles(ticker: String): List<Candle> {
        // 1. Redis-кэш (Sorted Set) — дешёвое чтение последних свечей
        val cached = candleCache.getRecentCandles(ticker, tradingConfig.timeframe, 200)
        if (cached.size >= 50) return cached.sortedBy { it.time }

        // 2. PostgreSQL — долгосрочное хранение (R2DBC, без Dispatchers.IO)
        val from = LocalDateTime.now().minusDays(7)
        val db = candleRepo.findByTickerAndTimeframeAndTimeBetween(ticker, tradingConfig.timeframe, from, LocalDateTime.now())
        if (db.size >= 50) {
            candleCache.addCandles(db)
            return db.sortedBy { it.time }
        }

        // 3. MOEX — последний источник, с write-through в кэш
        val moex = moexClient.getCandles(ticker, 10, from)
        moex.forEach { candle -> candleRepo.save(candle) }
        candleCache.addCandles(moex)
        return moex.sortedBy { it.time }
    }
}
