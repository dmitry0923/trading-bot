package com.trading.bot.application

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.ExitRules
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.signal.Signal
import com.trading.bot.infrastructure.db.BlockingDb
import com.trading.bot.model.PositionDirection
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.service.RedisCacheService
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
    private val redis: RedisCacheService,
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
    ): OrderParams {
        val instrument =
            instrumentsConfig.find(ticker)
                ?: return OrderParams(direction = direction, quantity = 0)
        val takeProfitPoints = riskConfig.defaultTakeProfitPoints
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
     * Параметры заявки для акции: SL/TP по проценту от цены входа.
     * Размер позиции (quantity) вычисляет адаптивный риск-менеджмент (Kelly)
     * в оркестраторе входа.
     */
    fun buildStockOrderParams(
        direction: PositionDirection,
        quantity: Int,
        entryPrice: BigDecimal,
    ): OrderParams {
        val stopLoss = ExitRules.calcSL(entryPrice, direction, riskConfig.defaultStopLossPercent)
        val takeProfit = ExitRules.calcTP(entryPrice, direction, riskConfig.defaultTakeProfitPercent)
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
     * До этого момента стратегия хранится как чистое направление (Signal).
     */
    suspend fun recordStrategyExecution(
        signal: Signal,
        params: OrderParams,
    ) {
        if (params.quantity <= 0) return
        strategyRepo.updateOrderParams(
            cycleId = signal.cycleId,
            ticker = signal.ticker,
            quantity = params.quantity,
            stopLoss = params.stopLossPrice,
            takeProfit = params.takeProfitPrice,
            trailingStop = params.trailingStopPrice != null,
        )
        val existing = BlockingDb.io { redis.getStrategy(signal.ticker) }
        if (existing != null) {
            val updated =
                existing.copy(
                    quantity = params.quantity,
                    stopLoss = params.stopLossPrice,
                    takeProfit = params.takeProfitPrice,
                    trailingStop = params.trailingStopPrice != null,
                )
            BlockingDb.io { redis.saveStrategy(updated) }
        }
    }
}
