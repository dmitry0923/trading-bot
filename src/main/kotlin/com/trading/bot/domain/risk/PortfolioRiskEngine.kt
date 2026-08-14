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
 * @param volatilityDataQuality качество данных о волатильности
 * @param correlationDataQuality качество данных о корреляциях
 * @param dataQualityScale множитель размера по качеству данных
 *   (1.0 = полные данные, <1.0 = оценённые/отсутствующие данные)
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
    val volatilityDataQuality: PortfolioDataQuality = PortfolioDataQuality.KNOWN,
    val correlationDataQuality: PortfolioDataQuality = PortfolioDataQuality.KNOWN,
    val dataQualityScale: BigDecimal = BigDecimal.ONE,
)

/**
 * Качество данных, на которых рассчитан портфельный риск.
 *
 * - [KNOWN] — полные данные (дневная realized-vol, все пары корреляций рассчитаны);
 * - [ESTIMATED] — часть данных оценена (например, волатильность из внутридневных
 *   свечей, масштабированная sqrt(свечей в сессии));
 * - [INSUFFICIENT] — данных нет вовсе; размер кандидата стремится к нулю, а в
 *   режиме жёсткой блокировки портфельного риска вход запрещается
 *   (PORTFOLIO_DATA_INSUFFICIENT).
 */
enum class PortfolioDataQuality {
    KNOWN,
    ESTIMATED,
    INSUFFICIENT,
}

/**
 * Разрешённая матрица корреляций с качеством данных.
 *
 * @param matrix матрица (индекс = позиция в списке тикеров); отсутствующие пары
 *   заменены консервативным fallback (максимальная наблюдаемая корреляция)
 * @param quality качество данных: [PortfolioDataQuality.KNOWN] — все пары
 *   рассчитаны, [PortfolioDataQuality.INSUFFICIENT] — есть пары без данных
 */
data class ResolvedCorrelationMatrix(
    val matrix: List<List<Double>>,
    val quality: PortfolioDataQuality,
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
