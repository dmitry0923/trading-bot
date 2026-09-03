package com.trading.bot.application

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.ExitRules
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.TradeRiskDecision
import com.trading.bot.model.PositionDirection
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.service.ReactiveRedisCacheService
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Собирает параметры заявки (OrderParams) из решения стратегического этапа
 * (Signal) и расчётов риск-этапов (RiskVerdict + PositionSizeResult).
 *
 * Здесь же происходит расчёт дефолтных SL/TP (по пунктам — фьючерсы,
 * по проценту — акции) и фиксация фактических риск-параметров в историю
 * стратегий ([StrategyRepository.updateOrderParams] + Redis).
 */
@Component
class OrderBuilder(
    private val riskConfig: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val strategyRepo: StrategyRepository,
    private val redis: ReactiveRedisCacheService,
) {
    /**
     * Параметры заявки для фьючерса: SL/TP в ценах от [PositionSizeResult],
     * маржа, ликвидация, плечо, стоп в пунктах.
     *
     * @param stopLossPoints дистанция стопа в пунктах (ATR-адаптивная в live,
     *   fallback — [RiskConfig.defaultStopLossPoints]); ТП остаётся фиксированным.
     */
    fun buildFuturesOrderParams(
        ticker: String,
        direction: PositionDirection,
        entryPrice: BigDecimal,
        currentGo: BigDecimal,
        size: PositionSizeResult,
        leverage: BigDecimal,
        stopLossPoints: Int,
        takeProfitPointsOverride: Int? = null,
    ): OrderParams {
        val instrument =
            instrumentsConfig.find(ticker)
                ?: return OrderParams(direction = direction, quantity = 0)
        val takeProfitPoints = takeProfitPointsOverride ?: riskConfig.defaultTakeProfitPoints
        val slOffset = BigDecimal(stopLossPoints).multiply(instrument.priceStep)
        val tpOffset = BigDecimal(takeProfitPoints).multiply(instrument.priceStep)
        val stopLoss =
            when (direction) {
                PositionDirection.LONG -> entryPrice.subtract(slOffset)
                PositionDirection.SHORT -> entryPrice.add(slOffset)
            }
        val takeProfit =
            when (direction) {
                PositionDirection.LONG -> entryPrice.add(tpOffset)
                PositionDirection.SHORT -> entryPrice.subtract(tpOffset)
            }
        return OrderParams(
            direction = direction,
            quantity = size.quantity,
            stopLossPrice = stopLoss,
            takeProfitPrice = takeProfit,
            marginRequired = size.marginRequired,
            liquidationPrice = size.liquidationPrice,
            leverage = leverage,
            goPerContract = currentGo,
            stopLossPoints = stopLossPoints,
            trailingStopPrice = stopLoss,
        )
    }

    /**
     * Параметры заявки для spot-инструмента (акция / FX): SL/TP по ATR (если доступен)
     * или по проценту от цены входа, округлённые до сетки цен инструмента (priceStep).
     * Размер позиции (quantity) вычисляет адаптивный риск-менеджмент (Kelly)
     * в оркестраторе входа.
     *
     * @param atr текущий ATR (MINUTE_10, 14 периодов). Если null или множители = 0 —
     *   fallback на процентный SL/TP из InstrumentSpec/RiskConfig.
     */
    fun buildSpotOrderParams(
        ticker: String,
        direction: PositionDirection,
        quantity: Int,
        entryPrice: BigDecimal,
        atr: BigDecimal? = null,
        frozenSlPercent: Double? = null,
        frozenTpPercent: Double? = null,
    ): OrderParams {
        val spec =
            instrumentsConfig.find(ticker)
                ?: return OrderParams(direction = direction, quantity = 0)
        val priceStep = spec.priceStep
        // P1-аудит: при LIVE-исполнении замороженной стратегии SL/TP берутся ИЗ frozen
        // (проценты), НЕ из ATR/дефолтного конфига. Процентный путь также исключает
        // ATR-адаптивность, ровно как в перевалидированном backtest.
        val frozenSl = frozenSlPercent?.takeIf { it > 0.0 }?.let { BigDecimal.valueOf(it) }
        val frozenTp = frozenTpPercent?.takeIf { it > 0.0 }?.let { BigDecimal.valueOf(it) }
        val useAtr =
            frozenSl == null && frozenTp == null &&
                atr != null && atr > BigDecimal.ZERO &&
                riskConfig.atrSlMultiplier > BigDecimal.ZERO && riskConfig.atrTpMultiplier > BigDecimal.ZERO
        val stopLoss =
            when {
                frozenSl != null -> {
                    ExitRules.calcSL(entryPrice, direction, frozenSl, priceStep)
                }

                useAtr -> {
                    ExitRules.calcSLByAtr(entryPrice, direction, atr!!, riskConfig.atrSlMultiplier, priceStep)
                }

                else -> {
                    val slPercent = spec.effectiveSlPercent(riskConfig.defaultStopLossPercent)
                    ExitRules.calcSL(entryPrice, direction, slPercent, priceStep)
                }
            }
        val takeProfit =
            when {
                frozenTp != null -> {
                    ExitRules.calcTP(entryPrice, direction, frozenTp, priceStep)
                }

                useAtr -> {
                    ExitRules.calcTPByAtr(entryPrice, direction, atr!!, riskConfig.atrTpMultiplier, priceStep)
                }

                else -> {
                    val tpPercent = spec.effectiveTpPercent(riskConfig.defaultTakeProfitPercent)
                    ExitRules.calcTP(entryPrice, direction, tpPercent, priceStep)
                }
            }
        return OrderParams(
            direction = direction,
            quantity = quantity,
            stopLossPrice = stopLoss,
            takeProfitPrice = takeProfit,
            trailingStopPrice = if (riskConfig.trailingStopEnabled) stopLoss else null,
        )
    }

    /**
     * Фиксирует фактические риск-параметры заявки в историю стратегий
     * (БД + Redis): quantity/SL/TP/trailing заполняются после исполнения входа.
     * До этого момента стратегия хранится как чистое направление ([TradeRiskDecision.of]).
     */
    suspend fun recordStrategyExecution(decision: TradeRiskDecision) {
        if (decision.quantity <= 0) return
        strategyRepo.updateOrderParams(
            cycleId = decision.cycleId,
            ticker = decision.ticker,
            quantity = decision.quantity,
            stopLoss = decision.stopLoss,
            takeProfit = decision.takeProfit,
            trailingStop = decision.trailingStop,
        )
        val existing = redis.getStrategy(decision.ticker)
        if (existing != null) {
            val updated =
                existing.copy(
                    quantity = decision.quantity,
                    stopLoss = decision.stopLoss,
                    takeProfit = decision.takeProfit,
                    trailingStop = decision.trailingStop,
                )
            redis.saveStrategy(updated)
        }
    }
}
