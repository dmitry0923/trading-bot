package com.trading.bot.application

import com.trading.bot.application.decision.DecisionEngine
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.event.ExecutionReportEvent
import com.trading.bot.event.PriceChangedEvent
import com.trading.bot.event.StrategyGeneratedEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.DistributedLockService
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import com.trading.bot.service.TradingAccountService
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
 * - Открытие: делегируется [com.trading.bot.application.decision.DecisionEngine]
 *   (risk-first через [FuturesRiskEngine.canEnter], размер позиции через
 *   FuturesPositionSizer, параметры заявки через OrderBuilder; позиция с
 *   futures-полями: leverage, goPerContract, marginUsed, liquidationPrice,
 *   variationMargin, stopLossPoints — всё в [com.trading.bot.application.decision.FuturesEntryProfile]).
 * - Мониторинг: каждый тик PriceChangedEvent → [FuturesPositionMonitor]
 *   (checkLiquidationDistance, LIQUIDATION_CRITICAL → market close, SL/TP/trailing).
 * - P&L фьючерса (₽): (close - entry) * qty * pointValue, pointValue = priceStepCost / priceStep.
 * - При закрытии публикуется PositionClosedEvent → DailyLossCircuitBreaker обновляет дневной P&L.
 * - Защита от double execution / потеря контроля над позицией — в общем ядре
 *   [OrderExecutionEngine]: idempotency key на ордер, стейт-машина pendingEntry/pendingClose,
 *   State Reconciliation через outbox + verifyOrder, partial fills с дозакрытием остатка.
 *
 * Роли: [com.trading.bot.application.decision.DecisionEngine] — вход,
 * [FuturesPositionMonitor] — мониторинг, [OrderExecutionEngine] — исполнение/реконсиляция.
 * Здесь — оркестрация событий, force close и периодическая реконсиляция.
 */
@Service
class FuturesTradingBotService(
    private val futuresRiskEngine: FuturesRiskEngine,
    private val alorClient: AlorClient,
    private val orderOutboxService: OrderOutboxService,
    private val positionRepo: PositionRepository,
    private val orderOutboxRepo: OrderOutboxRepository,
    private val instrumentsConfig: InstrumentsConfig,
    private val riskConfig: RiskConfig,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: TradingEventPublisher,
    private val tradeEventService: TradeEventService,
    private val tradingGate: TradingGate,
    private val decisionEngine: DecisionEngine,
    private val distributedLockService: DistributedLockService,
    private val distributedLockConfig: DistributedLockConfig,
    private val tradingAccountService: TradingAccountService,
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
            protectionOrdersEnabled = alorClient.isLiveMode,
            portfolioResolver = { accountId -> tradingAccountService.portfolioOf(accountId) },
        )

    /** Мониторинг открытых позиций на каждом тике. */
    private val positionMonitor =
        FuturesPositionMonitor(
            futuresRiskEngine = futuresRiskEngine,
            riskConfig = riskConfig,
            instrumentsConfig = instrumentsConfig,
            positionRepo = positionRepo,
            engine = engine,
            meterRegistry = meterRegistry,
        )

    /**
     * Сигнал стратегии для Si → вход через единый [DecisionEngine].
     * Только Si (фьючерс) обрабатывается здесь; риск-проверки (свежесть данных,
     * торговые часы, лимиты, STRESS) выполняет DecisionEngine через
     * [com.trading.bot.application.decision.FuturesEntryProfile].
     */
    @EventListener
    fun onStrategyGenerated(event: StrategyGeneratedEvent) {
        val signal = event.signal
        if (signal.ticker != "Si") return
        if (signal.action != StrategyAction.BUY && signal.action != StrategyAction.SELL) return
        if (!tradingGate.isTradingEnabled()) {
            logger.info { "Trading disabled (single flag) — futures entry skipped ${signal.ticker}" }
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
                logger.error(e) { "Futures entry handler error ${signal.ticker}" }
                meterRegistry.counter("futures.entry.error", Tags.of("ticker", signal.ticker)).increment()
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
            distributedLockService.runExclusive(
                name = "scheduler:reconcile-futures",
                ttlSeconds = distributedLockConfig.schedulerTtlSeconds,
            ) {
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
}
