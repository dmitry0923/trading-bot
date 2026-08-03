package com.trading.bot.service
import com.trading.bot.client.AlorClient
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.*
import com.trading.bot.repository.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

@Service
class TradingBotService(private val tradingConfig: TradingConfig, private val alorClient: AlorClient, private val redis: RedisCacheService, private val risk: RiskManagementService, private val positionRepo: PositionRepository, private val agentLogRepo: AgentLogRepository) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Scheduled(fixedDelayString = "#{@tradingConfig.botIntervalMs}")
    fun run() {
        logger.info { "=== BOT CYCLE ===" }
        scope.launch {
            try {
                val open = positionRepo.findByStatus(PositionStatus.OPEN)
                // 1. If no open positions (or less than max), check strategy for new entry
                if (open.size <= tradingConfig.maxOpenPositionsForNewEntry) {
                    val strategies = redis.getAllStrategies(tradingConfig.tickers)
                    strategies.values.filter { it.action == StrategyAction.BUY || it.action == StrategyAction.SELL }.forEach { strat ->
                        if (open.none { it.ticker == strat.ticker }) openPosition(strat)
                    }
                } else {
                    logger.info { "Open positions ${open.size} > max ${tradingConfig.maxOpenPositionsForNewEntry}, skipping new entries" }
                }
            } catch (e: Exception) { logger.error(e) { "Bot cycle error" } }
        }
    }

    @Scheduled(fixedDelayString = "#{@tradingConfig.monitorIntervalMs}")
    fun monitor() {
        scope.launch {
            val open = positionRepo.findByStatus(PositionStatus.OPEN)
            open.forEach { pos ->
                try {
                    val price = alorClient.getLastPrice(pos.ticker) ?: return@forEach
                    pos.currentPrice = price
                    val pnl = when(pos.direction) { PositionDirection.LONG -> price.subtract(pos.entryPrice).multiply(BigDecimal(pos.quantity)); PositionDirection.SHORT -> pos.entryPrice.subtract(price).multiply(BigDecimal(pos.quantity)) }
                    pos.pnl = pnl
                    // Check SL/TP
                    if (risk.shouldCloseBySL(pos, price)) { closePosition(pos, price, "STOP_LOSS"); return@forEach }
                    if (risk.shouldCloseByTP(pos, price)) { closePosition(pos, price, "TAKE_PROFIT"); return@forEach }
                    if (risk.shouldCloseByTrailing(pos, price)) { closePosition(pos, price, "TRAILING_STOP"); return@forEach }
                    // Update trailing stop
                    risk.updateTrailingStop(pos, price)
                    // Sync with strategy from Redis
                    redis.getStrategy(pos.ticker)?.let { strat ->
                        if (strat.action == StrategyAction.CLOSE) { closePosition(pos, price, "STRATEGY_CLOSE"); return@forEach }
                        strat.stopLoss?.let { newSL ->
                            val shouldUpd = when(pos.direction) { PositionDirection.LONG -> pos.stopLoss == null || newSL > pos.stopLoss!!; PositionDirection.SHORT -> pos.stopLoss == null || newSL < pos.stopLoss!! }
                            if (shouldUpd) { pos.stopLoss = newSL; logger.info { "SL updated ${pos.ticker} -> $newSL" } }
                        }
                        strat.takeProfit?.let { newTP ->
                            val shouldUpd = when(pos.direction) { PositionDirection.LONG -> pos.takeProfit == null || newTP > pos.takeProfit!!; PositionDirection.SHORT -> pos.takeProfit == null || newTP < pos.takeProfit!! }
                            if (shouldUpd) { pos.takeProfit = newTP; logger.info { "TP updated ${pos.ticker} -> $newTP" } }
                        }
                    }
                    positionRepo.save(pos)
                } catch (e: Exception) { logger.error(e) { "Monitor error ${pos.ticker}" } }
            }
        }
    }

    private suspend fun openPosition(strat: Strategy) {
        val open = positionRepo.findByStatus(PositionStatus.OPEN)
        val check = risk.validateNewStrategy(strat, open)
        if (!check.allowed) { logger.warn { "Risk reject ${strat.ticker}: ${check.reason}" }; return }
        val qty = if (check.adjustedQty > 0) check.adjustedQty else strat.quantity
        val dir = if (strat.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val side = if (strat.action == StrategyAction.BUY) "buy" else "sell"
        val orderId = alorClient.placeLimitOrder(strat.ticker, side, qty, strat.targetPrice) ?: alorClient.placeMarketOrder(strat.ticker, side, qty)
        if (orderId == null) { logger.error { "Order failed ${strat.ticker}" }; return }
        val pos = Position(ticker = strat.ticker, direction = dir, quantity = qty, entryPrice = strat.targetPrice, currentPrice = strat.targetPrice, stopLoss = strat.stopLoss ?: risk.calcSL(strat.targetPrice, dir), takeProfit = strat.takeProfit ?: risk.calcTP(strat.targetPrice, dir), trailingStopPrice = if (strat.trailingStop) strat.stopLoss else null, alorOrderId = orderId)
        positionRepo.save(pos)
        risk.updateDailyPnL(BigDecimal.ZERO)
        agentLogRepo.save(AgentLog(cycleId = strat.cycleId, agentName = "TradingBot", ticker = strat.ticker, action = "OPEN", confidence = strat.confidence, reasoning = "Opened ${dir.name} $qty @ ${strat.targetPrice}"))
        logger.info { "Opened ${strat.ticker} ${dir.name} $qty @ ${strat.targetPrice}" }
    }

    private suspend fun closePosition(pos: Position, price: BigDecimal, reason: String) {
        val side = when(pos.direction) { PositionDirection.LONG -> "sell"; PositionDirection.SHORT -> "buy" }
        alorClient.placeMarketOrder(pos.ticker, side, pos.quantity)
        pos.status = when(reason) { "TAKE_PROFIT" -> PositionStatus.TAKE_PROFIT; else -> PositionStatus.CLOSED }
        pos.closedAt = LocalDateTime.now(); pos.closePrice = price; pos.closeReason = reason
        val pnl = when(pos.direction) { PositionDirection.LONG -> price.subtract(pos.entryPrice).multiply(BigDecimal(pos.quantity)); PositionDirection.SHORT -> pos.entryPrice.subtract(price).multiply(BigDecimal(pos.quantity)) }
        pos.pnl = pnl
        positionRepo.save(pos)
        risk.updateDailyPnL(pnl)
        logger.info { "Closed ${pos.ticker} reason=$reason P&L=$pnl" }
    }
}
