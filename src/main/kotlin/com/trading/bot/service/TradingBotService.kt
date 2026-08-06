package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorWebSocketClient
import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsConnectionStatus
import com.trading.bot.client.WsStream
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.infrastructure.db.BlockingDb
import com.trading.bot.model.AgentLog
import com.trading.bot.model.ExecutionReport
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.OrderStatus
import com.trading.bot.model.OutboxStatus
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.Strategy
import com.trading.bot.model.StrategyAction
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Исполнительный сервис торгового бота (акции/валюты).
 *
 * - Котировки: real-time через WebSocket [AlorWebSocketClient.subscribeToQuotes];
 *   [pollMarketData] остаётся деградированным fallback (SIMULATION / нет WS).
 * - Все критичные операции (вход/выход/исполнение) — через доменные события.
 *
 * Защита от double execution / потеря контроля над позицией (аналогично
 * FuturesTradingBotService): idempotency key на ордер, стейт-машина
 * pendingEntry/pendingClose, State Reconciliation через outbox + verifyOrder,
 * partial fills с дозакрытием остатка.
 */
@Service
class TradingBotService(
    private val tradingConfig: TradingConfig,
    private val alorClient: AlorClient,
    private val alorWsClient: AlorWebSocketClient,
    private val webSocketManager: WebSocketManager,
    private val orderOutboxService: OrderOutboxService,
    private val redis: RedisCacheService,
    private val risk: RiskManagementService,
    private val adaptiveRisk: AdaptiveRiskService,
    private val drawdownProtection: DrawdownProtectionService,
    private val volatilityIndexService: VolatilityIndexService,
    private val positionRepo: PositionRepository,
    private val orderOutboxRepo: OrderOutboxRepository,
    private val alorConfig: AlorConfig,
    private val agentLogRepo: AgentLogRepository,
    private val tradeEventService: TradeEventService,
    private val eventPublisher: TradingEventPublisher,
    private val tradingGate: com.trading.bot.application.TradingGate,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Время последнего WS-тика по тикеру — используется для отключения поллинга. */
    private val lastWsTickAt = ConcurrentHashMap<String, Instant>()

    /** Текущий P&L открытых позиций (Gauge position.pnl, обновляется на каждом тике). */
    private val positionPnlGauges = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicReference<Double>>()

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
        // При обрыве WS-котировок сбрасываем кэш последних WS-тиков: fallback-поллинг
        // [pollMarketData] возобновляется немедленно (без ожидания monitorIntervalMs),
        // пока реконнект не восстановит real-time поток.
        scope.launch {
            webSocketManager.events.collect { event ->
                if (event.stream == WsStream.QUOTES && event.status == WsConnectionStatus.DISCONNECTED) {
                    lastWsTickAt.clear()
                }
            }
        }
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
        if (!tradingGate.isTradingEnabled()) {
            logger.info { "Trading disabled (single flag) — entry skipped ${strat.ticker}" }
            return
        }
        scope.launch {
            try {
                if (risk.isDailyLossLimitReached()) {
                    logger.warn { "Daily loss limit reached, skip entry ${strat.ticker}" }
                    return@launch
                }
                if (drawdownProtection.isEntryBlocked()) {
                    logger.warn { "Drawdown protection, skip entry ${strat.ticker}: ${drawdownProtection.entryBlockReason()}" }
                    return@launch
                }
                if (volatilityIndexService.isVolatilityAnomalous()) {
                    logger.warn { "Volatility index pause, skip entry ${strat.ticker}" }
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
            val handlerStart = System.nanoTime()
            try {
                val open =
                    positionRepo
                        .findByStatus(PositionStatus.OPEN)
                        .filter { it.ticker == event.ticker && it.instrumentType != InstrumentType.FUTURES }
                open.forEach { pos ->
                    // Позиции, ожидающие подтверждения входа/закрытия, обрабатывает реконсилятор
                    // (SL/TP на них не срабатывают — исключаем двойные ордера).
                    if (pos.pendingEntry || pos.pendingClose) return@forEach
                    val price = event.price
                    pos.currentPrice = price
                    val pnl =
                        when (pos.direction) {
                            PositionDirection.LONG -> price.subtract(pos.entryPrice).multiply(BigDecimal(pos.quantity))
                            PositionDirection.SHORT -> pos.entryPrice.subtract(price).multiply(BigDecimal(pos.quantity))
                        }
                    pos.pnl = pnl
                    updatePositionPnlGauge(pos.ticker, pnl.toDouble())

                    if (risk.shouldCloseBySL(pos, price)) {
                        closePosition(pos, price, "STOP_LOSS")
                        return@forEach
                    }
                    if (risk.shouldCloseByTP(pos, price)) {
                        closePosition(pos, price, "TAKE_PROFIT")
                        return@forEach
                    }
                    if (risk.shouldCloseByTrailing(pos, price)) {
                        closePosition(pos, price, "TRAILING_STOP")
                        return@forEach
                    }

                    risk.updateTrailingStop(pos, price)

                    var slUpdated = false
                    var tpUpdated = false
                    BlockingDb.io { redis.getStrategy(pos.ticker) }?.let { strat ->
                        if (strat.action == StrategyAction.CLOSE) {
                            closePosition(pos, price, "STRATEGY_CLOSE")
                            return@forEach
                        }
                        strat.stopLoss?.let { newSL ->
                            val shouldUpd =
                                when (pos.direction) {
                                    PositionDirection.LONG -> pos.stopLoss == null || newSL > pos.stopLoss!!
                                    PositionDirection.SHORT -> pos.stopLoss == null || newSL < pos.stopLoss!!
                                }
                            if (shouldUpd) {
                                pos.stopLoss = newSL
                                logger.info { "SL updated ${pos.ticker} -> $newSL" }
                                slUpdated = true
                            }
                        }
                        strat.takeProfit?.let { newTP ->
                            val shouldUpd =
                                when (pos.direction) {
                                    PositionDirection.LONG -> pos.takeProfit == null || newTP > pos.takeProfit!!
                                    PositionDirection.SHORT -> pos.takeProfit == null || newTP < pos.takeProfit!!
                                }
                            if (shouldUpd) {
                                pos.takeProfit = newTP
                                logger.info { "TP updated ${pos.ticker} -> $newTP" }
                                tpUpdated = true
                            }
                        }
                    }
                    positionRepo.save(pos)
                    if (slUpdated || tpUpdated) {
                        tradeEventService.recordPositionUpdated(pos)
                    }
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

    /**
     * Обновляет Gauge position.pnl для тикера (регистрируется один раз на тикер).
     */
    private fun updatePositionPnlGauge(
        ticker: String,
        pnl: Double,
    ) {
        positionPnlGauges
            .computeIfAbsent(ticker) { t ->
                val ref =
                    java.util.concurrent.atomic
                        .AtomicReference(0.0)
                meterRegistry.gauge("position.pnl", Tags.of("ticker", t), ref) { it.get() }
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
        logger.info { "=== BOT CYCLE (manual trigger) ===" }
        meterRegistry.counter("bot.cycle").increment()
        scope.launch {
            val strategies = BlockingDb.io { redis.getAllStrategies(tradingConfig.tickers) }
            strategies.values.forEach { eventPublisher.publishStrategyGenerated(it) }
        }
    }

    /**
     * Принудительное закрытие всех открытых акций/валютных позиций
     * (настройка "закрыть торговлю сейчас"). Для фьючерсов — см. FuturesTradingBotService.
     *
     * @param reason причина закрытия (FORCE_CLOSE, FORCE_CLOSE_SCHEDULED и т.п.)
     * @return количество закрытых позиций
     */
    suspend fun forceCloseAll(reason: String = "FORCE_CLOSE"): Int {
        val open =
            positionRepo
                .findByStatus(PositionStatus.OPEN)
                .filter { it.instrumentType != InstrumentType.FUTURES }
        open.forEach { pos ->
            try {
                val price = alorClient.getLastPrice(pos.ticker) ?: pos.currentPrice ?: pos.entryPrice
                closePosition(pos, price, reason)
            } catch (e: Exception) {
                logger.error(e) { "Force close failed ${pos.ticker}" }
            }
        }
        logger.info { "Force close (stocks): ${open.size} positions, reason=$reason" }
        return open.size
    }

    private suspend fun openPosition(strat: Strategy) {
        val open = positionRepo.findByStatus(PositionStatus.OPEN)
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
        if (adaptiveRisk.exceedsSectorCorrelationLimit(strat.ticker, open)) {
            logger.warn { "Sector correlation filter reject ${strat.ticker}: correlated position inside same sector" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", strat.ticker, "reason", "SECTOR_CORRELATION")).increment()
            return
        }

        val kellySizeRub = adaptiveRisk.calculateOptimalPositionSize(strat.ticker)
        val kellyQty =
            if (kellySizeRub > BigDecimal.ZERO) {
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
        val candidateNotional = strat.targetPrice.multiply(BigDecimal(qty))
        if (risk.exceedsPortfolioLimits(candidateNotional, dir, open)) {
            logger.warn { "Portfolio exposure reject ${strat.ticker}: gross/net limit" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", strat.ticker, "reason", "PORTFOLIO_LIMIT")).increment()
            return
        }
        val side = if (strat.action == StrategyAction.BUY) "buy" else "sell"
        val placed = orderOutboxService.placeOrder(strat.ticker, side, qty, strat.targetPrice, "limit")
        if (!placed.success || placed.alorOrderId == null) {
            if (placed.uncertain) {
                // Запрос мог дойти до Alor → создаём позицию в состоянии pendingEntry;
                // факт исполнения подтвердит реконсилятор (State Reconciliation).
                logger.warn { "Entry for ${strat.ticker} UNCERTAIN (outbox=${placed.outboxId}); position created as pendingEntry" }
                val pos =
                    Position(
                        ticker = strat.ticker,
                        direction = dir,
                        quantity = qty,
                        entryPrice = strat.targetPrice,
                        currentPrice = strat.targetPrice,
                        stopLoss = strat.stopLoss ?: risk.calcSL(strat.targetPrice, dir),
                        takeProfit = strat.takeProfit ?: risk.calcTP(strat.targetPrice, dir),
                        trailingStopPrice = if (strat.trailingStop) strat.stopLoss else null,
                        pendingEntry = true,
                    )
                positionRepo.save(pos)
                meterRegistry.counter("bot.entry.uncertain", Tags.of("ticker", strat.ticker)).increment()
                return
            }
            logger.error { "Order failed ${strat.ticker}" }
            meterRegistry.counter("bot.order.failed", Tags.of("ticker", strat.ticker)).increment()
            return
        }
        val orderId = placed.alorOrderId

        val execution = alorClient.verifyOrder(orderId)
        val fillPrice = execution?.avgPrice ?: strat.targetPrice
        val filledQty = execution?.filledQuantity?.takeIf { it in 1 until qty }
        val actualQty = filledQty ?: qty
        logger.info { "Order $orderId for ${strat.ticker} verified: status=${execution?.status}, fillPrice=$fillPrice, qty=$actualQty" }

        val pos =
            Position(
                ticker = strat.ticker,
                direction = dir,
                quantity = actualQty,
                entryPrice = fillPrice,
                currentPrice = fillPrice,
                stopLoss = strat.stopLoss ?: risk.calcSL(fillPrice, dir),
                takeProfit = strat.takeProfit ?: risk.calcTP(fillPrice, dir),
                trailingStopPrice = if (strat.trailingStop) strat.stopLoss else null,
                alorOrderId = orderId,
            )
        positionRepo.save(pos)
        tradeEventService.recordPositionOpened(pos)
        risk.updateDailyPnL(BigDecimal.ZERO)
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

    /**
     * Закрытие позиции (стейт-машина, защита от double execution).
     */
    private suspend fun closePosition(
        pos: Position,
        price: BigDecimal,
        reason: String,
    ) {
        // Уже идёт закрытие — НЕ создаём второй ордер, сверяем состояние текущего.
        if (pos.pendingClose) {
            if (pos.closeOrderId != null) {
                confirmCloseFill(pos, price, reason)
            } else {
                resolveCloseViaOutbox(pos)
            }
            return
        }

        val side =
            when (pos.direction) {
                PositionDirection.LONG -> "sell"
                PositionDirection.SHORT -> "buy"
            }
        val placed =
            orderOutboxService.placeOrder(
                pos.ticker,
                side,
                pos.quantity,
                null,
                "market",
                positionId = pos.id,
                closeReason = reason,
            )
        if (!placed.success || placed.alorOrderId == null) {
            if (placed.uncertain) {
                logger.warn {
                    "Close for ${pos.ticker} UNCERTAIN (outbox=${placed.outboxId}); " +
                        "position stays open, pending outbox reconciliation"
                }
                pos.pendingClose = true
                pos.closeOrderId = null
                pos.closeReason = reason
                positionRepo.save(pos)
                meterRegistry.counter("bot.close.uncertain", Tags.of("ticker", pos.ticker)).increment()
            } else {
                logger.error { "Close order NOT accepted for ${pos.ticker} ($reason); position stays OPEN" }
                meterRegistry.counter("bot.close.rejected", Tags.of("ticker", pos.ticker)).increment()
                pos.pendingClose = false
                positionRepo.save(pos)
            }
            return
        }

        pos.closeOrderId = placed.alorOrderId
        pos.pendingClose = true
        pos.closeReason = reason
        positionRepo.save(pos)
        confirmCloseFill(pos, price, reason)
    }

    /**
     * Подтверждение исполнения close-ордера через verifyOrder.
     */
    private suspend fun confirmCloseFill(
        pos: Position,
        expectedPrice: BigDecimal,
        reason: String,
    ) {
        val orderId = pos.closeOrderId ?: return
        val execution = alorClient.verifyOrder(orderId, expectedPrice = expectedPrice)
        if (execution == null) {
            logger.warn { "Close order $orderId for ${pos.ticker} state UNKNOWN; pending reconciliation" }
            return
        }
        val avg = execution.avgPrice ?: expectedPrice
        applyCloseExecution(pos, execution.filledQuantity, avg, reason)
    }

    /**
     * Применяет результат исполнения close-ордера (verifyOrder или WS):
     * полное → финализация, частичное → дозакрытие остатка.
     */
    private suspend fun applyCloseExecution(
        pos: Position,
        filled: Int,
        avg: BigDecimal,
        reason: String,
    ) {
        val filledQty = filled.coerceIn(0, pos.quantity)
        if (filledQty <= 0) return
        if (filledQty >= pos.quantity) {
            finalizeClosePosition(pos, avg, reason)
        } else {
            applyPartialClose(pos, filledQty, avg)
        }
    }

    /**
     * Partial fill: реализуем P&L закрытой части, уменьшаем quantity, остаток дозакрываем.
     */
    private suspend fun applyPartialClose(
        pos: Position,
        filled: Int,
        avg: BigDecimal,
    ) {
        val qty = BigDecimal(filled)
        val partialPnl =
            when (pos.direction) {
                PositionDirection.LONG -> avg.subtract(pos.entryPrice).multiply(qty)
                PositionDirection.SHORT -> pos.entryPrice.subtract(avg).multiply(qty)
            }
        pos.realizedPnl = pos.realizedPnl.add(partialPnl)
        pos.quantity -= filled
        pos.closeOrderId = null
        pos.pendingClose = false
        pos.currentPrice = avg
        positionRepo.save(pos)
        meterRegistry.counter("bot.partial_close", Tags.of("ticker", pos.ticker)).increment()
        logger.warn {
            "PARTIAL close ${pos.ticker}: closed=$filled remainder=${pos.quantity} @ $avg " +
                "realized=$partialPnl ₽ (cumulative=${pos.realizedPnl}); remainder will be re-closed"
        }
    }

    /**
     * Полное закрытие: P&L = realizedPnl (partial) + P&L остатка.
     */
    private suspend fun finalizeClosePosition(
        pos: Position,
        closePrice: BigDecimal,
        reason: String,
    ) {
        val qty = BigDecimal(pos.quantity)
        val remainderPnl =
            when (pos.direction) {
                PositionDirection.LONG -> closePrice.subtract(pos.entryPrice).multiply(qty)
                PositionDirection.SHORT -> pos.entryPrice.subtract(closePrice).multiply(qty)
            }
        val totalPnl = pos.realizedPnl.add(remainderPnl)
        pos.status =
            when (reason) {
                "TAKE_PROFIT" -> PositionStatus.TAKE_PROFIT
                else -> PositionStatus.CLOSED
            }
        pos.closedAt = LocalDateTime.now()
        pos.closePrice = closePrice
        pos.closeReason = reason
        pos.pnl = totalPnl
        pos.pendingClose = false
        pos.closeOrderId = null
        positionRepo.save(pos)
        tradeEventService.recordPositionClosed(pos, reason)
        risk.updateDailyPnL(totalPnl)
        meterRegistry.counter("bot.position.closed", Tags.of("ticker", pos.ticker, "reason", reason)).increment()
        meterRegistry.gauge("bot.pnl", Tags.of("ticker", pos.ticker), totalPnl.toDouble())
        logger.info { "Closed ${pos.ticker} reason=$reason P&L=$totalPnl" }
    }

    /**
     * Фоновый State Reconciliation для pendingEntry/pendingClose позиций (акции).
     */
    @Scheduled(fixedDelay = 15000)
    fun reconcilePendingOrders() {
        scope.launch {
            try {
                val open = positionRepo.findByStatus(PositionStatus.OPEN).filter { it.instrumentType != InstrumentType.FUTURES }
                for (pos in open) {
                    try {
                        when {
                            pos.pendingEntry -> {
                                resolveEntryViaOutbox(pos)
                            }

                            pos.pendingClose -> {
                                if (pos.closeOrderId != null) {
                                    confirmCloseFill(pos, pos.currentPrice ?: pos.entryPrice, pos.closeReason ?: "RECONCILIATION")
                                } else {
                                    resolveCloseViaOutbox(pos)
                                }
                            }

                            else -> {}
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Stock reconciler error for ${pos.id}/${pos.ticker}" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Stock reconciler error" }
            }
        }
    }

    /**
     * Сверка pendingEntry-позиции через outbox-запись.
     */
    private suspend fun resolveEntryViaOutbox(pos: Position) {
        val outbox = orderOutboxRepo.findLatestByPositionId(pos.id!!)
        if (outbox == null) {
            logger.warn { "No outbox row for pending entry ${pos.id}/${pos.ticker}; leaving pending" }
            return
        }
        when {
            outbox.status == OutboxStatus.SENT && outbox.alorOrderId != null -> {
                val execution = alorClient.verifyOrder(outbox.alorOrderId)
                if (execution == null) return
                if (execution.status.contains("reject") || execution.status.contains("cancel")) {
                    abandonEntry(pos, "ENTRY_REJECTED")
                    return
                }
                if (execution.filledQuantity <= 0) return // лимитный ордер ещё не исполнился
                pos.alorOrderId = outbox.alorOrderId
                pos.pendingEntry = false
                pos.entryPrice = execution.avgPrice ?: pos.entryPrice
                pos.quantity = execution.filledQuantity.coerceAtMost(pos.quantity)
                positionRepo.save(pos)
                tradeEventService.recordPositionOpened(pos)
                logger.info { "Pending entry resolved ${pos.ticker}: order=${outbox.alorOrderId} qty=${pos.quantity} @ ${pos.entryPrice}" }
            }

            outbox.status == OutboxStatus.FAILED && outbox.retryCount >= alorConfig.maxOrderRetries -> {
                abandonEntry(pos, "ENTRY_NOT_CONFIRMED")
            }

            else -> {}
        }
    }

    /**
     * Сверка pendingClose-позиции без closeOrderId через outbox-запись.
     */
    private suspend fun resolveCloseViaOutbox(pos: Position) {
        val outbox = orderOutboxRepo.findLatestByPositionId(pos.id!!)
        if (outbox == null) {
            logger.warn { "No outbox row for pending close ${pos.id}/${pos.ticker}; resetting pendingClose" }
            pos.pendingClose = false
            positionRepo.save(pos)
            return
        }
        when {
            outbox.status == OutboxStatus.SENT && outbox.alorOrderId != null -> {
                pos.closeOrderId = outbox.alorOrderId
                positionRepo.save(pos)
                confirmCloseFill(pos, pos.currentPrice ?: pos.entryPrice, pos.closeReason ?: "RECONCILIATION")
            }

            outbox.status == OutboxStatus.FAILED && outbox.retryCount >= alorConfig.maxOrderRetries -> {
                logger.warn { "Pending close ${pos.id}/${pos.ticker} permanently failed; resetting for a fresh close order" }
                pos.pendingClose = false
                pos.closeOrderId = null
                positionRepo.save(pos)
            }

            else -> {}
        }
    }

    private suspend fun abandonEntry(
        pos: Position,
        reason: String,
    ) {
        logger.warn { "Entry for ${pos.ticker} abandoned: $reason" }
        pos.pendingEntry = false
        pos.status = PositionStatus.CLOSED
        pos.closeReason = reason
        pos.closedAt = LocalDateTime.now()
        positionRepo.save(pos)
        meterRegistry.counter("bot.entry.abandoned", Tags.of("ticker", pos.ticker, "reason", reason)).increment()
    }

    /**
     * Применяет ExecutionReport из WebSocket: фиксирует фактическую цену
     * исполнения (вход/закрытие) — с учётом partial fills.
     */
    private suspend fun applyExecutionReport(report: ExecutionReport) {
        if (report.status != OrderStatus.FILLED && report.status != OrderStatus.PARTIALLY_FILLED) return
        val orderId = report.orderId
        val pos = positionRepo.findByAlorOrderId(orderId) ?: positionRepo.findByCloseOrderId(orderId) ?: return
        if (pos.status != PositionStatus.OPEN || pos.closedAt != null) return
        if (pos.instrumentType == InstrumentType.FUTURES) return // фьючерсы обрабатывает FuturesTradingBotService
        val fillPrice = report.avgPrice ?: return

        // Подтверждение входа (pendingEntry).
        if (pos.pendingEntry) {
            if (report.status == OrderStatus.FILLED) {
                pos.alorOrderId = orderId
                pos.pendingEntry = false
                pos.entryPrice = fillPrice
                pos.quantity = report.filledQty.coerceAtLeast(1)
                positionRepo.save(pos)
                tradeEventService.recordPositionOpened(pos)
                logger.info { "WS entry fill applied for ${pos.ticker}: order=$orderId qty=${pos.quantity} @ $fillPrice" }
            }
            // PARTIALLY_FILLED вход — оставляем реконсилятору (verifyOrder даст кумулятивный fill).
            return
        }

        // Подтверждение закрытия (pendingClose).
        if (pos.pendingClose) {
            applyCloseExecution(pos, report.filledQty, fillPrice, pos.closeReason ?: "EXECUTION_FILL")
            return
        }

        // Обычный путь: WS fill по входному ордеру фиксирует фактическую цену/сдвиг.
        pos.closePrice = fillPrice
        val pnl =
            when (pos.direction) {
                PositionDirection.LONG -> fillPrice.subtract(pos.entryPrice).multiply(BigDecimal(pos.quantity))
                PositionDirection.SHORT -> pos.entryPrice.subtract(fillPrice).multiply(BigDecimal(pos.quantity))
            }
        pos.pnl = pnl
        pos.status = if (report.status == OrderStatus.PARTIALLY_FILLED) PositionStatus.OPEN else PositionStatus.CLOSED
        pos.closedAt = if (report.status == OrderStatus.PARTIALLY_FILLED) null else LocalDateTime.now()
        pos.closeReason = pos.closeReason ?: "EXECUTION_FILL"
        positionRepo.save(pos)
        if (pos.status == PositionStatus.CLOSED) {
            tradeEventService.recordPositionClosed(pos, "EXECUTION_FILL")
        }
        alorClient.recordSlippage(pos.entryPrice, fillPrice, pos.quantity)
        meterRegistry.counter("bot.ws.fill_applied", Tags.of("ticker", pos.ticker)).increment()
        logger.info { "WS fill applied for ${pos.ticker}: order=$orderId price=$fillPrice pnl=$pnl" }
    }
}
