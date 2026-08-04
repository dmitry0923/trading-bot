package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorWebSocketClient
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.model.*
import com.trading.bot.repository.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

@Service
class TradingBotService(
    private val tradingConfig: TradingConfig,
    private val alorClient: AlorClient,
    private val alorWsClient: AlorWebSocketClient,
    private val orderOutboxService: OrderOutboxService,
    private val redis: RedisCacheService,
    private val risk: RiskManagementService,
    private val adaptiveRisk: AdaptiveRiskService,
    private val positionRepo: PositionRepository,
    private val agentLogRepo: AgentLogRepository,
    private val eventPublisher: TradingEventPublisher,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            alorWsClient.subscribeToOrders().collect { report ->
                try {
                    eventPublisher.publishExecutionReport(report)
                } catch (e: Exception) {
                    logger.error(e) { "WS execution processing error for order ${report.orderId}" }
                }
            }
        }
    }

    /**
     * Поллинг котировок — единственный допустимый @Scheduled (WS по котировкам
     * отсутствует). Критичные операции (вход/выход/исполнение) — только через события.
     */
    @Scheduled(fixedDelayString = "#{@tradingConfig.monitorIntervalMs}")
    fun pollMarketData() {
        scope.launch {
            val open = positionRepo.findByStatus(PositionStatus.OPEN)
            open.map { it.ticker }.distinct().forEach { ticker ->
                try {
                    val price = alorClient.getLastPrice(ticker) ?: return@forEach
                    eventPublisher.publishPriceChanged(ticker, price)
                } catch (e: Exception) {
                    logger.error(e) { "Price poll error $ticker" }
                    meterRegistry.counter("bot.price.poll.error", Tags.of("ticker", ticker)).increment()
                }
            }
        }
    }

    /**
     * StrategyGeneratedEvent → если сигнал пригоден и нет открытой позиции → EntrySignalEvent.
     */
    @EventListener
    fun onStrategyGenerated(event: com.trading.bot.event.StrategyGeneratedEvent) {
        val strat = event.strategy
        if (strat.ticker == "Si") return // фьючерсы обрабатывает FuturesTradingBotService
        if (strat.action != StrategyAction.BUY && strat.action != StrategyAction.SELL) return
        scope.launch {
            try {
                if (risk.isDailyLossLimitReached()) {
                    logger.warn { "Daily loss limit reached, skip entry ${strat.ticker}" }
                    return@launch
                }
                val open = positionRepo.findByStatus(PositionStatus.OPEN)
                if (open.any { it.ticker == strat.ticker }) return@launch
                if (open.size > tradingConfig.maxOpenPositionsForNewEntry) {
                    logger.info { "Open positions ${open.size} > max ${tradingConfig.maxOpenPositionsForNewEntry}, skip ${strat.ticker}" }
                    return@launch
                }
                eventPublisher.publishEntrySignal(strat)
            } catch (e: Exception) {
                logger.error(e) { "Strategy generated handler error ${strat.ticker}" }
            }
        }
    }

    /**
     * EntrySignalEvent → RiskEngine.assessEntry() + открытие позиции.
     */
    @EventListener
    fun onEntrySignal(event: com.trading.bot.event.EntrySignalEvent) {
        scope.launch {
            try {
                openPosition(event.strategy)
            } catch (e: Exception) {
                logger.error(e) { "Entry signal handler error ${event.strategy.ticker}" }
                meterRegistry.counter("bot.entry.error", Tags.of("ticker", event.strategy.ticker)).increment()
            }
        }
    }

    /**
     * PriceChangedEvent → мониторинг открытых позиций (SL/TP/trailing/STRATEGY_CLOSE).
     */
    @EventListener
    fun onPriceChanged(event: com.trading.bot.event.PriceChangedEvent) {
        scope.launch {
            try {
                val open = positionRepo.findByStatus(PositionStatus.OPEN)
                    .filter { it.ticker == event.ticker && it.instrumentType != InstrumentType.FUTURES }
                open.forEach { pos ->
                    val price = event.price
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
                }
            } catch (e: Exception) {
                logger.error(e) { "Price change handler error ${event.ticker}" }
                meterRegistry.counter("bot.monitor.error", Tags.of("ticker", event.ticker)).increment()
            }
        }
    }

    /**
     * ExecutionReportEvent → фиксация фактического исполнения (closePrice, P&L, slippage).
     */
    @EventListener
    fun onExecutionReport(event: com.trading.bot.event.ExecutionReportEvent) {
        try {
            applyExecutionReport(event.report)
        } catch (e: Exception) {
            logger.error(e) { "Execution report handler error for order ${event.report.orderId}" }
        }
    }

    /**
     * Ручной триггер (API /bot/trigger): публикует EntrySignalEvent для текущих стратегий.
     */
    fun runBotCycle() {
        logger.info { "=== BOT CYCLE (manual trigger) ===" }
        meterRegistry.counter("bot.cycle").increment()
        val strategies = redis.getAllStrategies(tradingConfig.tickers)
        strategies.values.forEach { eventPublisher.publishStrategyGenerated(it) }
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
        val placed = orderOutboxService.placeOrder(strat.ticker, side, qty, strat.targetPrice, "limit")
        if (!placed.success || placed.alorOrderId == null) {
            logger.error { "Order failed ${strat.ticker}" }
            meterRegistry.counter("bot.order.failed", Tags.of("ticker", strat.ticker)).increment()
            return
        }
        val orderId = placed.alorOrderId

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
        val placed = orderOutboxService.placeOrder(pos.ticker, side, pos.quantity, null, "market")
        val orderId = placed.alorOrderId
        val execution = orderId?.let { alorClient.verifyOrder(it, expectedPrice = price) }
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

    /**
     * Применяет ExecutionReport из WebSocket: фиксирует фактическую цену
     * исполнения в closePrice открытой позиции (slippage tracking).
     */
    private fun applyExecutionReport(report: ExecutionReport) {
        if (report.status != OrderStatus.FILLED && report.status != OrderStatus.PARTIALLY_FILLED) return
        val orderId = report.orderId
        val pos = positionRepo.findByAlorOrderId(orderId) ?: return
        if (pos.status != PositionStatus.OPEN || pos.closedAt != null) return
        if (pos.instrumentType == InstrumentType.FUTURES) return // фьючерсы обрабатывает FuturesTradingBotService

        val fillPrice = report.avgPrice ?: return
        pos.closePrice = fillPrice
        val pnl = when (pos.direction) {
            PositionDirection.LONG -> fillPrice.subtract(pos.entryPrice).multiply(BigDecimal(pos.quantity))
            PositionDirection.SHORT -> pos.entryPrice.subtract(fillPrice).multiply(BigDecimal(pos.quantity))
        }
        pos.pnl = pnl
        pos.status = if (report.status == OrderStatus.PARTIALLY_FILLED) PositionStatus.OPEN else PositionStatus.CLOSED
        pos.closedAt = if (report.status == OrderStatus.PARTIALLY_FILLED) null else LocalDateTime.now()
        pos.closeReason = pos.closeReason ?: "EXECUTION_FILL"
        positionRepo.save(pos)
        alorClient.recordSlippage(pos.entryPrice, fillPrice, pos.quantity)
        meterRegistry.counter("bot.ws.fill_applied", Tags.of("ticker", pos.ticker)).increment()
        logger.info { "WS fill applied for ${pos.ticker}: order=$orderId price=$fillPrice pnl=$pnl" }
    }
}
