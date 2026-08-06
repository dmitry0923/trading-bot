package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.FuturesRiskEngine
import com.trading.bot.event.PriceChangedEvent
import com.trading.bot.event.StrategyGeneratedEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.OutboxStatus
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
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
import java.math.BigDecimal
import java.time.LocalDateTime

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
 * Защита от double execution / потеря контроля над позицией:
 * - Каждый ордер уходит в Alor с уникальным idempotency key (см. OrderOutboxService);
 *   повторные доставки дедуплицируются биржей.
 * - [Position.pendingClose]: пока закрытие в полёте, новый ордер НЕ создаётся —
 *   [Position.closeOrderId] сверяется через [AlorClient.verifyOrder].
 * - [Position.pendingEntry]: вход принят/UNCERTAIN — факт исполнения сверяется
 *   реконсилятором через outbox.
 * - Partial fills: [applyPartialClose] уменьшает quantity и реализует P&L;
 *   остаток дозакрывается следующей итерацией мониторинга.
 * - [reconcilePendingOrders] — фоновый State Reconciliation (REST портфеля) для
 *   pendingEntry/pendingClose позиций.
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
    private val eventPublisher: TradingEventPublisher,
    private val tradeEventService: TradeEventService,
    private val tradingGate: TradingGate,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        scope.launch {
            try {
                openFuturesPosition(strat.ticker, strat.targetPrice, strat.action)
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
        scope.launch {
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
                closeFuturesPosition(pos, price, reason)
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
    ) {
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

        val side = if (direction == PositionDirection.LONG) "buy" else "sell"
        val placed = orderOutboxService.placeOrder(ticker, side, validation.quantity, entryPrice, "limit")
        if (!placed.success || placed.alorOrderId == null) {
            if (placed.uncertain) {
                // Доставка могла дойти до Alor → создаём позицию в состоянии pendingEntry;
                // факт исполнения подтвердит реконсилятор (REST портфеля).
                logger.warn {
                    "Entry order for $ticker UNCERTAIN (outbox=${placed.outboxId}); " +
                        "position created as pendingEntry"
                }
                val pos =
                    Position(
                        ticker = ticker,
                        direction = direction,
                        quantity = validation.quantity,
                        entryPrice = entryPrice,
                        currentPrice = entryPrice,
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
                        pendingEntry = true,
                    )
                positionRepo.save(pos)
                meterRegistry.counter("futures.entry.uncertain", Tags.of("ticker", ticker)).increment()
                return
            }
            logger.error { "Order failed for $ticker" }
            meterRegistry.counter("futures.order.failed", Tags.of("ticker", ticker)).increment()
            return
        }

        // Ордер принят биржей → подтверждаем факт исполнения (учитывая partial fill).
        val execution = alorClient.verifyOrder(placed.alorOrderId)
        val fillPrice = execution?.avgPrice ?: entryPrice
        val filledQty = execution?.filledQuantity?.takeIf { it in 1 until validation.quantity }
        val actualQty = filledQty ?: validation.quantity

        val pos =
            Position(
                ticker = ticker,
                direction = direction,
                quantity = actualQty,
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
                alorOrderId = placed.alorOrderId,
            )
        positionRepo.save(pos)
        tradeEventService.recordPositionOpened(pos)
        eventPublisher.publishPositionOpened(pos)
        meterRegistry
            .counter(
                "futures.position.opened",
                Tags.of("ticker", ticker, "direction", direction.name),
            ).increment()
        logger.info {
            "Opened futures $ticker $direction qty=$actualQty @ $fillPrice " +
                "sl=${validation.stopLossPrice} tp=${validation.takeProfitPrice} " +
                "margin=${validation.marginRequired} liq=${validation.liquidationPrice}"
        }
    }

    private suspend fun monitorOpenPositions(
        ticker: String,
        price: BigDecimal,
    ) {
        val open = positionRepo.findByStatus(PositionStatus.OPEN).filter { it.ticker == ticker }
        for (pos in open) {
            if (pos.instrumentType != InstrumentType.FUTURES) continue

            // Позиция ожидает подтверждения входа — SL/TP/закрытие не трогаем,
            // ждём State Reconciliation.
            if (pos.pendingEntry) {
                resolveEntryViaOutbox(pos)
                continue
            }

            // Закрытие уже в полёте — новый ордер НЕ создаём (защита от double execution).
            if (pos.pendingClose) {
                if (pos.closeOrderId != null) {
                    confirmCloseFill(pos, price, pos.closeReason ?: "RECONCILIATION")
                } else {
                    resolveCloseViaOutbox(pos)
                }
                continue
            }

            pos.currentPrice = price

            // 1. Guardrail ликвидации — самый приоритетный
            when (futuresRiskEngine.checkLiquidationDistance(pos, price)) {
                FuturesRiskEngine.LiquidationStatus.CRITICAL -> {
                    logger.error { "LIQUIDATION_CRITICAL ${pos.ticker} @ $price — immediate market close" }
                    closeFuturesPosition(pos, price, "LIQUIDATION_CRITICAL")
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
                closeFuturesPosition(pos, price, "STOP_LOSS")
                continue
            }
            if (riskManagement.shouldCloseByTP(pos, price)) {
                closeFuturesPosition(pos, price, "TAKE_PROFIT")
                continue
            }
            if (riskManagement.shouldCloseByTrailing(pos, price)) {
                closeFuturesPosition(pos, price, "TRAILING_STOP")
                continue
            }

            // 3. Подтягивание trailing (только в прибыль, с учётом вариационной маржи)
            futuresRiskEngine.updateTrailingStop(pos, price)
            positionRepo.save(pos)
        }
    }

    /**
     * Закрытие фьючерсной позиции (стейт-машина, защита от double execution).
     */
    private suspend fun closeFuturesPosition(
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

        val side = if (pos.direction == PositionDirection.LONG) "sell" else "buy"
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
                // Запрос мог дойти до Alor → НЕ создаём дублирующий ордер.
                // Outbox переотправит с тем же idempotency key после State Reconciliation.
                logger.warn {
                    "Close order for ${pos.ticker} UNCERTAIN (outbox=${placed.outboxId}); " +
                        "position stays open, pending outbox reconciliation"
                }
                pos.pendingClose = true
                pos.closeOrderId = null
                pos.closeReason = reason
                positionRepo.save(pos)
                meterRegistry.counter("futures.close.uncertain", Tags.of("ticker", pos.ticker)).increment()
            } else {
                // Определённый отказ (ордер не принят биржей) — позиция остаётся OPEN,
                // следующая итерация мониторинга повторит закрытие (новый idempotency key).
                logger.error { "Close order NOT accepted by Alor for ${pos.ticker} ($reason); position stays OPEN" }
                meterRegistry.counter("futures.close.rejected", Tags.of("ticker", pos.ticker)).increment()
                pos.pendingClose = false
                positionRepo.save(pos)
            }
            return
        }

        // Ордер принят — фиксируем close-стейт и подтверждаем исполнение.
        pos.closeOrderId = placed.alorOrderId
        pos.pendingClose = true
        pos.closeReason = reason
        positionRepo.save(pos)
        confirmCloseFill(pos, price, reason)
    }

    /**
     * Подтверждение исполнения close-ордера через verifyOrder.
     * Полное исполнение → финализация; частичное → дозакрытие остатка;
     * неизвестное состояние → остаёмся в pendingClose (реконсилятор повторит).
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
        val filled = execution.filledQuantity.coerceIn(0, pos.quantity)
        val avg = execution.avgPrice ?: expectedPrice
        if (filled <= 0) {
            logger.info { "Close order $orderId for ${pos.ticker} not yet filled (status=${execution.status})" }
            return
        }
        if (filled >= pos.quantity) {
            finalizeClosePosition(pos, avg, reason)
        } else {
            applyPartialClose(pos, filled, avg)
        }
    }

    /**
     * Partial fill: реализуем P&L закрытой части, уменьшаем quantity.
     * Остаток дозакрывается следующей итерацией мониторинга (новый ордер с новым ключом).
     */
    private suspend fun applyPartialClose(
        pos: Position,
        filled: Int,
        avg: BigDecimal,
    ) {
        val pointValue = instrumentsConfig.pointValue(pos.ticker)
        val closedQty = BigDecimal(filled)
        val partialPnl =
            when (pos.direction) {
                PositionDirection.LONG -> {
                    avg.subtract(pos.entryPrice).multiply(pointValue).multiply(closedQty)
                }

                PositionDirection.SHORT -> {
                    pos.entryPrice
                        .subtract(avg)
                        .multiply(pointValue)
                        .multiply(closedQty)
                }
            }
        pos.realizedPnl = pos.realizedPnl.add(partialPnl)
        pos.quantity -= filled
        pos.closeOrderId = null
        pos.pendingClose = false
        pos.currentPrice = avg
        positionRepo.save(pos)
        meterRegistry.counter("futures.partial_close", Tags.of("ticker", pos.ticker)).increment()
        logger.warn {
            "PARTIAL close ${pos.ticker}: closed=$filled remainder=${pos.quantity} @ $avg " +
                "realized=$partialPnl ₽ (cumulative=${pos.realizedPnl}); remainder will be re-closed"
        }
    }

    /**
     * Полное закрытие позиции: P&L = realizedPnl (partial) + P&L остатка.
     */
    private suspend fun finalizeClosePosition(
        pos: Position,
        closePrice: BigDecimal,
        reason: String,
    ) {
        val pointValue = instrumentsConfig.pointValue(pos.ticker)
        val qty = BigDecimal(pos.quantity)
        val remainderPnl =
            when (pos.direction) {
                PositionDirection.LONG -> {
                    closePrice.subtract(pos.entryPrice).multiply(pointValue).multiply(qty)
                }

                PositionDirection.SHORT -> {
                    pos.entryPrice
                        .subtract(closePrice)
                        .multiply(pointValue)
                        .multiply(qty)
                }
            }
        val totalPnl = pos.realizedPnl.add(remainderPnl)

        pos.closePrice = closePrice
        pos.pnl = totalPnl
        pos.status = if (reason == "TAKE_PROFIT") PositionStatus.TAKE_PROFIT else PositionStatus.CLOSED
        pos.closedAt = LocalDateTime.now()
        pos.closeReason = reason
        pos.pendingClose = false
        pos.closeOrderId = null
        positionRepo.save(pos)
        tradeEventService.recordPositionClosed(pos, reason)
        eventPublisher.publishPositionClosed(pos)
        meterRegistry.counter("futures.position.closed", Tags.of("ticker", pos.ticker, "reason", reason)).increment()
        logger.info { "Closed futures ${pos.ticker} reason=$reason pnl=$totalPnl ₽ @ $closePrice" }
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
                        logger.error(e) { "Futures reconciler error for ${pos.id}/${pos.ticker}" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Futures reconciler error" }
            }
        }
    }

    /**
     * Сверка pendingEntry-позиции через outbox-запись: когда доставка подтверждена
     * (SENT + orderNumber) — проверяем факт исполнения и фиксируем реальный qty/цену.
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
                // Доставка исчерпала попытки; перед каждой переотправкой reconcile давал
                // NOT_FOUND — ордера на бирже нет, вход не состоялся.
                abandonEntry(pos, "ENTRY_NOT_CONFIRMED")
            }

            else -> {} // PENDING / FAILED (ещё ретраится) → ждём
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
                // Все попытки доставки исчерпаны и reconcile ни разу не нашёл ордер на бирже
                // (иначе была бы SENT). Безопасно создать новый ордер (новый idempotency key).
                logger.warn { "Pending close ${pos.id}/${pos.ticker} permanently failed; resetting for a fresh close order" }
                pos.pendingClose = false
                pos.closeOrderId = null
                positionRepo.save(pos)
            }

            else -> {} // PENDING / FAILED (ещё ретраится) → ждём outbox worker
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
        meterRegistry.counter("futures.entry.abandoned", Tags.of("ticker", pos.ticker, "reason", reason)).increment()
    }
}
