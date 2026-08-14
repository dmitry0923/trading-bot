package com.trading.bot.application.risk

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.EstimatedLiquidationPriceProvider
import com.trading.bot.domain.risk.LiquidationPriceProvider
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.PositionSizer
import com.trading.bot.model.PositionDirection
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Расчёт размера позиции для фьючерса (например Si — доллар/рубль).
 *
 * Входные параметры:
 *   ticker          = инструмент из InstrumentsConfig (например "Si")
 *   portfolioMoney  = депозит (50 000 ₽)
 *   stopLossPoints  = стоп в пунктах (default 50)
 *   currentGo       = текущее гарантийное обеспечение (default 15 000 ₽)
 *
 * ФОРМУЛЫ (все расчёты в рублях):
 *   1. marginPerContract   = currentGo (ПОЛНОЕ гарантийное обеспечение биржи).
 *      Пользовательское плечо НЕ делит маржу: брокер требует GO независимо от
 *      выбранного leverage, поэтому `go / leverage` занижало бы маржу и могло
 *      привести к оверсайзингу.
 *   2. riskAmount          = portfolioMoney * riskPerTradePercent / 100 = 50000 * 1% = 500 ₽
 *   3. lossPerContract     = stopLossPoints * priceStepCost = 50 * 10 = 500 ₽
 *   4. maxContractsByRisk  = riskAmount / lossPerContract = 500 / 500 = 1
 *   5. marginBudget        = portfolioMoney * maxMarginUsagePercent / 100 = 50000 * 30% = 15000 ₽
 *   6. maxContractsByMargin= marginBudget / marginPerContract = 15000 / 15000 = 1
 *   7. finalQty            = floor(min(maxContractsByRisk, maxContractsByMargin, maxContractsPerPosition))
 *
 * Ликвидация (guardrail) — ОЦЕНОЧНАЯ дистанция для предварительного риск-чека,
 * считается через [LiquidationPriceProvider] (по умолчанию
 * [EstimatedLiquidationPriceProvider]):
 *   pointValue = priceStepCost / priceStep = 10 / 0.01 = 1000 ₽ на 1.0 цены
 *   bufferPrice = currentGo / pointValue = 15000 / 1000 = 15 ₽
 *   estimatedLiquidationPrice (LONG)  = entryPrice - bufferPrice
 *   estimatedLiquidationPrice (SHORT) = entryPrice + bufferPrice
 *
 * Это упрощённая модель (потеря вариационной маржи = GO, без maintenance margin,
 * комиссий и режима позиции биржи). Для production использовать официальную
 * liquidation price биржи, если она предоставляется (реализация
 * [LiquidationPriceProvider] с биржевым источником).
 *
 * Если finalQty < 1 → возвращаем quantity = 0 с причиной отказа (вход запрещён).
 */
@Service
class FuturesPositionSizer(
    private val riskConfig: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val liquidationPriceProvider: LiquidationPriceProvider = EstimatedLiquidationPriceProvider(),
) : PositionSizer {
    override fun calculateContracts(
        ticker: String,
        portfolioMoney: BigDecimal,
        stopLossPoints: Int,
        currentGo: BigDecimal,
    ): PositionSizeResult = calculateContracts(ticker, portfolioMoney, stopLossPoints, currentGo, null, null)

    override fun calculateContracts(
        ticker: String,
        portfolioMoney: BigDecimal,
        stopLossPoints: Int,
        currentGo: BigDecimal,
        entryPrice: BigDecimal?,
        direction: PositionDirection?,
    ): PositionSizeResult {
        if (portfolioMoney <= BigDecimal.ZERO) {
            return PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, "NON_POSITIVE_PORTFOLIO")
        }
        if (currentGo <= BigDecimal.ZERO) {
            return PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, "INVALID_GO")
        }
        val instrument =
            instrumentsConfig.find(ticker)
                ?: return PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, "INSTRUMENT_NOT_FOUND")
        if (stopLossPoints <= 0) {
            return PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, "INVALID_STOP_LOSS_POINTS")
        }

        // 1. Маржа на один контракт (руб): ПОЛНОЕ гарантийное обеспечение биржи.
        //    Плечо не уменьшает маржу — брокер требует GO независимо от leverage
        //    (консервативно, без оверсайзинга).
        val marginPerContract = currentGo

        // 2. Риск на сделку (руб): депозит * riskPerTradePercent%
        val riskPercent = BigDecimal(riskConfig.riskPerTradePercent.toString())
        val riskAmount =
            portfolioMoney
                .multiply(riskPercent)
                .divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)

        // 3. Убыток с одного контракта на стопе (руб): пункты * стоимость пункта
        val lossPerContract = BigDecimal(stopLossPoints).multiply(instrument.priceStepCost)
        if (lossPerContract <= BigDecimal.ZERO) {
            return PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, "INVALID_LOSS_PER_CONTRACT")
        }

        // 4. Максимум контрактов по риску
        val maxContractsByRisk =
            riskAmount
                .divide(lossPerContract, 4, RoundingMode.DOWN)
                .toInt()

        // 5. Маржинальный бюджет: депозит * maxMarginUsagePercent%
        val marginBudget =
            portfolioMoney
                .multiply(BigDecimal(riskConfig.maxMarginUsagePercent.toString()))
                .divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)

        // 6. Максимум контрактов по марже
        val maxContractsByMargin =
            marginBudget
                .divide(marginPerContract, 4, RoundingMode.DOWN)
                .toInt()

        // 7. Итог: минимум всех лимитов
        val finalQty =
            minOf(
                maxContractsByRisk,
                maxContractsByMargin,
                riskConfig.maxContractsPerPosition,
            )

        if (finalQty < 1) {
            val reason =
                when {
                    maxContractsByRisk < 1 -> "ZERO_RISK_SIZE"
                    maxContractsByMargin < 1 -> "INSUFFICIENT_MARGIN"
                    else -> "ZERO_CONTRACTS_CONFIG"
                }
            return PositionSizeResult(0, BigDecimal.ZERO, riskAmount, null, reason)
        }

        val marginRequired = marginPerContract.multiply(BigDecimal(finalQty))
        val liquidationPrice =
            liquidationPriceProvider.liquidationPrice(
                entryPrice,
                direction,
                marginPerContract,
                instrument.priceStep,
                instrument.priceStepCost,
            )

        return PositionSizeResult(
            quantity = finalQty,
            marginRequired = marginRequired,
            riskAmount = riskAmount,
            liquidationPrice = liquidationPrice,
            reason = null,
        )
    }
}
