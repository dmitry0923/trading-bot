package com.trading.bot.domain.risk

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import java.math.BigDecimal

/**
 * Входные данные для портфельной риск-проверки.
 *
 * @param candidateTicker тикер кандидата на вход
 * @param candidateDirection направление кандидата
 * @param candidateNotionalRub нотионал кандидата в рублях (qty * entryPrice)
 * @param openPositions текущие открытые позиции
 * @param aum текущий депозит (AUM) в рублях
 */
data class PortfolioRiskRequest(
    val candidateTicker: String,
    val candidateDirection: PositionDirection,
    val candidateNotionalRub: BigDecimal,
    val openPositions: List<Position>,
    val aum: BigDecimal,
)

/**
 * Результат портфельной риск-проверки.
 *
 * В отличие от попарных корреляционных фильтров агрегирует весь портфель
 * (Markowitz-дисперсия, VaR95, effectiveN, направленная концентрация) —
 * «три коррелированные позиции = одна большая ставка на рынок».
 *
 * @param allowed true, если вход разрешён
 * @param reasons причины блокировки при [allowed] = false
 * @param scaleDownFactor множитель уменьшения размера позиции в SCALE-режиме
 *   (1.0 = без изменений)
 * @param portfolioDailyVolPercent дневная волатильность портфеля после добавления кандидата, %
 * @param var95Rub однодневный VaR95 в рублях
 * @param effectivePositions эффективное число позиций (1 / HHI), 1 = одна ставка
 * @param directionalConcentrationPercent |net| / gross * 100
 * @param maxPairCorrelation максимальная попарная корреляция в портфеле
 */
data class PortfolioRiskReport(
    val allowed: Boolean,
    val reasons: List<String> = emptyList(),
    val scaleDownFactor: BigDecimal = BigDecimal.ONE,
    val portfolioDailyVolPercent: BigDecimal = BigDecimal.ZERO,
    val var95Rub: BigDecimal = BigDecimal.ZERO,
    val effectivePositions: BigDecimal = BigDecimal.ZERO,
    val directionalConcentrationPercent: BigDecimal = BigDecimal.ZERO,
    val maxPairCorrelation: Double = 0.0,
)

/**
 * Портфельный риск-этап пайплайна.
 *
 * Отвечает на вопрос «какой риск добавляет кандидат в ПОРТФЕЛЬ» — в отличие от
 * [RiskEngine], который проверяет сделку изолированно. Реализации должны быть
 * чистыми (без Spring, без БД, без исполнения ордеров) — данные приходят через
 * [PortfolioRiskRequest].
 */
interface PortfolioRiskEngine {
    suspend fun evaluate(request: PortfolioRiskRequest): PortfolioRiskReport
}
