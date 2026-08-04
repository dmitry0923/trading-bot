package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.agent.*
import com.trading.bot.client.AlorClient
import com.trading.bot.client.MoexClient
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.model.*
import com.trading.bot.repository.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

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
    private val positionRepo: PositionRepository,
    private val riskConfig: RiskConfig,
    private val redis: RedisCacheService,
    private val strategyRepo: StrategyRepository,
    private val candleRepo: CandleRepository,
    private val agentLogRepo: AgentLogRepository,
    private val eventPublisher: TradingEventPublisher,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Scheduled-точка входа стратегического цикла. Не блокирует поток планировщика:
     * вся работа выполняется в фоновом CoroutineScope.
     */
    @Scheduled(fixedDelayString = "#{@tradingConfig.strategyIntervalMs}")
    fun run() {
        val cycleId = UUID.randomUUID().toString()
        logger.info { "=== STRATEGY CYCLE $cycleId ===" }
        meterRegistry.counter("strategy.cycle").increment()
        scope.launch { executeCycle(cycleId) }
    }

    /**
     * Ручной триггер (API /api/v1/strategy/trigger).
     */
    fun runStrategyCycle() = run()

    /**
     * Исполняет цикл стратегии: параллельный feedback, затем параллельная обработка тикеров.
     *
     * @param cycleId уникальный идентификатор цикла
     */
    private suspend fun executeCycle(cycleId: String) {
        val tickers = tradingConfig.tickers
        try {
            coroutineScope {
                val feedback = tickers
                    .map { ticker ->
                        async {
                            try {
                                ticker to feedbackAgent.generateFeedback(ticker)
                            } catch (e: Exception) {
                                logger.error(e) { "Feedback generation failed for $ticker" }
                                meterRegistry.counter("strategy.feedback.error", Tags.of("ticker", ticker)).increment()
                                ticker to null
                            }
                        }
                    }
                    .awaitAll()
                    .toMap()

                feedback.forEach { (ticker, fb) ->
                    if (fb?.shouldPauseTrading == true) {
                        logger.warn { "PAUSE recommended for $ticker by Meta-Agent" }
                        meterRegistry.counter("strategy.pause", Tags.of("ticker", ticker)).increment()
                    }
                }

                tickers
                    .map { ticker ->
                        async {
                            try {
                                processTicker(ticker, cycleId, feedback[ticker])
                            } catch (e: Exception) {
                                logger.error(e) { "Strategy error $ticker" }
                                meterRegistry.counter("strategy.error", Tags.of("ticker", ticker)).increment()
                            }
                        }
                    }
                    .awaitAll()
            }
        } catch (e: Exception) {
            logger.error(e) { "Strategy cycle failed" }
        }
    }

    /**
     * Обработка одного тикера. Tech/Fund-агенты запускаются параллельно.
     *
     * @param ticker тикер инструмента
     * @param cycleId идентификатор цикла
     * @param fb feedback Meta-Agent'а (может быть null)
     */
    private suspend fun processTicker(
        ticker: String,
        cycleId: String,
        fb: PerformanceFeedbackAgent.StrategyFeedback?
    ) {
        if (fb?.shouldPauseTrading == true || adaptiveRisk.shouldPauseTrading(ticker)) {
            logger.info { "Skipping $ticker — trading paused by adaptive risk" }
            meterRegistry.counter("strategy.skipped", Tags.of("ticker", ticker)).increment()
            return
        }

        val candles = loadCandles(ticker)
        val snapshot = alorClient.getMarketSnapshot(ticker) ?: return

        // Независимые агенты Tech + Fund — параллельно (экономит ~1 LLM-вызов за такт)
        val (tech, fund) = coroutineScope {
            val techDeferred = async { techAgent.analyze(ticker, candles, snapshot, cycleId) }
            val fundDeferred = async { fundAgent.analyze(ticker, cycleId) }
            techDeferred.await() to fundDeferred.await()
        }

        val adaptiveConf = adaptiveRisk.getAdaptiveConfidenceThreshold(ticker)
        val draft = stratAgent.formulate(ticker, tech, fund, snapshot, cycleId, adaptiveThreshold = adaptiveConf)
        val challenge = contrAgent.challenge(draft, tech, fund, snapshot, cycleId)

        val riskContext = RiskContext(
            shouldPause = adaptiveRisk.shouldPauseTrading(ticker),
            dailyLossLimitReached = riskManagement.isDailyLossLimitReached(),
            drawdownRecovery = adaptiveRisk.isInDrawdownRecovery(),
            openPositionsCount = positionRepo.findByStatus(PositionStatus.OPEN).size,
            maxOpenPositions = riskConfig.maxOpenPositions
        )
        val final = arbAgent.adjudicate(
            draft, challenge, tech, fund, snapshot, cycleId,
            contextPrompt = fb?.contextPrompt,
            adaptiveConfidence = adaptiveConf,
            riskContext = riskContext
        )

        val atr = BigDecimal.valueOf(tech.atr)
        val direction = if (final.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT

        val highVolatility = final.action != StrategyAction.HOLD &&
            riskManagement.isVolatilityTooHigh(atr, snapshot.currentPrice)
        val effectiveFinal = if (highVolatility) {
            logger.warn { "Volatility guard: $ticker ATR=$atr > ${riskConfig.maxVolatilityPercent}%, strategy -> HOLD" }
            meterRegistry.counter("strategy.volatility.blocked", Tags.of("ticker", ticker)).increment()
            final.copy(action = StrategyAction.HOLD, quantity = 0, reasoning = final.reasoning + " [VOLATILITY_GUARD]")
        } else {
            final
        }

        val adaptiveSL = adaptiveRisk.calculateAdaptiveSL(effectiveFinal.targetPrice, direction, ticker, atr)
        val adaptiveTP = adaptiveRisk.calculateAdaptiveTP(effectiveFinal.targetPrice, direction, ticker, atr)
        val effectiveConfidence = effectiveFinal.confidence.coerceAtLeast(adaptiveConf)

        val strategy = Strategy(
            ticker = ticker,
            action = effectiveFinal.action,
            targetPrice = effectiveFinal.targetPrice,
            quantity = effectiveFinal.quantity,
            stopLoss = adaptiveSL,
            takeProfit = adaptiveTP,
            trailingStop = effectiveFinal.trailingStop,
            confidence = effectiveConfidence,
            reasoning = effectiveFinal.reasoning + " | Meta: confAdj=${fb?.confidenceAdjustment ?: 0.0}, SL/TP adapted, atr=${atr}",
            rawJson = objectMapper.writeValueAsString(effectiveFinal),
            cycleId = cycleId,
            validUntil = LocalDateTime.now().plusMinutes(10)
        )

        strategyRepo.save(strategy)
        redis.saveStrategy(strategy)
        eventPublisher.publishStrategyGenerated(strategy)
        meterRegistry.counter("strategy.saved", Tags.of("ticker", ticker, "action", effectiveFinal.action.name)).increment()
        logger.info { "Strategy $ticker: ${effectiveFinal.action} @ ${effectiveFinal.targetPrice} (adaptive conf=$adaptiveConf, atr=$atr)" }
    }

    private suspend fun loadCandles(ticker: String): List<Candle> {
        val from = LocalDateTime.now().minusDays(7)
        val db = candleRepo.findByTickerAndTimeframeAndTimeBetween(ticker, tradingConfig.timeframe, from, LocalDateTime.now())
        if (db.size >= 50) return db.sortedBy { it.time }
        val moex = moexClient.getCandles(ticker, 10, from)
        moex.forEach { if (!candleRepo.existsByTickerAndTimeframeAndTime(it.ticker, it.timeframe, it.time)) candleRepo.save(it) }
        return moex.sortedBy { it.time }
    }
}
