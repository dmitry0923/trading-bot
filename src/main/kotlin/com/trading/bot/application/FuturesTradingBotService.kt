package com.trading.bot.application

import com.trading.bot.client.AlorClient
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
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.context.event.EventListener
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
 */
@Service
class FuturesTradingBotService(
    private val futuresRiskEngine: FuturesRiskEngine,
    private val tradingHoursGuard: TradingHoursGuard,
    private val alorClient: AlorClient,
    private val alorFuturesClient: AlorFuturesClient,
    private val orderOutboxService: OrderOutboxService,
    private val positionRepo: PositionRepository,
    private val riskManagement: RiskManagementService,
    private val instrumentsConfig: InstrumentsConfig,
    private val leverageConfig: LeverageConfig,
    private val riskConfig: RiskConfig,
    private val eventPublisher: TradingEventPublisher,
    private val tradeEventService: TradeEventService,
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
            logger.error { "Order failed for $ticker" }
            meterRegistry.counter("futures.order.failed", Tags.of("ticker", ticker)).increment()
            return
        }
        val execution = alorClient.verifyOrder(placed.alorOrderId)
        val fillPrice = execution?.avgPrice ?: entryPrice

        val pos =
            Position(
                ticker = ticker,
                direction = direction,
                quantity = validation.quantity,
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
            "Opened futures $ticker $direction qty=${validation.quantity} @ $fillPrice " +
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

    private suspend fun closeFuturesPosition(
        pos: Position,
        price: BigDecimal,
        reason: String,
    ) {
        val side = if (pos.direction == PositionDirection.LONG) "sell" else "buy"
        val placed = orderOutboxService.placeOrder(pos.ticker, side, pos.quantity, null, "market")
        val execution = placed.alorOrderId?.let { alorClient.verifyOrder(it, expectedPrice = price) }
        val closePrice = execution?.avgPrice ?: price

        // P&L фьючерса (₽): (close - entry) * qty * pointValue
        val pointValue = instrumentsConfig.pointValue(pos.ticker)
        val qty = BigDecimal(pos.quantity)
        val pnl =
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

        pos.closePrice = closePrice
        pos.pnl = pnl
        pos.status = if (reason == "TAKE_PROFIT") PositionStatus.TAKE_PROFIT else PositionStatus.CLOSED
        pos.closedAt = LocalDateTime.now()
        pos.closeReason = reason
        positionRepo.save(pos)
        tradeEventService.recordPositionClosed(pos, reason)

        // Daily P&L обновляется в DailyLossCircuitBreaker через событие.
        eventPublisher.publishPositionClosed(pos)
        meterRegistry.counter("futures.position.closed", Tags.of("ticker", pos.ticker, "reason", reason)).increment()
        logger.info { "Closed futures ${pos.ticker} reason=$reason pnl=$pnl ₽ @ $closePrice" }
    }
}
