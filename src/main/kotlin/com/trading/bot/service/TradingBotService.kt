package com.trading.bot.service

import com.trading.bot.application.MarketDataGate
import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.OrderExecutionEngine
import com.trading.bot.application.PnlCalculator
import com.trading.bot.application.TradingGate
import com.trading.bot.application.risk.StockRiskEngine
import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorWebSocketClient
import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsConnectionStatus
import com.trading.bot.client.WsStream
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.ExitRules
import com.trading.bot.domain.risk.PortfolioRiskEngine
import com.trading.bot.domain.risk.PortfolioRiskRequest
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.signal.Signal
import com.trading.bot.domain.signal.toSignal
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
    private val stockRiskEngine: StockRiskEngine,
    private val portfolioRiskEngine: PortfolioRiskEngine,
    private val orderBuilder: OrderBuilder,
    private val candleCache: CandleCacheService,
    private val riskConfig: RiskConfig,
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
     * StrategyGeneratedEvent → если сигнал пригоден → EntrySignalEvent.
     * Все риск-проверки (дневной лимит, drawdown, волатильность, дубли, лимиты
     * позиций и секторов) выполняет [StockRiskEngine.canEnter] на этапе входа.
     */
    @EventListener
    fun onStrategyGenerated(event: com.trading.bot.event.StrategyGeneratedEvent) {
        val signal = event.signal
        if (signal.ticker == "Si") return // фьючерсы обрабатывает FuturesTradingBotService
        if (signal.action != StrategyAction.BUY && signal.action != StrategyAction.SELL) return
        if (!tradingGate.isTradingEnabled()) {
            logger.info { "Trading disabled (single flag) — entry skipped ${signal.ticker}" }
            return
        }
        if (!marketDataGate.isPriceDataFresh(signal.ticker)) {
            logger.warn { "STALE market data — entry blocked ${signal.ticker}" }
            meterRegistry.counter("bot.entry.rejected", Tags.of("ticker", signal.ticker, "reason", "STALE_DATA")).increment()
            return
        }
        scope.launch(
            TraceContext.mdcContext(
                mapOf(
                    TraceContext.TRACE_ID to signal.cycleId,
                    TraceContext.CYCLE_ID to signal.cycleId,
                    TraceContext.TICKER to signal.ticker,
                ),
            ),
        ) {
            try {
                eventPublisher.publishEntrySignal(signal)
            } catch (e: Exception) {
                logger.error(e) { "Strategy generated handler error ${signal.ticker}" }
            }
        }
    }

    /**
     * EntrySignalEvent → RiskEngine.canEnter() + открытие позиции.
     */
    @EventListener
    fun onEntrySignal(event: com.trading.bot.event.EntrySignalEvent) {
        scope.launch(
            TraceContext.mdcContext(
                mapOf(
                    TraceContext.TRACE_ID to event.signal.cycleId,
                    TraceContext.CYCLE_ID to event.signal.cycleId,
                    TraceContext.TICKER to event.signal.ticker,
                ),
            ),
        ) {
            try {
                openPosition(event.signal)
            } catch (e: Exception) {
                logger.error(e) { "Entry signal handler error ${event.signal.ticker}" }
                meterRegistry.counter("bot.entry.error", Tags.of("ticker", event.signal.ticker)).increment()
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

                    if (ExitRules.shouldCloseBySL(pos, price)) {
                        engine.closePosition(pos, price, "STOP_LOSS")
                        return@forEach
                    }
                    if (ExitRules.shouldCloseByTP(pos, price)) {
                        engine.closePosition(pos, price, "TAKE_PROFIT")
                        return@forEach
                    }
                    if (ExitRules.shouldCloseByTrailing(pos, price)) {
                        engine.closePosition(pos, price, "TRAILING_STOP")
                        return@forEach
                    }

                    if (riskConfig.trailingStopEnabled) {
                        ExitRules.updateTrailingStop(pos, price, riskConfig.trailingStopPercent)
                    }

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
            strategies.values.forEach { eventPublisher.publishStrategyGenerated(it.toSignal()) }
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

    private suspend fun openPosition(signal: Signal) {
        val lock = entryLocks.computeIfAbsent(signal.ticker) { Mutex() }
        lock.withLock {
            doOpenPosition(signal)
        }
    }

    private suspend fun doOpenPosition(signal: Signal) {
        val ticker = signal.ticker
        if (!marketDataGate.isPriceDataFresh(ticker)) {
            logger.warn { "STALE market data — entry blocked $ticker (defense in depth)" }
            meterRegistry.counter("bot.entry.rejected", Tags.of("ticker", ticker, "reason", "STALE_DATA")).increment()
            return
        }

        val open = positionRepo.findByStatus(PositionStatus.OPEN)
        val entryPrice = alorClient.getLastPrice(ticker) ?: signal.targetPrice
        val direction = if (signal.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val atr = candleCache.calculateAtr(ticker, "MINUTE_10", 14)

        // Риск-этап: Да/Нет (дневной лимит, drawdown, волатильность индекса,
        // дубли, лимиты позиций/секторов, ATR%).
        val verdict =
            stockRiskEngine.canEnter(
                EntryRequest(
                    ticker = ticker,
                    action = signal.action,
                    entryPrice = entryPrice,
                    direction = direction,
                    portfolioMoney = riskConfig.maxPositionRub,
                    currentGo = BigDecimal.ZERO,
                    atr = atr,
                    openPositions = open,
                ),
            )
        if (verdict is RiskVerdict.Rejected) {
            logger.warn { "Risk reject $ticker: ${verdict.reason}" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", ticker, "reason", verdict.reason)).increment()
            return
        }
        if (adaptiveRisk.exceedsCorrelationLimit(ticker, open)) {
            logger.warn { "Correlation filter reject $ticker: correlated with an open position" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", ticker, "reason", "CORRELATION")).increment()
            return
        }
        if (adaptiveRisk.exceedsSectorCorrelationLimit(ticker, open)) {
            logger.warn { "Sector correlation filter reject $ticker: correlated position inside same sector" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", ticker, "reason", "SECTOR_CORRELATION")).increment()
            return
        }

        // Сайзинг (Kelly) — адаптивный риск-менеджмент.
        val kellySizeRub = adaptiveRisk.calculateOptimalPositionSize(ticker)
        val kellyQty =
            if (kellySizeRub > BigDecimal.ZERO) {
                kellySizeRub.divide(entryPrice, 0, RoundingMode.DOWN).toInt().coerceAtLeast(1)
            } else {
                0
            }
        var qty = kellyQty.coerceAtLeast(1)

        var params = orderBuilder.buildStockOrderParams(direction, qty, entryPrice)
        if (params.quantity <= 0) {
            logger.warn { "Zero quantity for $ticker after adaptive sizing" }
            return
        }

        val candidateNotional = entryPrice.multiply(BigDecimal(params.quantity))
        if (risk.exceedsPortfolioLimits(candidateNotional, direction, open)) {
            logger.warn { "Portfolio exposure reject $ticker: gross/net limit" }
            meterRegistry.counter("bot.risk.reject", Tags.of("ticker", ticker, "reason", "PORTFOLIO_LIMIT")).increment()
            return
        }

        // Портфельный риск (агрегат): VaR95 / эффективное число ставок / направленная
        // концентрация. BLOCK — запрет входа (когда несколько коррелированных позиций
        // фактически образуют одну ставку на рынок); SCALE — уменьшение размера.
        val portfolioReport =
            portfolioRiskEngine.evaluate(
                PortfolioRiskRequest(
                    candidateTicker = ticker,
                    candidateDirection = direction,
                    candidateNotionalRub = candidateNotional,
                    openPositions = open,
                    aum = riskConfig.maxPositionRub,
                ),
            )
        if (!portfolioReport.allowed) {
            logger.warn { "Portfolio risk reject $ticker: ${portfolioReport.reasons.joinToString("|")}" }
            meterRegistry
                .counter("bot.risk.reject", Tags.of("ticker", ticker, "reason", portfolioReport.reasons.joinToString("|")))
                .increment()
            return
        }
        if (portfolioReport.scaleDownFactor < BigDecimal.ONE) {
            val scaledQty =
                BigDecimal(params.quantity)
                    .multiply(portfolioReport.scaleDownFactor)
                    .setScale(0, RoundingMode.DOWN)
                    .toInt()
                    .coerceAtLeast(1)
            if (scaledQty != params.quantity) {
                qty = scaledQty
                params = orderBuilder.buildStockOrderParams(direction, scaledQty, entryPrice)
                meterRegistry.counter("bot.portfolio.scaled", Tags.of("ticker", ticker)).increment()
                logger.info {
                    "Portfolio risk scale $ticker: qty ${params.quantity} (factor=${portfolioReport.scaleDownFactor})"
                }
            }
        }

        val opened =
            engine.placeEntryOrder(ticker, direction, params.quantity, entryPrice) { orderId, pending, fillPrice, entryQty ->
                Position(
                    ticker = ticker,
                    direction = direction,
                    quantity = entryQty,
                    entryPrice = fillPrice,
                    currentPrice = fillPrice,
                    stopLoss = params.stopLossPrice,
                    takeProfit = params.takeProfitPrice,
                    trailingStopPrice = params.trailingStopPrice,
                    alorOrderId = orderId,
                    pendingEntry = pending,
                    cycleId = signal.cycleId,
                )
            }
        if (opened != null) {
            orderBuilder.recordStrategyExecution(signal, params)
            risk.updateDailyPnL(BigDecimal.ZERO)
            agentLogRepo.save(
                AgentLog(
                    cycleId = signal.cycleId,
                    agentName = "TradingBot",
                    ticker = ticker,
                    action = "OPEN",
                    confidence = signal.confidence,
                    reasoning =
                        "Opened ${direction.name} $qty @ ${opened.entryPrice} " +
                            "(target=${signal.targetPrice}, adaptive qty=$qty, kelly=$kellyQty)",
                ),
            )
            logger.info { "Opened $ticker ${direction.name} $qty @ ${opened.entryPrice} (adaptive qty=$qty)" }
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
