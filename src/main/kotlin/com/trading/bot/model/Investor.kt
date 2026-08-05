package com.trading.bot.model

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Инвестор робота: пассивный участник, вносящий капитал.
 * Статистика бота (закрытые сделки) используется для расчёта доли и прогноза доходности.
 */
data class Investor(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val email: String? = null,
    val status: String = "ACTIVE",
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

enum class InvestorTransactionType {
    DEPOSIT,
    WITHDRAWAL,
    CLEARING,
    FEE,
}

data class InvestorAccount(
    val id: UUID = UUID.randomUUID(),
    val investorId: UUID,
    val currency: String = "RUB",
    val balance: BigDecimal = BigDecimal.ZERO,
    val totalDeposited: BigDecimal = BigDecimal.ZERO,
    val totalWithdrawn: BigDecimal = BigDecimal.ZERO,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)

data class InvestorTransaction(
    val id: UUID = UUID.randomUUID(),
    val investorId: UUID,
    val accountId: UUID,
    val type: String,
    val amount: BigDecimal,
    val currency: String = "RUB",
    val sharesAtTime: BigDecimal? = null,
    val equityAtTime: BigDecimal? = null,
    val description: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

data class InvestorAllocation(
    val id: UUID = UUID.randomUUID(),
    val investorId: UUID,
    val accountId: UUID,
    val amount: BigDecimal,
    val allocatedAt: LocalDateTime = LocalDateTime.now(),
)

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
