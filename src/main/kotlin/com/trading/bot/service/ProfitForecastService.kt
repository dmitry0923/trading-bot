package com.trading.bot.service

import com.trading.bot.model.ProfitForecast
import com.trading.bot.repository.PositionRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Прогноз прибыли на основе реальной статистики закрытых сделок бота.
 *
 * - Дневная доходность = суммарный реализованный P&L за день / капитал-база
 * - Ожидаемая доходность на горизонте = средняя дневная доходность * горизонт
 * - 95% доверительный интервал = mean * days ± 1.96 * σ * sqrt(days)
 * - Годовая доходность — линейная экстраполяция (252 торговых дня)
 *
 * Сервис питается только реальными данными из таблицы positions (закрытые сделки),
 * поэтому прогноз всегда отражает фактическую эффективность бота.
 */
@Service
class ProfitForecastService(
    private val positionRepo: PositionRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Строит прогноз на основе закрытых сделок за последние [lookbackDays] дней.
     *
     * @param horizonDays горизонт прогноза (дней)
     * @param capitalBase капитал-база, от которой считается доходность (пул инвесторов)
     * @param lookbackDays окно реальных данных для оценки статистики
     */
    suspend fun forecast(
        horizonDays: Int = 90,
        capitalBase: BigDecimal = BigDecimal(1_000_000),
        lookbackDays: Int = 90,
    ): ProfitForecast {
        val since = LocalDateTime.now().minusDays(lookbackDays.toLong())
        val closed = positionRepo.findClosedSince(since)

        if (closed.isEmpty()) {
            return ProfitForecast(
                asOf = LocalDateTime.now(),
                horizonDays = horizonDays,
                expectedReturnPercent = 0.0,
                expectedReturnAnnualPercent = 0.0,
                confidenceLowPercent = 0.0,
                confidenceHighPercent = 0.0,
                dailyMeanReturnPercent = 0.0,
                dailyVolatilityPercent = 0.0,
                tradesAnalyzed = 0,
                note = "Недостаточно данных: нет закрытых сделок за последние $lookbackDays дней",
            )
        }

        val base = capitalBase.max(BigDecimal.ONE)
        val dailyReturns =
            closed
                .groupBy { it.closedAt?.toLocalDate() }
                .filterKeys { it != null }
                .map { (_, trades) ->
                    val dayPnl = trades.sumOf { it.pnl ?: BigDecimal.ZERO }
                    dayPnl.toDouble() / base.toDouble() * 100.0
                }

        val mean = if (dailyReturns.isEmpty()) 0.0 else dailyReturns.average()
        val variance =
            if (dailyReturns.size > 1) {
                dailyReturns.sumOf { (it - mean).pow(2) } / (dailyReturns.size - 1)
            } else {
                0.0
            }
        val volatility = sqrt(variance)

        val expectedReturn = mean * horizonDays
        val expectedAnnual = mean * TRADING_DAYS_PER_YEAR
        val interval = 1.96 * volatility * sqrt(horizonDays.toDouble())

        logger.info {
            "Forecast: horizon=$horizonDays d, mean=$mean%/day, vol=$volatility%/day, " +
                "trades=${closed.size}, expected=$expectedReturn%"
        }
        return ProfitForecast(
            asOf = LocalDateTime.now(),
            horizonDays = horizonDays,
            expectedReturnPercent = round2(expectedReturn),
            expectedReturnAnnualPercent = round2(expectedAnnual),
            confidenceLowPercent = round2(expectedReturn - interval),
            confidenceHighPercent = round2(expectedReturn + interval),
            dailyMeanReturnPercent = round4(mean),
            dailyVolatilityPercent = round4(volatility),
            tradesAnalyzed = closed.size,
            note = "Оценка на реальных данных: ${closed.size} закрытых сделок за $lookbackDays дней",
        )
    }

    private fun round2(value: Double): Double = BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun round4(value: Double): Double = BigDecimal(value).setScale(4, RoundingMode.HALF_UP).toDouble()

    companion object {
        private const val TRADING_DAYS_PER_YEAR = 252
    }
}
