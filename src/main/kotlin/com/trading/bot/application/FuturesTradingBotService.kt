package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.FuturesRiskEngine
import com.trading.bot.event.ExecutionReportEvent
import com.trading.bot.event.PriceChangedEvent
import com.trading.bot.event.StrategyGeneratedEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.TradeEventService
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Исполнительный сервис для фьючерсов (Si).
 *
 * - Открытие: только через FuturesRiskEngine.validateEntry() (risk-first).
 * - Позиция сохраняется с futures-полями (leverage, goPerContract, marginUsed,
 *   liquidationPrice, variationMargin, stopLossPoints).
 * - Мониторинг: каждый тик PriceChangedEvent → checkLiquidationDistance().
 *   LIQUIDATION_CRITICAL → немедленный market close.
 * - Daily loss limit: перед каждой сделкой проверяется isDailyLossLimitReached().
 * - P&L фьючерса (₽): (close - entry) * qty * pointValue, pointValue = priceStepCost / priceStep.
 * - При закрытии публикуется PositionClosedEvent → DailyLossCircuitBreaker обновляет дневной P&L.
 *
 * Защита от double execution / потеря контроля над позицией вынесена в общее ядро
 * [OrderExecutionEngine] (см. TradingBotService): idempotency key на ордер, стейт-машина
 * pendingEntry/pendingClose, State Reconciliation через outbox + verifyOrder,
 * partial fills с дозакрытием остатка.
 */
@Service
class FuturesTradingBotService(
    private val futuresRiskEngine: FuturesRiskEngine,
    private val tradingHoursGuard: TradingHoursGuard,
    private val alorClient: AlorClient,
    private val alorFuturesClient: AlorFuturesClient,
    private val orderOutboxService: OrderOutboxService,
    private val positionRepo: PositionRepository,
    private val orderOutboxRepo: OrderOutboxRepository,
    private val riskManagement: RiskManagementService,
    private val instrumentsConfig: InstrumentsConfig,
    private val leverageConfig: LeverageConfig,
    private val riskConfig: RiskConfig,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: TradingEventPublisher,
    private val tradeEventService: TradeEventService,
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
            pnlCalculator = PnlCalculator.futures { ticker -> instrumentsConfig.pointValue(ticker) },
            instrumentFilter = { it.instrumentType == InstrumentType.FUTURES },
            metricPrefix = "futures",
            onEntryOpened = { eventPublisher.publishPositionOpened(it) },
            onPositionClosed = { eventPublisher.publishPositionClosed(it) },
        )

    /** Per-ticker mutex входа: сериализует openFuturesPosition по тикеру (защита от
     *  гонки двух сигналов на один тикер → двойного ордера). */
    private val entryLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Сигнал стратегии для Si → вход. Только Si (фьючерс) обрабатывается здесь.
     */
    @EventListener
    fun onStrategyGenerated(event: StrategyGeneratedEvent) {
        val strat = event.strategy
        if (strat.ticker != "Si") return
        if (strat.action != StrategyAction.BUY && strat.action != StrategyAction.SELL) return
        if (!tradingGate.isTradingEnabled()) {
            logger.info { "Trading disabled (single flag) — futures entry skipped ${strat.ticker}" }
            return
        }
        if (!marketDataGate.isPriceDataFresh(strat.ticker)) {
            logger.warn { "STALE market data — futures entry blocked ${strat.ticker}" }
            meterRegistry.counter("futures.entry.rejected", Tags.of("ticker", strat.ticker, "reason", "STALE_DATA")).increment()
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
                openFuturesPosition(strat.ticker, strat.targetPrice, strat.action, strat.cycleId)
            } catch (e: Exception) {
                logger.error(e) { "Futures entry handler error ${strat.ticker}" }
                meterRegistry.counter("futures.entry.error", Tags.of("ticker", strat.ticker)).increment()
            }
        }
    }

    /**
     * Мониторинг открытых фьючерсных позиций на каждом тике.
     */
    @EventListener
    fun onPriceChanged(event: PriceChangedEvent) {
        if (event.ticker != "Si") return
        scope.launch(TraceContext.mdcContext(mapOf(TraceContext.TICKER to event.ticker))) {
            try {
                monitorOpenPositions(event.ticker, event.price)
            } catch (e: Exception) {
                logger.error(e) { "Futures monitor handler error ${event.ticker}" }
                meterRegistry.counter("futures.monitor.error", Tags.of("ticker", event.ticker)).increment()
            }
        }
    }

    @EventListener
    fun onTradingHalted(event: TradingHaltedEvent) {
        logger.error { "TRADING HALTED: ${event.reason}. New entries are blocked, open positions still monitored." }
        meterRegistry.counter("futures.trading.halted", Tags.of("reason", event.reason)).increment()
    }

    /**
     * ExecutionReportEvent (WS-поток Alor) → фиксация фактического исполнения
     * фьючерсных ордеров (вход/закрытие, partial fills) в ядре исполнения.
     * Без этого fill'ы, потерянные при обрыве WebSocket, бот узнал бы только
     * через REST-реконсилятор.
     */
    @EventListener
    fun onExecutionReport(event: ExecutionReportEvent) {
        scope.launch {
            try {
                engine.handleExecutionReport(event.report)
            } catch (e: Exception) {
                logger.error(e) { "Futures execution report handler error for order ${event.report.orderId}" }
            }
        }
    }

    /**
     * Принудительное закрытие всех открытых фьючерсных позиций
     * (настройка "закрыть торговлю сейчас").
     *
     * @param reason причина закрытия
     * @return количество закрытых позиций
     */
    suspend fun forceCloseAll(reason: String = "FORCE_CLOSE"): Int {
        val open =
            positionRepo
                .findByStatus(PositionStatus.OPEN)
                .filter { it.instrumentType == InstrumentType.FUTURES }
        open.forEach { pos ->
            try {
                val price = alorClient.getLastPrice(pos.ticker) ?: pos.currentPrice ?: pos.entryPrice
                engine.closePosition(pos, price, reason)
            } catch (e: Exception) {
                logger.error(e) { "Futures force close failed ${pos.ticker}" }
            }
        }
        logger.info { "Force close (futures): ${open.size} positions, reason=$reason" }
        return open.size
    }

    private suspend fun openFuturesPosition(
        ticker: String,
        targetPrice: BigDecimal,
        action: StrategyAction,
        cycleId: String?,
    ) {
        val lock = entryLocks.computeIfAbsent(ticker) { Mutex() }
        lock.withLock {
            doOpenFuturesPosition(ticker, targetPrice, action, cycleId)
        }
    }

    private suspend fun doOpenFuturesPosition(
        ticker: String,
        targetPrice: BigDecimal,
        action: StrategyAction,
        cycleId: String?,
    ) {
        if (!marketDataGate.isPriceDataFresh(ticker)) {
            logger.warn { "STALE market data — futures entry blocked $ticker (defense in depth)" }
            meterRegistry.counter("futures.entry.rejected", Tags.of("ticker", ticker, "reason", "STALE_DATA")).increment()
            return
        }
        if (futuresRiskEngine.isDailyLossLimitReached()) {
            logger.warn { "Daily loss limit reached — entry blocked $ticker" }
            meterRegistry.counter("risk.entry.rejected", Tags.of("reason", "DAILY_LIMIT")).increment()
            return
        }
        if (!tradingHoursGuard.isTradingAllowed()) {
            logger.info { "Outside trading hours — entry skipped $ticker" }
            meterRegistry.counter("risk.entry.rejected", Tags.of("reason", "OUTSIDE_HOURS")).increment()
            return
        }

        val direction = if (action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val entryPrice = alorClient.getLastPrice(ticker) ?: targetPrice
        val currentGo = alorFuturesClient.getFuturesGO(ticker)
        val portfolioMoney = alorFuturesClient.getPortfolioMoney()

        val validation = futuresRiskEngine.validateEntry(ticker, entryPrice, direction, portfolioMoney, currentGo)
        if (!validation.allowed) {
            logger.warn { "Risk engine rejected $ticker: ${validation.reason}" }
            return
        }

        val opened =
            engine.placeEntryOrder(ticker, direction, validation.quantity, entryPrice) { orderId, pending, fillPrice, qty ->
                Position(
                    ticker = ticker,
                    direction = direction,
                    quantity = qty,
                    entryPrice = fillPrice,
                    currentPrice = fillPrice,
                    stopLoss = validation.stopLossPrice,
                    takeProfit = validation.takeProfitPrice,
                    trailingStopPrice = validation.stopLossPrice,
                    instrumentType = InstrumentType.FUTURES,
                    leverage = leverageConfig.effective(),
                    goPerContract = currentGo,
                    marginUsed = validation.marginRequired,
                    liquidationPrice = validation.liquidationPrice,
                    variationMargin = BigDecimal.ZERO,
                    stopLossPoints = riskConfig.defaultStopLossPoints,
                    alorOrderId = orderId,
                    pendingEntry = pending,
                    cycleId = cycleId,
                )
            }
        if (opened != null) {
            logger.info {
                "Opened futures $ticker $direction qty=${opened.quantity} @ ${opened.entryPrice} " +
                    "sl=${validation.stopLossPrice} tp=${validation.takeProfitPrice} " +
                    "margin=${validation.marginRequired} liq=${validation.liquidationPrice}"
            }
        }
    }

    private suspend fun monitorOpenPositions(
        ticker: String,
        price: BigDecimal,
    ) {
        val open = positionRepo.findByStatus(PositionStatus.OPEN).filter { it.ticker == ticker }
        for (pos in open) {
            if (pos.instrumentType != InstrumentType.FUTURES) continue
            TraceContext.put(TraceContext.TRACE_ID, pos.cycleId)
            TraceContext.put(TraceContext.CYCLE_ID, pos.cycleId)

            // Позиция ожидает подтверждения входа — SL/TP/закрытие не трогаем,
            // ждём State Reconciliation.
            if (pos.pendingEntry) {
                engine.resolveEntryViaOutbox(pos)
                continue
            }

            // Закрытие уже в полёте — новый ордер НЕ создаём (защита от double execution).
            if (pos.pendingClose) {
                engine.reconcilePosition(pos)
                continue
            }

            pos.currentPrice = price

            // 1. Guardrail ликвидации — самый приоритетный
            when (futuresRiskEngine.checkLiquidationDistance(pos, price)) {
                FuturesRiskEngine.LiquidationStatus.CRITICAL -> {
                    logger.error { "LIQUIDATION_CRITICAL ${pos.ticker} @ $price — immediate market close" }
                    engine.closePosition(pos, price, "LIQUIDATION_CRITICAL")
                    continue
                }

                FuturesRiskEngine.LiquidationStatus.WARNING -> {
                    logger.warn {
                        "LIQUIDATION_WARNING ${pos.ticker} @ $price — " +
                            "distance < ${riskConfig.minLiquidationDistancePercent}%"
                    }
                    meterRegistry
                        .counter(
                            "futures.liquidation.warning",
                            Tags.of("ticker", pos.ticker),
                        ).increment()
                }

                FuturesRiskEngine.LiquidationStatus.SAFE -> {}
            }

            // 2. SL / TP / trailing
            if (riskManagement.shouldCloseBySL(pos, price)) {
                engine.closePosition(pos, price, "STOP_LOSS")
                continue
            }
            if (riskManagement.shouldCloseByTP(pos, price)) {
                engine.closePosition(pos, price, "TAKE_PROFIT")
                continue
            }
            if (riskManagement.shouldCloseByTrailing(pos, price)) {
                engine.closePosition(pos, price, "TRAILING_STOP")
                continue
            }

            // 3. Подтягивание trailing (только в прибыль, с учётом вариационной маржи)
            futuresRiskEngine.updateTrailingStop(pos, price)
            positionRepo.save(pos)
        }
    }

    /**
     * Фоновый State Reconciliation (REST) для pendingEntry/pendingClose позиций.
     */
    @Scheduled(fixedDelay = 15000)
    fun reconcilePendingOrders() {
        scope.launch {
            try {
                val open = positionRepo.findByStatus(PositionStatus.OPEN).filter { it.instrumentType == InstrumentType.FUTURES }
                for (pos in open) {
                    try {
                        TraceContext.put(TraceContext.TRACE_ID, pos.cycleId)
                        TraceContext.put(TraceContext.CYCLE_ID, pos.cycleId)
                        engine.reconcilePosition(pos)
                    } catch (e: Exception) {
                        logger.error(e) { "Futures reconciler error for ${pos.id}/${pos.ticker}" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Futures reconciler error" }
            }
        }
    }
}
