package com.trading.bot.model.dto

import com.trading.bot.model.entity.Investor
import com.trading.bot.model.entity.InvestorAccount
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class InvestorView(
    val investor: Investor,
    val account: InvestorAccount,
    val realizedPnL: BigDecimal,
    val totalReturnPercent: Double,
)

/**
 * Прогноз прибыли для инвесторов на основе реальной статистики закрытых сделок.
 */
data class ProfitForecast(
    val asOf: LocalDateTime,
    val horizonDays: Int,
    val expectedReturnPercent: Double,
    val expectedReturnAnnualPercent: Double,
    val confidenceLowPercent: Double,
    val confidenceHighPercent: Double,
    val dailyMeanReturnPercent: Double,
    val dailyVolatilityPercent: Double,
    val tradesAnalyzed: Int,
    val note: String,
)

/**
 * Расчёт клиринга: сколько инвестор может вывести на дату выхода.
 */
data class ClearingQuote(
    val investorId: UUID,
    val investorName: String,
    val requestedDate: LocalDateTime,
    val sharesAtTime: BigDecimal,
    val poolEquity: BigDecimal,
    val poolContributed: BigDecimal,
    val poolRealizedPnL: BigDecimal,
    val attributedPnL: BigDecimal,
    val forecastComponent: BigDecimal,
    val estimatedWithdrawalAmount: BigDecimal,
    val breakdown: Map<String, String>,
)
