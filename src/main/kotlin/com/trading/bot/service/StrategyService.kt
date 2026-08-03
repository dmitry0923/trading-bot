package com.trading.bot.service
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.agent.*
import com.trading.bot.client.AlorClient
import com.trading.bot.client.MoexClient
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.*
import com.trading.bot.repository.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class StrategyService(private val tradingConfig: TradingConfig, private val alorClient: AlorClient, private val moexClient: MoexClient, private val techAgent: TechnicalAnalysisAgent, private val fundAgent: FundamentalAnalysisAgent, private val stratAgent: StrategyAgent, private val contrAgent: ContrarianAgent, private val arbAgent: ArbitratorAgent, private val redis: RedisCacheService, private val strategyRepo: StrategyRepository, private val candleRepo: CandleRepository, private val agentLogRepo: AgentLogRepository, private val objectMapper: ObjectMapper) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Scheduled(fixedDelayString = "#{@tradingConfig.strategyIntervalMs}")
    fun run() {
        val cycleId = UUID.randomUUID().toString()
        logger.info { "=== STRATEGY CYCLE $cycleId ===" }
        scope.launch {
            tradingConfig.tickers.forEach { ticker ->
                try {
                    val candles = loadCandles(ticker)
                    val snapshot = alorClient.getMarketSnapshot(ticker) ?: return@forEach
                    val tech = techAgent.analyze(ticker, candles, snapshot, cycleId)
                    val fund = fundAgent.analyze(ticker, cycleId)
                    val draft = stratAgent.formulate(ticker, tech, fund, snapshot, cycleId)
                    val challenge = contrAgent.challenge(draft, tech, fund, snapshot, cycleId)
                    val final = arbAgent.adjudicate(draft, challenge, tech, fund, snapshot, cycleId)
                    val strategy = Strategy(ticker = ticker, action = final.action, targetPrice = final.targetPrice, quantity = final.quantity, stopLoss = final.stopLoss, takeProfit = final.takeProfit, trailingStop = final.trailingStop, confidence = final.confidence, reasoning = final.reasoning, rawJson = objectMapper.writeValueAsString(final), cycleId = cycleId, validUntil = LocalDateTime.now().plusMinutes(10))
                    strategyRepo.save(strategy)
                    redis.saveStrategy(strategy)
                    logger.info { "Strategy $ticker: ${final.action} @ ${final.targetPrice}" }
                } catch (e: Exception) { logger.error(e) { "Strategy error $ticker" } }
            }
        }
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
