package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.risk.StockRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.RiskEngine
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.AumProvider
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.RiskManagementService
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Профиль входа для акций/валют.
 *
 * Воспроизводит прежний пайплайн TradingBotService.doOpenPosition:
 * - риск-этап: [StockRiskEngine] (Да/Нет);
 * - pre-sizing: глобальный и внутрисекторный корреляционные фильтры;
 * - сайзинг: адаптивный Kelly ([AdaptiveRiskService.calculateOptimalPositionSize]),
 *   размер позиции = Kelly-бюджет / цена входа (минимум 1 лот);
 * - post-sizing: лимиты Gross/Net Exposure ([RiskManagementService.exceedsPortfolioLimits]);
 * - параметры заявки: SL/TP по проценту ([OrderBuilder.buildStockOrderParams]);
 * - портфельный риск — ENFORCED (BLOCK/SCALE);
 * - после открытия: сброс дневного P&L и лог агента TradingBot (OPEN).
 */
@Component
class StockEntryProfile(
    stockRiskEngine: StockRiskEngine,
    private val adaptiveRisk: AdaptiveRiskService,
    private val risk: RiskManagementService,
    private val candleCache: CandleCacheService,
    private val orderBuilder: OrderBuilder,
    private val riskConfig: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val agentLogRepo: AgentLogRepository,
    private val aumProvider: AumProvider,
) : EntryProfile {
    override val instrumentType: InstrumentType = InstrumentType.STOCK
    override val metricPrefix: String = "bot"
    override val riskEngine: RiskEngine = stockRiskEngine

    override fun matches(ticker: String): Boolean = !instrumentsConfig.isFutures(ticker)

    override suspend fun buildEntryRequest(
        signal: Signal,
        entryPrice: BigDecimal,
        openPositions: List<Position>,
        accountId: Long?,
    ): EntryRequest =
        EntryRequest(
            ticker = signal.ticker,
            action = signal.action,
            entryPrice = entryPrice,
            direction = signal.direction(),
            portfolioMoney = aumProvider.currentAum(accountId),
            currentGo = BigDecimal.ZERO,
            atr = candleCache.calculateAtr(signal.ticker, "MINUTE_10", 14),
            openPositions = openPositions,
            accountId = accountId,
        )

    override suspend fun preSizingChecks(
        ticker: String,
        openPositions: List<Position>,
    ): String? =
        when {
            adaptiveRisk.exceedsCorrelationLimit(ticker, openPositions) -> "CORRELATION"
            adaptiveRisk.exceedsSectorCorrelationLimit(ticker, openPositions) -> "SECTOR_CORRELATION"
            else -> null
        }

    override suspend fun sizePosition(
        signal: Signal,
        entryPrice: BigDecimal,
        request: EntryRequest,
    ): PositionSizeResult {
        val kellySizeRub = adaptiveRisk.calculateOptimalPositionSize(signal.ticker, confidence = signal.confidence)
        val kellyQty =
            if (kellySizeRub > BigDecimal.ZERO) {
                kellySizeRub.divide(entryPrice, 0, RoundingMode.DOWN).toInt()
            } else {
                0
            }

        // Риск-кап на сделку (аналог FuturesPositionSizer): убыток при срабатывании
        // стопа не может превысить riskPerTradePercent% от AUM.
        // lossPerShare = entryPrice * defaultStopLossPercent% (SL-цена у OrderBuilder такая же).
        val riskAmount =
            request.portfolioMoney
                .multiply(BigDecimal(riskConfig.riskPerTradePercent.toString()))
                .divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val lossPerShare =
            entryPrice
                .multiply(BigDecimal(riskConfig.defaultStopLossPercent.toString()))
                .divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
        val maxQtyByRisk =
            if (lossPerShare > BigDecimal.ZERO) {
                riskAmount.divide(lossPerShare, 4, RoundingMode.DOWN).toInt()
            } else {
                0
            }

        val finalQty = minOf(kellyQty, maxQtyByRisk)
        if (finalQty < 1) {
            return PositionSizeResult(
                quantity = 0,
                marginRequired = BigDecimal.ZERO,
                riskAmount = riskAmount,
                liquidationPrice = null,
                reason = "ZERO_RISK_SIZE",
            )
        }
        return PositionSizeResult(
            quantity = finalQty,
            marginRequired = kellySizeRub,
            riskAmount = riskAmount,
            liquidationPrice = null,
            reason = null,
        )
    }

    override suspend fun postSizingChecks(
        direction: PositionDirection,
        entryPrice: BigDecimal,
        size: PositionSizeResult,
        openPositions: List<Position>,
    ): String? {
        val qty = size.quantity.coerceAtLeast(1)
        val candidateNotional = entryPrice.multiply(BigDecimal(qty))
        return if (risk.exceedsPortfolioLimits(candidateNotional, direction, openPositions)) "PORTFOLIO_LIMIT" else null
    }

    override fun buildOrderParams(
        ticker: String,
        direction: PositionDirection,
        entryPrice: BigDecimal,
        size: PositionSizeResult,
        request: EntryRequest,
    ): OrderParams = orderBuilder.buildStockOrderParams(direction, size.quantity.coerceAtLeast(1), entryPrice)

    override fun portfolioMode(): PortfolioMode = PortfolioMode.ENFORCED

    override fun buildPosition(
        signal: Signal,
        params: OrderParams,
        orderId: String?,
        pending: Boolean,
        fillPrice: BigDecimal,
        qty: Int,
    ): Position =
        Position(
            ticker = signal.ticker,
            direction = params.direction,
            quantity = qty,
            entryPrice = fillPrice,
            currentPrice = fillPrice,
            stopLoss = params.stopLossPrice,
            takeProfit = params.takeProfitPrice,
            trailingStopPrice = params.trailingStopPrice,
            alorOrderId = orderId,
            pendingEntry = pending,
            cycleId = signal.cycleId,
        )

    override suspend fun onOpened(
        signal: Signal,
        opened: Position,
        params: OrderParams,
        size: PositionSizeResult,
    ) {
        risk.updateDailyPnL(BigDecimal.ZERO, opened.accountId)
        agentLogRepo.save(
            AgentLog(
                cycleId = signal.cycleId,
                agentName = "TradingBot",
                ticker = signal.ticker,
                action = "OPEN",
                confidence = signal.confidence,
                reasoning =
                    "Opened ${opened.direction.name} ${params.quantity} @ ${opened.entryPrice} " +
                        "(target=${signal.targetPrice}, adaptive qty=${params.quantity}, kelly=${size.quantity})",
            ),
        )
    }
}
