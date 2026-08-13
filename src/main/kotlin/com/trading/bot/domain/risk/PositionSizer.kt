package com.trading.bot.domain.risk

import com.trading.bot.model.PositionDirection
import java.math.BigDecimal

/**
 * Результат расчёта размера позиции.
 *
 * @property quantity итоговое количество контрактов (0 = вход запрещён)
 * @property marginRequired требуемая маржа в рублях
 * @property riskAmount допустимый риск на сделку в рублях
 * @property liquidationPrice ОЦЕНОЧНАЯ цена ликвидации (упрощённая модель для
 *   риск-чека; null, если не считалась). Для production предпочитать официальную
 *   liquidation price биржи, если она предоставляется.
 * @property reason причина отказа при quantity = 0 (null при успехе)
 */
data class PositionSizeResult(
    val quantity: Int,
    val marginRequired: BigDecimal,
    val riskAmount: BigDecimal,
    val liquidationPrice: BigDecimal?,
    val reason: String?,
)

/**
 * Размер позиции и дефолтные SL/TP-расчёты — отдельный этап пайплайна
 * (после RiskEngine, перед OrderBuilder).
 */
interface PositionSizer {
    /**
     * Базовый расчёт без цены входа (liquidationPrice = null).
     */
    fun calculateContracts(
        ticker: String,
        portfolioMoney: BigDecimal,
        stopLossPoints: Int,
        currentGo: BigDecimal,
    ): PositionSizeResult

    /**
     * Полный расчёт с ценой входа и направлением — вычисляет liquidationPrice.
     */
    fun calculateContracts(
        ticker: String,
        portfolioMoney: BigDecimal,
        stopLossPoints: Int,
        currentGo: BigDecimal,
        entryPrice: BigDecimal?,
        direction: PositionDirection?,
    ): PositionSizeResult
}
