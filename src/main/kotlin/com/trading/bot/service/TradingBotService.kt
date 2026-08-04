package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorWebSocketClient
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.infrastructure.db.BlockingDb
import com.trading.bot.model.AgentLog
import com.trading.bot.model.ExecutionReport
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.OrderStatus
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.Strategy
import com.trading.bot.model.StrategyAction
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Исполнительный сервис торгового бота (акции/валюты).
 *
 * - Котировки: real-time через WebSocket [AlorWebSocketClient.subscribeToQuotes];
 *   [pollMarketData] остаётся деградированным fallback (SIMULATION / нет WS).
 * - Все критичные операции (вход/выход/исполнение) — через доменные события.
 */
@Service
class TradingBotService(
    private val tradingConfig: TradingConfig,
    private val alorClient: AlorClient,
    private val alorWsClient: AlorWebSocketClient,
    private val orderOutboxService: OrderOutboxService,
    private val redis: RedisCacheService,
    private val risk: RiskManagementService,
    private val settingsService: SettingsService,
    private val adaptiveRisk: AdaptiveRiskService,
    private val positionRepo: PositionRepository,
    private val agentLogRepo: AgentLogRepository,
    private val tradeEventService: TradeEventService,
    private val eventPublisher: TradingEventPublisher,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Время последнего WS-тика по тикеру — используется для отключения поллинга. */
    private val lastWsTickAt = ConcurrentHashMap<String, Instant>()

    /** Текущий P&L открытых позиций (Gauge position.pnl, обновляется на каждом тике). */
    private val positionPnlGauges = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val realizedPnlGauges = ConcurrentHashMap<String, AtomicReference<Double>>()

    /** Сериализует глобальную проверку лимитов и открытие позиции. */
    private val entryMutex = Mutex()

    /** Не допускает двух одновременных закрытий одной позиции на соседних WS-тиках. */
    private val monitorMutexes = ConcurrentHashMap<String, Mutex>()

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
        if (tradingConfig.wsQuotesEnabled) {
            scope.launch {
                alorWsClient.subscribeToQuotes(tradingConfig.tickers).collect { tick ->
                    try {
                        lastWsTickAt[tick.ticker] = Instant.now()
                        meterRegistry
                            .timer("alor.ws.message.lag", Tags.of("ticker", tick.ticker))
                            .record(Duration.between(tick.receivedAt, Instant.now()).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        eventPublisher.publishPriceChanged(tick.ticker, tick.price)
                    } catch (e: Exception) {
                        logger.error(e) { "WS quote processing error for ${tick.ticker}" }
                    }
                }
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel("TradingBotService is shutting down")
    }

    /**
     * Поллинг котировок — fallback, когда WS неактивен (SIMULATION, нет токена).
     * Если по тикеру был WS-тик за последние monitorIntervalMs — пропускаем тикер.
     */
    @Scheduled(fixedDelayString = "#{@tradingConfig.monitorIntervalMs}")
    fun pollMarketData() {
        scope.launch {
            val now = Instant.now()
            val open = positionRepo.findByStatus(PositionStatus.OPEN)
            open
                .map { it.ticker }
                .distinct()
                .filter { ticker ->
                    val lastWs = lastWsTickAt[ticker]
                    lastWs == null || Duration.between(lastWs, now).toMillis() >= tradingConfig.monitorIntervalMs
                }.forEach { ticker ->
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
                if (!settingsService.isTradingEnabled()) {
                    logger.info { "Trading disabled, skip entry ${strat.ticker}" }
                    return@launch
                }
                if (risk.isDailyLossLimitReached()) {
                    logger.warn { "Daily loss limit reached, skip entry ${strat.ticker}" }
                    return@launch
                }
                val open = positionRepo.findByStatus(PositionStatus.OPEN)
                if (open.any { it.ticker == strat.ticker }) return@launch
                val openSpotCount = open.count { it.instrumentType != InstrumentType.FUTURES }
                if (openSpotCount >= tradingConfig.maxOpenPositionsForNewEntry) {
                    logger.info {
                        "Open positions $openSpotCount >= max ${tradingConfig.maxOpenPositionsForNewEntry}, skip ${strat.ticker}"
                    }
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
                entryMutex.withLock {
                    if (settingsService.isTradingEnabled()) {
                        openPosition(event.strategy)
                    }
                }
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
            val handlerStart = System.nanoTime()
            try {
                monitorMutexes.computeIfAbsent(event.ticker) { Mutex() }.withLock {
                    val open =
                        positionRepo
                            .findByStatus(PositionStatus.OPEN)
                            .filter { it.ticker == event.ticker && it.instrumentType != InstrumentType.FUTURES }
                    open.forEach { position -> monitorPosition(position, event.price) }
                }
            } catch (e: Exception) {
                logger.error(e) { "Price change handler error ${event.ticker}" }
                meterRegistry.counter("bot.monitor.error", Tags.of("ticker", event.ticker)).increment()
            } finally {
                meterRegistry
                    .timer("bot.latency", Tags.of("ticker", event.ticker))
                    .record(System.nanoTime() - handlerStart, java.util.concurrent.TimeUnit.NANOSECONDS)
            }
        }
    }

    private suspend fun monitorPosition(
        position: Position,
        price: BigDecimal,
    ) {
        position.currentPrice = price
        val pnl =
            when (position.direction) {
                PositionDirection.LONG -> price.subtract(position.entryPrice).multiply(BigDecimal(position.quantity))
                PositionDirection.SHORT -> position.entryPrice.subtract(price).multiply(BigDecimal(position.quantity))
            }
        position.pnl = pnl
        updatePositionPnlGauge(position.ticker, pnl.toDouble())

        val closeReason =
            when {
                risk.shouldCloseBySL(position, price) -> "STOP_LOSS"
                risk.shouldCloseByTP(position, price) -> "TAKE_PROFIT"
                risk.shouldCloseByTrailing(position, price) -> "TRAILING_STOP"
                else -> null
            }
        if (closeReason != null) {
            closePosition(position, price, closeReason)
            return
        }

        risk.updateTrailingStop(position, price)
        var slUpdated = false
        var tpUpdated = false
        BlockingDb.io { redis.getStrategy(position.ticker) }?.let { strategy ->
            if (strategy.action == StrategyAction.CLOSE) {
                closePosition(position, price, "STRATEGY_CLOSE")
                return
            }
            strategy.stopLoss?.let { newStopLoss ->
                val currentStopLoss = position.stopLoss
                val improvesProtection =
                    when (position.direction) {
                        PositionDirection.LONG -> currentStopLoss == null || newStopLoss > currentStopLoss
                        PositionDirection.SHORT -> currentStopLoss == null || newStopLoss < currentStopLoss
                    }
                if (improvesProtection) {
                    position.stopLoss = newStopLoss
                    slUpdated = true
                    logger.info { "SL updated ${position.ticker} -> $newStopLoss" }
                }
            }
            strategy.takeProfit?.let { newTakeProfit ->
                val currentTakeProfit = position.takeProfit
                val extendsTarget =
                    when (position.direction) {
                        PositionDirection.LONG -> currentTakeProfit == null || newTakeProfit > currentTakeProfit
                        PositionDirection.SHORT -> currentTakeProfit == null || newTakeProfit < currentTakeProfit
                    }
                if (extendsTarget) {
                    position.takeProfit = newTakeProfit
                    tpUpdated = true
                    logger.info { "TP updated ${position.ticker} -> $newTakeProfit" }
                }
            }
        }
        positionRepo.save(position)
        if (slUpdated || tpUpdated) {
            tradeEventService.recordPositionUpdated(position)
        }
    }

    /**
     * Обновляет Gauge position.pnl для тикера (регистрируется один раз на тикер).
     */
    private fun updatePositionPnlGauge(
        ticker: String,
        pnl: Double,
    ) {
        positionPnlGauges
            .computeIfAbsent(ticker) { t ->
                val ref = AtomicReference(0.0)
                meterRegistry.gauge("position.pnl", Tags.of("ticker", t), ref) { it.get() }
                ref
            }.set(pnl)
    }

    private fun updateRealizedPnlGauge(
        ticker: String,
        pnl: Double,
    ) {
        realizedPnlGauges
            .computeIfAbsent(ticker) { t ->
                val ref = AtomicReference(0.0)
                meterRegistry.gauge("bot.pnl", Tags.of("ticker", t), ref) { it.get() }
                ref
            }.set(pnl)
    }

    /**
     * ExecutionReportEvent → фиксация фактического исполнения (closePrice, P&L, slippage).
     */
    @EventListener
    fun onExecutionReport(event: com.trading.bot.event.ExecutionReportEvent) {
        scope.launch {
            try {
                applyExecutionReport(event.report)
            } catch (e: Exception) {
                logger.error(e) { "Execution report handler error for order ${event.report.orderId}" }
            }
        }
    }

    /**
     * Ручной триггер (API /bot/trigger): публикует EntrySignalEvent для текущих стратегий.
     */
    fun runBotCycle() {
        if (!settingsService.isTradingEnabled()) {
            logger.info { "Bot cycle skipped: trading is disabled" }
            return
        }
        logger.info { "=== BOT CYCLE (manual trigger) ===" }
        meterRegistry.counter("bot.cycle").increment()
        scope.launch {
            val strategies = BlockingDb.io { redis.getAllStrategies(tradingConfig.tickers) }
            strategies.values.forEach { eventPublisher.publishStrategyGenerated(it) }
        }
    }

    private suspend fun openPosition(strat: Strategy) {
        if (strat.validUntil.isBefore(LocalDateTime.now())) {
            logger.info { "Expired strategy ignored for ${strat.ticker}" }
            return
        }
        if (strat.targetPrice <= BigDecimal.ZERO) {
            logger.warn { "Invalid target price for ${strat.ticker}: ${strat.targetPrice}" }
            return
        }

        val open = positionRepo.findByStatus(PositionStatus.OPEN)
        if (open.any { it.ticker.equals(strat.ticker, ignoreCase = true) }) {
            logger.info { "Position already open for ${strat.ticker}" }
            return
        }
        val check = risk.validateNewStrategy(strat, open)
        if (!check.allowed) {
            logger.warn { "Risk reject ${strat.ticker}: ${check.reason}" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", strat.ticker)).increment()
            return
        }
        if (adaptiveRisk.exceedsCorrelationLimit(strat.ticker, open)) {
            logger.warn { "Correlation filter reject ${strat.ticker}: correlated with an open position" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", strat.ticker, "reason", "CORRELATION")).increment()
            return
        }

        val kellySizeRub = adaptiveRisk.calculateOptimalPositionSize(strat.ticker)
        if (kellySizeRub <= BigDecimal.ZERO) {
            logger.warn { "Kelly sizing rejected ${strat.ticker}: non-positive edge" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", strat.ticker, "reason", "KELLY_ZERO")).increment()
            return
        }
        val kellyQty = kellySizeRub.divide(strat.targetPrice, 0, RoundingMode.DOWN).toInt()
        val requestedQty = check.adjustedQty.takeIf { it > 0 } ?: strat.quantity
        val qty = minOf(kellyQty, requestedQty)
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

        val newPosition =
            Position(
                ticker = strat.ticker,
                direction = dir,
                quantity = qty,
                entryPrice = fillPrice,
                currentPrice = fillPrice,
                stopLoss = strat.stopLoss ?: risk.calcSL(fillPrice, dir),
                takeProfit = strat.takeProfit ?: risk.calcTP(fillPrice, dir),
                trailingStopPrice = if (strat.trailingStop) strat.stopLoss else null,
                alorOrderId = orderId,
            )
        val pos = positionRepo.save(newPosition)
        tradeEventService.recordPositionOpened(pos)
        eventPublisher.publishPositionOpened(pos)
        agentLogRepo.save(
            AgentLog(
                cycleId = strat.cycleId,
                agentName = "TradingBot",
                ticker = strat.ticker,
                action = "OPEN",
                confidence = strat.confidence,
                reasoning = "Opened ${dir.name} $qty @ $fillPrice (target=${strat.targetPrice}, adaptive qty=$qty, kelly=$kellyQty)",
            ),
        )
        meterRegistry.counter("bot.position.opened", Tags.of("ticker", strat.ticker, "direction", dir.name)).increment()
        logger.info { "Opened ${strat.ticker} ${dir.name} $qty @ $fillPrice (adaptive qty=$qty)" }
    }

    private suspend fun closePosition(
        pos: Position,
        price: BigDecimal,
        reason: String,
    ) {
        val side =
            when (pos.direction) {
                PositionDirection.LONG -> "sell"
                PositionDirection.SHORT -> "buy"
            }
        val placed = orderOutboxService.placeOrder(pos.ticker, side, pos.quantity, null, "market")
        val orderId = placed.alorOrderId
        if (!placed.success || orderId == null) {
            logger.error { "Close order failed for ${pos.ticker}; position remains OPEN" }
            meterRegistry.counter("bot.order.failed", Tags.of("ticker", pos.ticker, "operation", "CLOSE")).increment()
            return
        }
        val execution = alorClient.verifyOrder(orderId, expectedPrice = price)
        val closePrice = execution?.avgPrice ?: price
        pos.status =
            when (reason) {
                "TAKE_PROFIT" -> PositionStatus.TAKE_PROFIT
                else -> PositionStatus.CLOSED
            }
        pos.closedAt = LocalDateTime.now()
        pos.closePrice = closePrice
        pos.closeReason = reason
        val pnl =
            when (pos.direction) {
                PositionDirection.LONG -> closePrice.subtract(pos.entryPrice).multiply(BigDecimal(pos.quantity))
                PositionDirection.SHORT -> pos.entryPrice.subtract(closePrice).multiply(BigDecimal(pos.quantity))
            }
        pos.pnl = pnl
        positionRepo.save(pos)
        tradeEventService.recordPositionClosed(pos, reason)
        risk.updateDailyPnL(pnl)
        eventPublisher.publishPositionClosed(pos)
        updatePositionPnlGauge(pos.ticker, 0.0)
        updateRealizedPnlGauge(pos.ticker, pnl.toDouble())
        meterRegistry.counter("bot.position.closed", Tags.of("ticker", pos.ticker, "reason", reason)).increment()
        logger.info { "Closed ${pos.ticker} reason=$reason P&L=$pnl" }
    }

    /**
     * Применяет отчёт по ордеру открытия.
     *
     * [Position.alorOrderId] хранит именно id входного ордера. Старое поведение
     * ошибочно трактовало его FILLED-событие как закрытие позиции. Теперь отчёт
     * уточняет цену/количество входа и оставляет позицию открытой.
     */
    private suspend fun applyExecutionReport(report: ExecutionReport) {
        if (report.status != OrderStatus.FILLED && report.status != OrderStatus.PARTIALLY_FILLED) return
        val pos = positionRepo.findByAlorOrderId(report.orderId) ?: return
        if (pos.status != PositionStatus.OPEN || pos.closedAt != null) return
        if (pos.instrumentType == InstrumentType.FUTURES) return

        val fillPrice = report.avgPrice ?: return
        val expectedPrice = pos.entryPrice
        pos.entryPrice = fillPrice
        pos.currentPrice = fillPrice
        if (report.filledQty > 0) {
            pos.quantity = report.filledQty
        }
        positionRepo.save(pos)
        tradeEventService.recordPositionUpdated(pos)
        alorClient.recordSlippage(expectedPrice, fillPrice, pos.quantity)
        meterRegistry.counter("bot.ws.fill_applied", Tags.of("ticker", pos.ticker)).increment()
        logger.info {
            "WS entry fill applied for ${pos.ticker}: order=${report.orderId} " +
                "price=$fillPrice qty=${pos.quantity} status=${report.status}"
        }
    }
}
