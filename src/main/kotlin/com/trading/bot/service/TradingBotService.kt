package com.trading.bot.service

import com.trading.bot.application.MarketDataGate
import com.trading.bot.application.OrderExecutionEngine
import com.trading.bot.application.PnlCalculator
import com.trading.bot.application.StockPositionMonitor
import com.trading.bot.application.TradingGate
import com.trading.bot.application.decision.DecisionEngine
import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorWebSocketClient
import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsConnectionStatus
import com.trading.bot.client.WsStream
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.signal.toSignal
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.infrastructure.metrics.MutableGauges
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val redis: ReactiveRedisCacheService,
    private val riskConfig: RiskConfig,
    private val positionRepo: PositionRepository,
    private val orderOutboxRepo: OrderOutboxRepository,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val tradeEventService: TradeEventService,
    private val eventPublisher: TradingEventPublisher,
    private val tradingGate: TradingGate,
    private val marketDataGate: MarketDataGate,
    private val decisionEngine: DecisionEngine,
    private val distributedLockService: DistributedLockService,
    private val distributedLockConfig: DistributedLockConfig,
    private val tradingAccountService: TradingAccountService,
    private val instrumentsConfig: InstrumentsConfig,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconcileRunning = AtomicBoolean(false)

    /** Lot-based P&L — единый источник для engine и ws-fill handler. Включает вычет комиссии. */
    private val pnlCalculator: PnlCalculator =
        PnlCalculator.lotBased(
            lotSize = { ticker -> instrumentsConfig.find(ticker)?.lotSize?.toLong() ?: 1L },
            commissionRub = { ticker -> instrumentsConfig.find(ticker)?.commissionRub },
        )

    @PreDestroy
    fun close() {
        scope.cancel()
    }

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
            pnlCalculator = pnlCalculator,
            instrumentFilter = { it.instrumentType != InstrumentType.FUTURES },
            metricPrefix = "bot",
            onPositionClosed = { pos ->
                // F-4 (roadmap 13.25): публикуем PositionClosedEvent как фьючерсы —
                // DailyLossCircuitBreaker учитывает дневной P&L один раз (прямой вызов
                // здесь дал бы двойной счёт), DrawdownProtectionService пересчитывает
                // multi-tier статус сразу после закрытия акции.
                eventPublisher.publishPositionClosed(pos)
                MutableGauges.set(meterRegistry, "bot.pnl", pos.pnl?.toDouble() ?: 0.0, Tags.of("ticker", pos.ticker))
            },
            onSlProtectionFailed = { pos ->
                // Safety: SL/TP permanently failed → HALT all entries + emergency close unprotected position.
                logger.warn { "SL protection FAILED for ${pos.ticker} — halting entries and triggering emergency close" }
                eventPublisher.publishTradingHalted(
                    TradingHaltedEvent(reason = "SL_PROTECTION_FAILED"),
                )
                scope.launch {
                    try {
                        val price = alorClient.getLastPrice(pos.ticker) ?: pos.currentPrice ?: pos.entryPrice
                        emergencyClose(pos.ticker, price)
                        logger.warn { "Emergency close after SL failure: ${pos.ticker} @ $price" }
                    } catch (e: Exception) {
                        logger.error(e) { "Emergency close FAILED for ${pos.ticker} after SL failure — manual intervention required" }
                    }
                }
            },
            protectionOrdersEnabled = alorClient.isLiveMode,
            portfolioResolver = { accountId -> tradingAccountService.portfolioOf(accountId) },
        )

    /** Мониторинг открытых позиций акций/валют на каждом тике. */
    private val stockPositionMonitor =
        StockPositionMonitor(
            positionRepo = positionRepo,
            engine = engine,
            redis = redis,
            riskConfig = riskConfig,
            instrumentsConfig = instrumentsConfig,
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
        )

    /**
     * WS-подписки на исполнения (multi-account, roadmap v2.2): по одному потоку
     * на портфель каждого включённого аккаунта + legacy конфиг-портфель (для
     * позиций с account_id = NULL). Потоки коллекционируются конкурентно.
     * Добавление аккаунта через API применяется при следующем рестарте бота
     * (доставка ордеров такого аккаунта всё равно работает через outbox).
     */
    private suspend fun subscribeAllOrderStreams() {
        val portfolios = mutableListOf(alorConfig.portfolio)
        for (account in tradingAccountService.findEnabled()) {
            if (account.alorPortfolio !in portfolios) portfolios += account.alorPortfolio
        }
        coroutineScope {
            for (portfolio in portfolios) {
                launch {
                    alorWsClient.subscribeToOrders(portfolio).collect { report ->
                        try {
                            eventPublisher.publishExecutionReport(report)
                        } catch (e: Exception) {
                            logger.error(e) { "WS execution processing error for order ${report.orderId} (portfolio=$portfolio)" }
                        }
                    }
                }
            }
        }
    }

    /** Время последнего WS-тика по тикеру — используется для отключения поллинга. */
    private val lastWsTickAt = ConcurrentHashMap<String, Instant>()

    init {
        scope.launch {
            subscribeAllOrderStreams()
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
                        if (tick.bidSize != null && tick.askSize != null) {
                            val obi =
                                com.trading.bot.domain.microstructure.ObiCalculator
                                    .calculate(tick.bidSize, tick.askSize)
                            if (obi != null) marketDataGate.updateObi(tick.ticker, obi)
                        }
                        if (tick.bid != null && tick.ask != null) {
                            marketDataGate.recordSpread(tick.ticker, tick.bid, tick.ask)
                        }
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
            distributedLockService.runExclusive(
                name = "scheduler:poll-market-data",
                ttlSeconds = distributedLockConfig.schedulerTtlSeconds,
            ) {
                val now = Instant.now()
                val tickers = positionRepo.findOpenTickersDistinct()
                tickers
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
    }

    /**
     * StrategyGeneratedEvent → единый оркестратор входа [DecisionEngine].
     * Все риск-проверки (свежесть данных, дневной лимит, drawdown, волатильность,
     * дубли, лимиты позиций/секторов, корреляция, exposure) выполняет DecisionEngine
     * через [com.trading.bot.application.decision.StockEntryProfile].
     */
    @EventListener
    fun onStrategyGenerated(event: com.trading.bot.event.StrategyGeneratedEvent) {
        val signal = event.signal
        if (instrumentsConfig.isFutures(signal.ticker)) return
        if (signal.action != StrategyAction.BUY && signal.action != StrategyAction.SELL) return
        if (!tradingGate.isTradingEnabled()) {
            logger.info { "Trading disabled (single flag) — entry skipped ${signal.ticker}" }
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
                decisionEngine.openPosition(signal, engine::placeEntryOrder)
            } catch (e: Exception) {
                logger.error(e) { "Strategy generated handler error ${signal.ticker}" }
            }
        }
    }

    /**
     * PriceChangedEvent → мониторинг открытых позиций (SL/TP/trailing/STRATEGY_CLOSE).
     * Делегируется в [StockPositionMonitor] для устранения God Object.
     */
    @EventListener
    fun onPriceChanged(event: com.trading.bot.event.PriceChangedEvent) {
        scope.launch(TraceContext.mdcContext(mapOf(TraceContext.TICKER to event.ticker))) {
            stockPositionMonitor.monitor(event)
        }
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
     * Fallback WS close fill: delegate to engine's delta model.
     *
     * Обрабатывает close-филы, которые engine.handleExecutionReport() пропустил
     * (например, pendingClose=false после releaseCloseClaim, но closeOrderId установлен).
     * Использует ту же cumulativeFilledQty − cumulativeCloseFillQty дельта-модель,
     * что и основной путь — P&L считается по фактическому delta, а не по всему qty.
     */
    private suspend fun handleRegularStockFill(report: ExecutionReport) {
        if (report.status != OrderStatus.FILLED && report.status != OrderStatus.PARTIALLY_FILLED) return
        val orderId = report.orderId
        val pos = positionRepo.findByCloseOrderId(orderId) ?: return
        if (pos.status != PositionStatus.OPEN || pos.closedAt != null) return
        if (pos.instrumentType == InstrumentType.FUTURES) return
        engine.handleCloseFill(pos, report)
    }

    /**
     * Ручной триггер (API /bot/trigger): публикует StrategyGeneratedEvent для текущих
     * стратегий — вход маршрутизируется через [DecisionEngine] (onStrategyGenerated).
     */
    fun runBotCycle() {
        logger.info { "=== BOT CYCLE (manual trigger) ===" }
        meterRegistry.counter("bot.cycle").increment()
        scope.launch {
            val strategies = redis.getAllStrategies(tradingConfig.tickers)
            strategies.values.forEach { eventPublisher.publishStrategyGenerated(it.toSignal()) }
        }
    }

    /**
     * Аварийное закрытие конкретной позиции (при SL_PROTECTION_FAILED).
     * Вызывается из [engine] callback, поэтому [engine] уже инициализирован.
     */
    private suspend fun emergencyClose(
        ticker: String,
        price: BigDecimal,
    ) {
        val pos = positionRepo.findByStatusAndTicker(PositionStatus.OPEN, ticker).firstOrNull() ?: return
        engine.closePosition(pos, price, CloseReason.EMERGENCY_STOP)
    }

    /**
     * Принудительное закрытие всех открытых акций/валютных позиций
     * (настройка "закрыть торговлю сейчас"). Для фьючерсов — см. FuturesTradingBotService.
     *
     * @param reason причина закрытия (FORCE_CLOSE, FORCE_CLOSE_SCHEDULED и т.п.)
     * @return количество закрытых позиций
     */
    suspend fun forceCloseAll(reason: CloseReason = CloseReason.FORCE_CLOSE): Int {
        val open = positionRepo.findOpenStocks()
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

    /**
     * Фоновый State Reconciliation для позиций (акции/валюты).
     * Запрашивает ВСЕ открытые позиции (не только pending), чтобы
     * обрабатывать slPendingReplace/tpPendingReplace флаги после partial close.
     * Фьючерсы обрабатываются FuturesTradingBotService.reconcilePendingOrders().
     */
    @Scheduled(fixedDelay = 15000)
    fun reconcilePendingOrders() {
        if (!reconcileRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                distributedLockService.runExclusive(
                    name = "scheduler:reconcile-stocks",
                    ttlSeconds = distributedLockConfig.schedulerTtlSeconds,
                ) {
                    try {
                        val open =
                            positionRepo
                                .findByStatus(PositionStatus.OPEN)
                                .filter { it.instrumentType != InstrumentType.FUTURES }
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
            } finally {
                reconcileRunning.set(false)
            }
        }
    }
}
