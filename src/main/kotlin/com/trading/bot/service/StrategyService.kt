package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.agent.*
import com.trading.bot.client.AlorClient
import com.trading.bot.client.MoexClient
import com.trading.bot.config.TradingConfig
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
    private val strategyRepo: StrategyRepository,
    private val candleRepo: CandleRepository,
    private val agentLogRepo: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Scheduled(fixedDelayString = "#{@tradingConfig.strategyIntervalMs}")
    fun run() {
        val cycleId = UUID.randomUUID().toString()
        logger.info { "=== STRATEGY CYCLE $cycleId ===" }
        meterRegistry.counter("strategy.cycle").increment()

        val globalFeedback = mutableMapOf<String, PerformanceFeedbackAgent.StrategyFeedback>()
        runBlocking {
            tradingConfig.tickers.forEach { ticker ->
                try {
                    globalFeedback[ticker] = feedbackAgent.generateFeedback(ticker)
                    if (globalFeedback[ticker]?.shouldPauseTrading == true) {
                        logger.warn { "PAUSE recommended for $ticker by Meta-Agent" }
                        meterRegistry.counter("strategy.pause", Tags.of("ticker", ticker)).increment()
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Feedback generation failed for $ticker" }
                    meterRegistry.counter("strategy.feedback.error", Tags.of("ticker", ticker)).increment()
                }
            }
        }

        scope.launch {
            tradingConfig.tickers.forEach { ticker ->
                try {
                    if (globalFeedback[ticker]?.shouldPauseTrading == true || adaptiveRisk.shouldPauseTrading(ticker)) {
                        logger.info { "Skipping $ticker — trading paused by adaptive risk" }
                        meterRegistry.counter("strategy.skipped", Tags.of("ticker", ticker)).increment()
                        return@forEach
                    }

                    val candles = loadCandles(ticker)
                    val snapshot = alorClient.getMarketSnapshot(ticker) ?: return@forEach

                    val tech = techAgent.analyze(ticker, candles, snapshot, cycleId)
                    val fund = fundAgent.analyze(ticker, cycleId)
                    val draft = stratAgent.formulate(ticker, tech, fund, snapshot, cycleId)
                    val challenge = contrAgent.challenge(draft, tech, fund, snapshot, cycleId)

                    val adaptiveConf = adaptiveRisk.getAdaptiveConfidenceThreshold(ticker)
                    val fb = globalFeedback[ticker]
                    val final = arbAgent.adjudicate(
                        draft, challenge, tech, fund, snapshot, cycleId,
                        contextPrompt = fb?.contextPrompt,
                        adaptiveConfidence = adaptiveConf
                    )

                    val atr = BigDecimal.valueOf(tech.atr)
                    val direction = if (final.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
                    val adaptiveSL = adaptiveRisk.calculateAdaptiveSL(final.targetPrice, direction, ticker, atr)
                    val adaptiveTP = adaptiveRisk.calculateAdaptiveTP(final.targetPrice, direction, ticker, atr)
                    val effectiveConfidence = final.confidence.coerceAtLeast(adaptiveConf)

                    val strategy = Strategy(
                        ticker = ticker,
                        action = final.action,
                        targetPrice = final.targetPrice,
                        quantity = final.quantity,
                        stopLoss = adaptiveSL,
                        takeProfit = adaptiveTP,
                        trailingStop = final.trailingStop,
                        confidence = effectiveConfidence,
                        reasoning = final.reasoning + " | Meta: confAdj=${fb?.confidenceAdjustment ?: 0.0}, SL/TP adapted, atr=${atr}",
                        rawJson = objectMapper.writeValueAsString(final),
                        cycleId = cycleId,
                        validUntil = LocalDateTime.now().plusMinutes(10)
                    )

                    strategyRepo.save(strategy)
                    redis.saveStrategy(strategy)
                    meterRegistry.counter("strategy.saved", Tags.of("ticker", ticker, "action", final.action.name)).increment()
                    logger.info { "Strategy $ticker: ${final.action} @ ${final.targetPrice} (adaptive conf=$adaptiveConf, atr=$atr)" }
                } catch (e: Exception) {
                    logger.error(e) { "Strategy error $ticker" }
                    meterRegistry.counter("strategy.error", Tags.of("ticker", ticker)).increment()
                }
            }
        }
    }

    fun runStrategyCycle() = run()

    private suspend fun loadCandles(ticker: String): List<Candle> {
        val from = LocalDateTime.now().minusDays(7)
        val db = candleRepo.findByTickerAndTimeframeAndTimeBetween(ticker, tradingConfig.timeframe, from, LocalDateTime.now())
        if (db.size >= 50) return db.sortedBy { it.time }
        val moex = moexClient.getCandles(ticker, 10, from)
        moex.forEach { if (!candleRepo.existsByTickerAndTimeframeAndTime(it.ticker, it.timeframe, it.time)) candleRepo.save(it) }
        return moex.sortedBy { it.time }
    }
}
