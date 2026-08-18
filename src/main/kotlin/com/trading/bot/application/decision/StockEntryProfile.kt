package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.risk.StockRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.RiskEngine
import com.trading.bot.domain.risk.TradeRiskDecision
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
 * - сайзинг: адаптивный Kelly ([AdaptiveRiskService.calculateOptimalPositionSize]);
 *   размер = Kelly budget / notionalPerLot; если 0 лотов — вход блокируется;
 * - post-sizing: лимиты Gross/Net Exposure ([RiskManagementService.exceedsPortfolioLimits]);
 * - параметры заявки: SL/TP по проценту ([OrderBuilder.buildSpotOrderParams]);
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
    ): EntryRequest? =
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
        val spec = instrumentsConfig.find(signal.ticker)
        val lotSize = spec?.lotSize?.coerceAtLeast(1) ?: 1
        val notionalPerLot = entryPrice.multiply(BigDecimal(lotSize))

        // F-12 (roadmap 13.25): AUM для Kelly берётся по аккаунту входа, а не глобально.
        val kellySizeRub =
            adaptiveRisk.calculateOptimalPositionSize(
                signal.ticker,
                signalStrength = signal.signalStrength,
                accountId = request.accountId,
            )
        val kellyLots =
            if (kellySizeRub > BigDecimal.ZERO && notionalPerLot > BigDecimal.ZERO) {
                kellySizeRub.divide(notionalPerLot, 0, RoundingMode.DOWN).toInt()
            } else {
                0
            }

        // Риск-кап на сделку: убыток при срабатывании стопа не может превысить
        // riskPerTradePercent% от AUM. lossPerLot = price × SL% × lotSize + 2×commission (entry + exit).
        val effectiveSlPercent = spec?.effectiveSlPercent(riskConfig.defaultStopLossPercent)
            ?: riskConfig.defaultStopLossPercent
        val commissionPerLot = spec?.commissionRub ?: BigDecimal.ZERO
        val riskAmount =
            request.portfolioMoney
                .multiply(BigDecimal(riskConfig.riskPerTradePercent.toString()))
                .divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val lossPerLot =
            entryPrice
                .multiply(effectiveSlPercent)
                .divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(lotSize))
                .add(commissionPerLot.multiply(BigDecimal(2)))
        val maxLotsByRisk =
            if (lossPerLot > BigDecimal.ZERO) {
                riskAmount.divide(lossPerLot, 0, RoundingMode.DOWN).toInt()
            } else {
                0
            }

        val finalQty = minOf(kellyLots, maxLotsByRisk)
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
            marginRequired = spec?.notional(finalQty, entryPrice)
                ?: entryPrice.multiply(BigDecimal(finalQty)),
            riskAmount = riskAmount,
            liquidationPrice = null,
            reason = null,
        )
    }

    override suspend fun postSizingChecks(
        ticker: String,
        direction: PositionDirection,
        entryPrice: BigDecimal,
        size: PositionSizeResult,
        openPositions: List<Position>,
    ): String? {
        if (size.quantity < 1) return "ZERO_RISK_SIZE"
        val spec = instrumentsConfig.find(ticker)
        val candidateNotional = spec?.notional(size.quantity, entryPrice)
            ?: entryPrice.multiply(BigDecimal(size.quantity))
        return if (risk.exceedsPortfolioLimits(candidateNotional, direction, openPositions)) "PORTFOLIO_LIMIT" else null
    }

    override fun buildOrderParams(
        ticker: String,
        direction: PositionDirection,
        entryPrice: BigDecimal,
        size: PositionSizeResult,
        request: EntryRequest,
    ): OrderParams = orderBuilder.buildSpotOrderParams(ticker, direction, size.quantity, entryPrice)

    override fun portfolioMode(): PortfolioMode = PortfolioMode.ENFORCED

    override fun buildPosition(
        decision: TradeRiskDecision,
        orderId: String?,
        pending: Boolean,
        fillPrice: BigDecimal,
        qty: Int,
    ): Position =
        Position(
            ticker = decision.ticker,
            direction = decision.direction,
            quantity = qty,
            entryPrice = fillPrice,
            currentPrice = fillPrice,
            stopLoss = decision.stopLoss,
            takeProfit = decision.takeProfit,
            trailingStopPrice = if (decision.trailingStop) decision.stopLoss else null,
            alorOrderId = orderId,
            pendingEntry = pending,
            cycleId = decision.cycleId,
        )

    override suspend fun onOpened(
        decision: TradeRiskDecision,
        opened: Position,
    ) {
        risk.updateDailyPnL(BigDecimal.ZERO, opened.accountId)
        agentLogRepo.save(
            AgentLog(
                cycleId = decision.cycleId,
                agentName = "TradingBot",
                ticker = decision.ticker,
                action = "OPEN",
                signalStrength = decision.signalStrength,
                reasoning =
                    "Opened ${opened.direction.name} ${decision.quantity} @ ${opened.entryPrice} " +
                        "(target=${decision.targetPrice}, adaptive qty=${decision.quantity}, kelly=${decision.requestedQuantity})",
            ),
        )
    }
}
