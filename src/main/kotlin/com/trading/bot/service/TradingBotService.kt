package com.trading.bot.service

import com.trading.bot.client.AlorClient
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
import java.math.RoundingMode
import java.time.LocalDateTime

@Service
class TradingBotService(
    private val tradingConfig: TradingConfig,
    private val alorClient: AlorClient,
    private val redis: RedisCacheService,
    private val risk: RiskManagementService,
    private val adaptiveRisk: AdaptiveRiskService,
    private val positionRepo: PositionRepository,
    private val agentLogRepo: AgentLogRepository,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Scheduled(fixedDelayString = "#{@tradingConfig.botIntervalMs}")
    fun run() {
        logger.info { "=== BOT CYCLE ===" }
        meterRegistry.counter("bot.cycle").increment()
        scope.launch {
            try {
                if (risk.isDailyLossLimitReached()) {
                    logger.warn { "Daily loss limit reached (${risk.getDailyPnL()}), trading halted" }
                    meterRegistry.counter("bot.halted.daily_loss").increment()
                    return@launch
                }
                val open = positionRepo.findByStatus(PositionStatus.OPEN)
                if (open.size <= tradingConfig.maxOpenPositionsForNewEntry) {
                    val strategies = redis.getAllStrategies(tradingConfig.tickers)
                    strategies.values.filter {
                        it.action == StrategyAction.BUY || it.action == StrategyAction.SELL
                    }.forEach { strat ->
                        if (open.none { it.ticker == strat.ticker }) openPosition(strat)
                    }
                } else {
                    logger.info { "Open positions ${open.size} > max ${tradingConfig.maxOpenPositionsForNewEntry}, skipping new entries" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Bot cycle error" }
                meterRegistry.counter("bot.cycle.error").increment()
            }
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
                    val pnl = when (pos.direction) {
                        PositionDirection.LONG -> price.subtract(pos.entryPrice).multiply(BigDecimal(pos.quantity))
                        PositionDirection.SHORT -> pos.entryPrice.subtract(price).multiply(BigDecimal(pos.quantity))
                    }
                    pos.pnl = pnl

                    if (risk.shouldCloseBySL(pos, price)) { closePosition(pos, price, "STOP_LOSS"); return@forEach }
                    if (risk.shouldCloseByTP(pos, price)) { closePosition(pos, price, "TAKE_PROFIT"); return@forEach }
                    if (risk.shouldCloseByTrailing(pos, price)) { closePosition(pos, price, "TRAILING_STOP"); return@forEach }

                    risk.updateTrailingStop(pos, price)

                    redis.getStrategy(pos.ticker)?.let { strat ->
                        if (strat.action == StrategyAction.CLOSE) {
                            closePosition(pos, price, "STRATEGY_CLOSE")
                            return@forEach
                        }
                        strat.stopLoss?.let { newSL ->
                            val shouldUpd = when (pos.direction) {
                                PositionDirection.LONG -> pos.stopLoss == null || newSL > pos.stopLoss!!
                                PositionDirection.SHORT -> pos.stopLoss == null || newSL < pos.stopLoss!!
                            }
                            if (shouldUpd) { pos.stopLoss = newSL; logger.info { "SL updated ${pos.ticker} -> $newSL" } }
                        }
                        strat.takeProfit?.let { newTP ->
                            val shouldUpd = when (pos.direction) {
                                PositionDirection.LONG -> pos.takeProfit == null || newTP > pos.takeProfit!!
                                PositionDirection.SHORT -> pos.takeProfit == null || newTP < pos.takeProfit!!
                            }
                            if (shouldUpd) { pos.takeProfit = newTP; logger.info { "TP updated ${pos.ticker} -> $newTP" } }
                        }
                    }
                    positionRepo.save(pos)
                } catch (e: Exception) {
                    logger.error(e) { "Monitor error ${pos.ticker}" }
                    meterRegistry.counter("bot.monitor.error", Tags.of("ticker", pos.ticker)).increment()
                }
            }
        }
    }

    private suspend fun openPosition(strat: Strategy) {
        val open = positionRepo.findByStatus(PositionStatus.OPEN)
        val check = risk.validateNewStrategy(strat, open)
        if (!check.allowed) {
            logger.warn { "Risk reject ${strat.ticker}: ${check.reason}" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", strat.ticker)).increment()
            return
        }

        val kellySizeRub = adaptiveRisk.calculateOptimalPositionSize(strat.ticker)
        val kellyQty = if (kellySizeRub > BigDecimal.ZERO) {
            kellySizeRub.divide(strat.targetPrice, 0, RoundingMode.DOWN).toInt().coerceAtLeast(1)
        } else {
            0
        }

        val qty = if (kellyQty > 0 && kellyQty < strat.quantity) kellyQty else (check.adjustedQty.takeIf { it > 0 } ?: strat.quantity)
        if (qty <= 0) {
            logger.warn { "Zero quantity for ${strat.ticker} after adaptive sizing" }
            return
        }

        val dir = if (strat.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val side = if (strat.action == StrategyAction.BUY) "buy" else "sell"
        val orderId = alorClient.placeLimitOrder(strat.ticker, side, qty, strat.targetPrice)
            ?: alorClient.placeMarketOrder(strat.ticker, side, qty)
        if (orderId == null) {
            logger.error { "Order failed ${strat.ticker}" }
            meterRegistry.counter("bot.order.failed", Tags.of("ticker", strat.ticker)).increment()
            return
        }

        val execution = alorClient.verifyOrder(orderId)
        val fillPrice = execution?.avgPrice ?: strat.targetPrice
        logger.info { "Order $orderId for ${strat.ticker} verified: status=${execution?.status}, fillPrice=$fillPrice" }

        val pos = Position(
            ticker = strat.ticker,
            direction = dir,
            quantity = qty,
            entryPrice = fillPrice,
            currentPrice = fillPrice,
            stopLoss = strat.stopLoss ?: risk.calcSL(fillPrice, dir),
            takeProfit = strat.takeProfit ?: risk.calcTP(fillPrice, dir),
            trailingStopPrice = if (strat.trailingStop) strat.stopLoss else null,
            alorOrderId = orderId
        )
        positionRepo.save(pos)
        risk.updateDailyPnL(BigDecimal.ZERO)
        agentLogRepo.save(
            AgentLog(
                cycleId = strat.cycleId,
                agentName = "TradingBot",
                ticker = strat.ticker,
                action = "OPEN",
                confidence = strat.confidence,
                reasoning = "Opened ${dir.name} $qty @ $fillPrice (target=${strat.targetPrice}, adaptive qty=$qty, kelly=$kellyQty)"
            )
        )
        meterRegistry.counter("bot.position.opened", Tags.of("ticker", strat.ticker, "direction", dir.name)).increment()
        logger.info { "Opened ${strat.ticker} ${dir.name} $qty @ $fillPrice (adaptive qty=$qty)" }
    }

    private suspend fun closePosition(pos: Position, price: BigDecimal, reason: String) {
        val side = when (pos.direction) {
            PositionDirection.LONG -> "sell"
            PositionDirection.SHORT -> "buy"
        }
        val orderId = alorClient.placeMarketOrder(pos.ticker, side, pos.quantity)
        val execution = orderId?.let { alorClient.verifyOrder(it) }
        val closePrice = execution?.avgPrice ?: price
        pos.status = when (reason) {
            "TAKE_PROFIT" -> PositionStatus.TAKE_PROFIT
            else -> PositionStatus.CLOSED
        }
        pos.closedAt = LocalDateTime.now()
        pos.closePrice = closePrice
        pos.closeReason = reason
        val pnl = when (pos.direction) {
            PositionDirection.LONG -> closePrice.subtract(pos.entryPrice).multiply(BigDecimal(pos.quantity))
            PositionDirection.SHORT -> pos.entryPrice.subtract(closePrice).multiply(BigDecimal(pos.quantity))
        }
        pos.pnl = pnl
        positionRepo.save(pos)
        risk.updateDailyPnL(pnl)
        meterRegistry.counter("bot.position.closed", Tags.of("ticker", pos.ticker, "reason", reason)).increment()
        meterRegistry.gauge("bot.pnl", Tags.of("ticker", pos.ticker), pnl.toDouble())
        logger.info { "Closed ${pos.ticker} reason=$reason P&L=$pnl" }
    }

    fun runBotCycle() = run()
}
