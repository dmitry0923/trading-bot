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
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
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
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Координатор торговли фьючерсами (Si).
 *
 * - Открытие: делегируется [FuturesEntryCoordinator] (risk-first через
 *   FuturesRiskEngine.validateEntry(), позиция с futures-полями: leverage,
 *   goPerContract, marginUsed, liquidationPrice, variationMargin, stopLossPoints).
 * - Мониторинг: каждый тик PriceChangedEvent → [FuturesPositionMonitor]
 *   (checkLiquidationDistance, LIQUIDATION_CRITICAL → market close, SL/TP/trailing).
 * - Daily loss limit: перед каждой сделкой проверяется isDailyLossLimitReached().
 * - P&L фьючерса (₽): (close - entry) * qty * pointValue, pointValue = priceStepCost / priceStep.
 * - При закрытии публикуется PositionClosedEvent → DailyLossCircuitBreaker обновляет дневной P&L.
 * - Защита от double execution / потеря контроля над позицией — в общем ядре
 *   [OrderExecutionEngine]: idempotency key на ордер, стейт-машина pendingEntry/pendingClose,
 *   State Reconciliation через outbox + verifyOrder, partial fills с дозакрытием остатка.
 *
 * Роли: [FuturesEntryCoordinator] — вход, [FuturesPositionMonitor] — мониторинг,
 * [OrderExecutionEngine] — исполнение/реконсиляция. Здесь — оркестрация событий,
 * force close и периодическая реконсиляция.
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

    /** Оркестратор входа (per-ticker mutex, risk-first проверки, размещение ордера). */
    private val entryCoordinator =
        FuturesEntryCoordinator(
            futuresRiskEngine = futuresRiskEngine,
            tradingHoursGuard = tradingHoursGuard,
            alorClient = alorClient,
            alorFuturesClient = alorFuturesClient,
            marketDataGate = marketDataGate,
            leverageConfig = leverageConfig,
            riskConfig = riskConfig,
            engine = engine,
            meterRegistry = meterRegistry,
        )

    /** Мониторинг открытых позиций на каждом тике. */
    private val positionMonitor =
        FuturesPositionMonitor(
            futuresRiskEngine = futuresRiskEngine,
            riskManagement = riskManagement,
            riskConfig = riskConfig,
            positionRepo = positionRepo,
            engine = engine,
            meterRegistry = meterRegistry,
        )

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
                entryCoordinator.openPosition(strat.ticker, strat.targetPrice, strat.action, strat.cycleId)
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
                positionMonitor.monitor(event.ticker, event.price)
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
