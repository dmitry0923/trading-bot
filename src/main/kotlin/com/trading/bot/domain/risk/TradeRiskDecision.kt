package com.trading.bot.domain.risk

import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import java.math.BigDecimal

/**
 * Единое риск-решение сделки (MR-010).
 *
 * Собирается на выходе риск-пайплайна входа: стратегический сигнал ([Signal]),
 * риск-вход ([EntryRequest]), размер ([PositionSizeResult]) и параметры заявки
 * ([OrderParams]) схлопываются в одну immutable-модель. Далее решение прогоняется
 * через портфельный риск, исполнение и A/B-эксперимент без дублирования полей.
 *
 * На стратегическом этапе ([of]) риск-поля (quantity/SL/TP/маржа) пусты —
 * они заполняются при входе ([from]).
 *
 * @param ticker инструмент
 * @param cycleId идентификатор цикла (= trace_id)
 * @param timeframe таймфрейм
 * @param action направление стратегии (BUY/SELL/HOLD)
 * @param direction направление позиции (LONG/SHORT)
 * @param quantity итоговый объём заявки (после портфельного SCALE)
 * @param requestedQuantity объём до портфельного SCALE (размер сайзера)
 * @param entryPrice цена входа
 * @param targetPrice целевая цена
 * @param stopLoss стоп-лосс
 * @param takeProfit тейк-профит
 * @param trailingStop trailing-стоп включён
 * @param signalStrength сила сигнала
 * @param reasoning обоснование
 * @param strategyName имя стратегии-победителя
 * @param riskAmount допустимый риск на сделку в рублях
 * @param marginRequired требуемая маржа в рублях
 * @param liquidationPrice ОЦЕНОЧНАЯ цена ликвидации (для риск-чека)
 * @param leverage плечо
 * @param goPerContract гарантийное обеспечение на контракт
 * @param stopLossPoints дистанция стопа в пунктах (фьючерсы)
 * @param accountId аккаунт (multi-account); null = legacy single-account
 */
data class TradeRiskDecision(
    val ticker: String,
    val cycleId: String,
    val timeframe: String,
    val action: StrategyAction,
    val direction: PositionDirection,
    val quantity: Int = 0,
    val requestedQuantity: Int = 0,
    val entryPrice: BigDecimal? = null,
    val targetPrice: BigDecimal,
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    val trailingStop: Boolean = false,
    val signalStrength: Double,
    val reasoning: String,
    val strategyName: String? = null,
    val riskAmount: BigDecimal? = null,
    val marginRequired: BigDecimal? = null,
    val liquidationPrice: BigDecimal? = null,
    val leverage: BigDecimal? = null,
    val goPerContract: BigDecimal? = null,
    val stopLossPoints: Int? = null,
    val accountId: Long? = null,
) {
    companion object {
        /** Стратегическая стадия: чистое направление, риск-поля пусты. */
        fun of(signal: Signal): TradeRiskDecision =
            TradeRiskDecision(
                ticker = signal.ticker,
                cycleId = signal.cycleId,
                timeframe = signal.timeframe,
                action = signal.action,
                direction = if (signal.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT,
                targetPrice = signal.targetPrice,
                signalStrength = signal.signalStrength,
                reasoning = signal.reasoning,
                strategyName = signal.strategyName,
            )

        /**
         * Выход риск-пайплайна входа: сигнал + риск-вход + размер + параметры
         * заявки схлопываются в одно решение.
         */
        fun from(
            signal: Signal,
            request: EntryRequest,
            size: PositionSizeResult,
            params: OrderParams,
        ): TradeRiskDecision =
            TradeRiskDecision(
                ticker = signal.ticker,
                cycleId = signal.cycleId,
                timeframe = signal.timeframe,
                action = signal.action,
                direction = if (signal.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT,
                quantity = params.quantity,
                requestedQuantity = size.quantity,
                entryPrice = request.entryPrice,
                targetPrice = signal.targetPrice,
                stopLoss = params.stopLossPrice,
                takeProfit = params.takeProfitPrice,
                trailingStop = params.trailingStopPrice != null,
                signalStrength = signal.signalStrength,
                reasoning = signal.reasoning,
                strategyName = signal.strategyName,
                riskAmount = size.riskAmount,
                marginRequired = params.marginRequired ?: size.marginRequired,
                liquidationPrice = params.liquidationPrice,
                leverage = params.leverage,
                goPerContract = params.goPerContract,
                stopLossPoints = params.stopLossPoints,
                accountId = request.accountId,
            )
    }
}
