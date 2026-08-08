package com.trading.bot.service

import com.trading.bot.application.MarketDataGate
import com.trading.bot.application.OrderExecutionEngine
import com.trading.bot.application.PnlCalculator
import com.trading.bot.application.TradingGate
import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorWebSocketClient
import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsConnectionStatus
import com.trading.bot.client.WsStream
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.infrastructure.db.BlockingDb
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.Strategy
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
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
 * Защита от double execution / потеря контроля над позицией вынесена в общее ядро
 * [com.trading.bot.application.OrderExecutionEngine] (используется также
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
    private val objectMapper: ObjectMapper,
    private val agentLogRepo: AgentLogRepository,
    private val tradeEventService: TradeEventService,
    private val eventPublisher: TradingEventPublisher,
    private val tradingGate: TradingGate,
    private val marketDataGate: MarketDataGate,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Общее ядро исполнения ордеров (стейт-машина, outbox-реконсиляция, partial fills). */
    private val engine =
        OrderExecutionEngine(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = alorConfig,
            objectMapper = objectMapper,
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            pnlCalculator = PnlCalculator.plain(),
            instrumentFilter = { it.instrumentType != InstrumentType.FUTURES },
            metricPrefix = "bot",
            onPositionClosed = { pos ->
                risk.updateDailyPnL(pos.pnl ?: BigDecimal.ZERO)
                meterRegistry.gauge("bot.pnl", Tags.of("ticker", pos.ticker), pos.pnl?.toDouble() ?: 0.0)
            },
        )

    /** Время последнего WS-тика по тикеру — используется для отключения поллинга. */
    private val lastWsTickAt = ConcurrentHashMap<String, Instant>()

    /** Per-ticker mutex входа: сериализует openPosition по тикеру (защита от гонки
     *  двух сигналов на один тикер → двойного ордера). */
    private val entryLocks = ConcurrentHashMap<String, Mutex>()

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
                        TraceContext.put(TraceContext.TICKER, tick.ticker)
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
                        TraceContext.put(TraceContext.TICKER, ticker)
                        val price = alorClient.getLastPrice(ticker) ?: return@forEach
                        marketDataGate.recordRestPollSuccess(ticker)
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
        if (!marketDataGate.isPriceDataFresh(strat.ticker)) {
            logger.warn { "STALE market data — entry blocked ${strat.ticker}" }
            meterRegistry.counter("bot.entry.rejected", Tags.of("ticker", strat.ticker, "reason", "STALE_DATA")).increment()
            return
        }
        scope.launch(
            TraceContext.mdcContext(
                mapOf(
                    TraceContext.TRACE_ID to strat.cycleId,
                    TraceContext.CYCLE_ID to strat.cycleId,
                    TraceContext.TICKER to strat.ticker,
                ),
            ),
        ) {
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
        scope.launch(
            TraceContext.mdcContext(
                mapOf(
                    TraceContext.TRACE_ID to event.strategy.cycleId,
                    TraceContext.CYCLE_ID to event.strategy.cycleId,
                    TraceContext.TICKER to event.strategy.ticker,
                ),
            ),
        ) {
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
        scope.launch(TraceContext.mdcContext(mapOf(TraceContext.TICKER to event.ticker))) {
            val handlerStart = System.nanoTime()
            try {
                val open =
                    positionRepo
                        .findByStatus(PositionStatus.OPEN)
                        .filter { it.ticker == event.ticker && it.instrumentType != InstrumentType.FUTURES }
                open.forEach { pos ->
                    // trace_id = cycleId открытия позиции: закрытия/мониторинг наследуют
                    // идентификатор цикла, породившего вход (см. StrategyService).
                    TraceContext.put(TraceContext.TRACE_ID, pos.cycleId)
                    TraceContext.put(TraceContext.CYCLE_ID, pos.cycleId)
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
                        engine.closePosition(pos, price, "STOP_LOSS")
                        return@forEach
                    }
                    if (risk.shouldCloseByTP(pos, price)) {
                        engine.closePosition(pos, price, "TAKE_PROFIT")
                        return@forEach
                    }
                    if (risk.shouldCloseByTrailing(pos, price)) {
                        engine.closePosition(pos, price, "TRAILING_STOP")
                        return@forEach
                    }

                    risk.updateTrailingStop(pos, price)

                    var slUpdated = false
                    var tpUpdated = false
                    BlockingDb.io { redis.getStrategy(pos.ticker) }?.let { strat ->
                        if (strat.action == StrategyAction.CLOSE) {
                            engine.closePosition(pos, price, "STRATEGY_CLOSE")
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
     * ExecutionReportEvent → фиксация фактического исполнения (вход/закрытие — в ядре,
     * обычные fill'ы акций — здесь).
     */
    @EventListener
    fun onExecutionReport(event: com.trading.bot.event.ExecutionReportEvent) {
        scope.launch {
            try {
                if (!engine.handleExecutionReport(event.report)) {
                    handleRegularStockFill(event.report)
                }
            } catch (e: Exception) {
                logger.error(e) { "Execution report handler error for order ${event.report.orderId}" }
            }
        }
    }

    /**
     * Обычный (не pendingEntry/pendingClose) WS fill по акции: фиксирует фактическую
     * цену исполнения и P&L, при полном исполнении закрывает позицию.
     */
    private suspend fun handleRegularStockFill(report: ExecutionReport) {
        if (report.status != OrderStatus.FILLED && report.status != OrderStatus.PARTIALLY_FILLED) return
        val orderId = report.orderId
        val pos = positionRepo.findByAlorOrderId(orderId) ?: positionRepo.findByCloseOrderId(orderId) ?: return
        if (pos.status != PositionStatus.OPEN || pos.closedAt != null) return
        if (pos.instrumentType == InstrumentType.FUTURES) return // фьючерсы обрабатывает FuturesTradingBotService
        val fillPrice = report.avgPrice ?: return

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
                engine.closePosition(pos, price, reason)
            } catch (e: Exception) {
                logger.error(e) { "Force close failed ${pos.ticker}" }
            }
        }
        logger.info { "Force close (stocks): ${open.size} positions, reason=$reason" }
        return open.size
    }

    private suspend fun openPosition(strat: Strategy) {
        val lock = entryLocks.computeIfAbsent(strat.ticker) { Mutex() }
        lock.withLock {
            doOpenPosition(strat)
        }
    }

    private suspend fun doOpenPosition(strat: Strategy) {
        if (!marketDataGate.isPriceDataFresh(strat.ticker)) {
            logger.warn { "STALE market data — entry blocked ${strat.ticker} (defense in depth)" }
            meterRegistry.counter("bot.entry.rejected", Tags.of("ticker", strat.ticker, "reason", "STALE_DATA")).increment()
            return
        }
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

        val opened =
            engine.placeEntryOrder(strat.ticker, dir, qty, strat.targetPrice) { orderId, pending, fillPrice, entryQty ->
                Position(
                    ticker = strat.ticker,
                    direction = dir,
                    quantity = entryQty,
                    entryPrice = fillPrice,
                    currentPrice = fillPrice,
                    stopLoss = strat.stopLoss ?: risk.calcSL(fillPrice, dir),
                    takeProfit = strat.takeProfit ?: risk.calcTP(fillPrice, dir),
                    trailingStopPrice = if (strat.trailingStop) strat.stopLoss else null,
                    alorOrderId = orderId,
                    pendingEntry = pending,
                    cycleId = strat.cycleId,
                )
            }
        if (opened != null) {
            risk.updateDailyPnL(BigDecimal.ZERO)
            agentLogRepo.save(
                AgentLog(
                    cycleId = strat.cycleId,
                    agentName = "TradingBot",
                    ticker = strat.ticker,
                    action = "OPEN",
                    confidence = strat.confidence,
                    reasoning =
                        "Opened ${dir.name} $qty @ ${opened.entryPrice} " +
                            "(target=${strat.targetPrice}, adaptive qty=$qty, kelly=$kellyQty)",
                ),
            )
            logger.info { "Opened ${strat.ticker} ${dir.name} $qty @ ${opened.entryPrice} (adaptive qty=$qty)" }
        }
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
                        engine.reconcilePosition(pos)
                    } catch (e: Exception) {
                        logger.error(e) { "Stock reconciler error for ${pos.id}/${pos.ticker}" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Stock reconciler error" }
            }
        }
    }
}
