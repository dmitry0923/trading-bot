package com.trading.bot.domain.risk

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.PositionDirection
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Расчёт размера позиции для фьючерса Si (доллар/рубль).
 *
 * Входные параметры:
 *   portfolioMoney = депозит (50 000 ₽)
 *   stopLossPoints  = стоп в пунктах (default 50)
 *   currentGo       = текущее гарантийное обеспечение (default 15 000 ₽)
 *   effectiveLeverage = clamp(userLeverage, min, max) = 2.0
 *
 * ФОРМУЛЫ (все расчёты в рублях):
 *   1. marginPerContract   = go / leverage = 15000 / 2 = 7500 ₽
 *   2. riskAmount          = portfolioMoney * riskPerTradePercent / 100 = 50000 * 1% = 500 ₽
 *   3. lossPerContract     = stopLossPoints * priceStepCost = 50 * 10 = 500 ₽
 *   4. maxContractsByRisk  = riskAmount / lossPerContract = 500 / 500 = 1
 *   5. marginBudget        = portfolioMoney * maxMarginUsagePercent / 100 = 50000 * 30% = 15000 ₽
 *   6. maxContractsByMargin= marginBudget / marginPerContract = 15000 / 7500 = 2
 *   7. finalQty            = floor(min(maxContractsByRisk, maxContractsByMargin, maxContractsPerPosition))
 *
 * Ликвидация (guardrail):
 *   pointValue = priceStepCost / priceStep = 10 / 0.01 = 1000 ₽ на 1.0 цены
 *   bufferPrice = (marginPerContract * leverage) / pointValue = (7500 * 2) / 1000 = 15 ₽
 *   liquidationPrice (LONG)  = entryPrice - bufferPrice
 *   liquidationPrice (SHORT) = entryPrice + bufferPrice
 *
 * Если finalQty < 1 → возвращаем quantity = 0 с причиной отказа (вход запрещён).
 */
@Service
class FuturesPositionSizer(
    private val leverageConfig: LeverageConfig,
    private val riskConfig: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
) {
    /**
     * Базовый расчёт без цены входа (liquidationPrice = null).
     */
    fun calculateSiContracts(
        portfolioMoney: BigDecimal,
        stopLossPoints: Int,
        currentGo: BigDecimal,
    ): PositionSizeResult = calculateSiContracts(portfolioMoney, stopLossPoints, currentGo, null, null)

    /**
     * Полный расчёт с ценой входа и направлением — вычисляет liquidationPrice.
     */
    fun calculateSiContracts(
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
            instrumentsConfig.find("Si")
                ?: return PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, "INSTRUMENT_NOT_FOUND")
        if (stopLossPoints <= 0) {
            return PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, "INVALID_STOP_LOSS_POINTS")
        }

        val leverage = leverageConfig.effective()

        // 1. Маржа на один контракт (руб): go / leverage
        val marginPerContract = currentGo.divide(leverage, 4, RoundingMode.HALF_UP)

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
        val liquidationPrice = calculateLiquidationPrice(entryPrice, direction, marginPerContract, leverage, instrument)

        return PositionSizeResult(
            quantity = finalQty,
            marginRequired = marginRequired,
            riskAmount = riskAmount,
            liquidationPrice = liquidationPrice,
            reason = null,
        )
    }

    /**
     * Ликвидационная цена для LONG/SHORT.
     *
     * pointValue = priceStepCost / priceStep (для Si: 1000 ₽/цена)
     * bufferPrice = (marginPerContract * leverage) / pointValue
     *   Si: (7500 * 2) / 1000 = 15 ₽ — при таком движении против позиции
     *       теряется вся маржа контракта.
     */
    private fun calculateLiquidationPrice(
        entryPrice: BigDecimal?,
        direction: PositionDirection?,
        marginPerContract: BigDecimal,
        leverage: BigDecimal,
        instrument: InstrumentsConfig.InstrumentSpec,
    ): BigDecimal? {
        if (entryPrice == null || direction == null) return null
        val pointValue = instrument.priceStepCost.divide(instrument.priceStep, 6, RoundingMode.HALF_UP)
        if (pointValue <= BigDecimal.ZERO) return null

        val bufferPrice =
            marginPerContract
                .multiply(leverage)
                .divide(pointValue, 6, RoundingMode.HALF_UP)

        return when (direction) {
            PositionDirection.LONG -> entryPrice.subtract(bufferPrice)
            PositionDirection.SHORT -> entryPrice.add(bufferPrice)
        }
    }
}

/**
 * Результат расчёта размера фьючерсной позиции.
 *
 * @property quantity итоговое количество контрактов (0 = вход запрещён)
 * @property marginRequired требуемая маржа в рублях
 * @property riskAmount допустимый риск на сделку в рублях
 * @property liquidationPrice расчётная цена ликвидации (null, если не считалась)
 * @property reason причина отказа при quantity = 0 (null при успехе)
 */
data class PositionSizeResult(
    val quantity: Int,
    val marginRequired: BigDecimal,
    val riskAmount: BigDecimal,
    val liquidationPrice: BigDecimal?,
    val reason: String?,
)
