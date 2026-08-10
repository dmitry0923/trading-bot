package com.trading.bot.model.dto

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Экспозиция одной открытой позиции.
 *
 * @param ticker тикер
 * @param direction направление (LONG/SHORT)
 * @param sector сектор из risk.sectors (UNKNOWN, если не задан)
 * @param notionalRub подписанный нотионал в рублях (long > 0, short < 0)
 * @param exposurePercentAum |нотионал| / AUM, %
 */
data class PositionExposure(
    val ticker: String,
    val direction: String,
    val sector: String,
    val notionalRub: BigDecimal,
    val exposurePercentAum: BigDecimal,
)

/**
 * Секторная экспозиция портфеля.
 *
 * @param sector сектор
 * @param positionCount число открытых позиций в секторе
 * @param grossPercentAum сумма |нотионалов| сектора / AUM, %
 * @param netPercentAum сумма подписанных нотионалов сектора / AUM, % (long-short)
 */
data class SectorExposure(
    val sector: String,
    val positionCount: Int,
    val grossPercentAum: BigDecimal,
    val netPercentAum: BigDecimal,
)

/**
 * Live-снимок портфельного риска для Correlation Engine.
 *
 * Собирается [com.trading.bot.service.RiskExposureService] по открытым позициям
 * без записи в БД. В отличие от входных фильтров (VaR/effectiveN/концентрация
 * в [com.trading.bot.application.risk.PortfolioRiskEngineImpl]) показывает
 * ТЕКУЩЕЕ состояние портфеля, а не гипотетический вход кандидата.
 *
 * @param aum текущий AUM (risk.maxPositionRub)
 * @param exposureScore агрегированный Exposure Score 0..100 (выше = рискованнее):
 *   взвешенный композит из направленной концентрации, эффективного числа ставок,
 *   VaR95, утилизации Gross/Net Exposure. Информационный + метрики.
 * @param grossExposureRub сумма |нотионалов| всех позиций
 * @param grossExposurePercent gross / AUM, %
 * @param grossLimitPercent лимит (risk.maxGrossExposurePercent)
 * @param netExposureRub сумма подписанных нотионалов (long - short)
 * @param netExposurePercent net / AUM, %
 * @param netLimitPercent лимит (risk.maxNetExposurePercent)
 * @param perPositionExposure экспозиция каждой открытой позиции
 * @param perSectorExposure секторная экспозиция
 * @param correlationMatrix попарная корреляция закрытий открытых позиций (Пирсон)
 * @param maxPairCorrelation максимальная попарная корреляция в портфеле
 * @param effectivePositions эффективное число НЕЗАВИСИМЫХ ставок (1 = одна ставка на рынок)
 * @param var95Rub однодневный VaR95 портфеля в рублях
 * @param var95Percent VaR95 / AUM, %
 * @param timestamp время снимка
 */
data class RiskExposureReport(
    val aum: BigDecimal,
    val exposureScore: Int,
    val grossExposureRub: BigDecimal,
    val grossExposurePercent: BigDecimal,
    val grossLimitPercent: BigDecimal,
    val netExposureRub: BigDecimal,
    val netExposurePercent: BigDecimal,
    val netLimitPercent: BigDecimal,
    val perPositionExposure: List<PositionExposure>,
    val perSectorExposure: List<SectorExposure>,
    val correlationMatrix: Map<String, Map<String, Double?>>,
    val maxPairCorrelation: Double,
    val effectivePositions: BigDecimal,
    val var95Rub: BigDecimal,
    val var95Percent: BigDecimal,
    val timestamp: LocalDateTime,
)
