package com.trading.bot.service

import com.trading.bot.model.ClearingQuote
import com.trading.bot.model.InvestorTransactionType
import com.trading.bot.repository.InvestorRepository
import com.trading.bot.repository.PositionRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Клиринг с инвесторами.
 *
 * Робот самостоятельно ведёт расчёты с инвесторами: на основании собственной
 * статистики (реализованный P&L закрытых сделок) и прогноза доходности
 * ([ProfitForecastService]) рассчитывает сумму вывода на дату выхода.
 *
 * Методика (NAV-атрибуция):
 *  1. Доля инвестора = текущий баланс / суммарный внесённый капитал пула.
 *  2. Атрибутированный P&L = доля * реализованный P&L пула (реальные сделки).
 *  3. Прогнозная компонента = доля * внесённый капитал * ожидаемая доходность
 *     за оставшиеся до даты вывода дни (из статистики бота).
 *  4. Сумма вывода = баланс + атрибутированный P&L + прогнозная компонента.
 */
@Service
class ClearingService(
    private val investorRepo: InvestorRepository,
    private val positionRepo: PositionRepository,
    private val investorService: InvestorService,
    private val profitForecastService: ProfitForecastService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Расчёт суммы вывода инвестора на дату выхода.
     *
     * @param investorId UUIDv7 инвестора
     * @param requestedDate дата выхода (клиринг)
     */
    suspend fun calculateWithdrawal(
        investorId: UUID,
        requestedDate: LocalDateTime = LocalDateTime.now(),
    ): ClearingQuote {
        val investor =
            investorRepo.findInvestorById(investorId)
                ?: throw IllegalArgumentException("Investor not found: $investorId")
        val account =
            investorRepo.findAccountByInvestor(investorId)
                ?: throw IllegalArgumentException("Account not found for investor: $investorId")

        val poolContributed = investorService.poolContributed()
        val poolRealized = investorService.poolRealizedPnL()
        val poolEquity = poolContributed.add(poolRealized)

        val share =
            if (poolContributed > BigDecimal.ZERO) {
                account.balance.divide(poolContributed, 8, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
        val attributedPnL =
            poolRealized.multiply(share).setScale(2, RoundingMode.HALF_UP)

        val daysUntil = ChronoUnit.DAYS.between(LocalDateTime.now().toLocalDate(), requestedDate.toLocalDate())
        val forecastComponent =
            if (daysUntil > 0) {
                val horizon = daysUntil.toInt().coerceAtMost(365)
                val forecast = profitForecastService.forecast(horizonDays = horizon, capitalBase = poolContributed)
                val expectedReturnPct = forecast.expectedReturnPercent / 100.0
                account.balance.multiply(BigDecimal.valueOf(expectedReturnPct)).setScale(2, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }

        val total =
            account.balance
                .add(attributedPnL)
                .add(forecastComponent)
                .max(BigDecimal.ZERO)

        return ClearingQuote(
            investorId = investor.id,
            investorName = investor.name,
            requestedDate = requestedDate,
            sharesAtTime = share.setScale(6, RoundingMode.HALF_UP),
            poolEquity = poolEquity.setScale(2, RoundingMode.HALF_UP),
            poolContributed = poolContributed.setScale(2, RoundingMode.HALF_UP),
            poolRealizedPnL = poolRealized.setScale(2, RoundingMode.HALF_UP),
            attributedPnL = attributedPnL,
            forecastComponent = forecastComponent,
            estimatedWithdrawalAmount = total.setScale(2, RoundingMode.HALF_UP),
            breakdown =
                mapOf(
                    "balance" to account.balance.toPlainString(),
                    "attributedPnL" to attributedPnL.toPlainString(),
                    "forecastComponent" to forecastComponent.toPlainString(),
                    "share" to share.toPlainString(),
                    "daysUntil" to daysUntil.toString(),
                ),
        )
    }

    /**
     * Исполнение клиринга: списание рассчитанной суммы со счёта инвестора
     * с фиксацией транзакции CLEARING и снимка доли/equity.
     */
    suspend fun settleWithdrawal(
        investorId: UUID,
        requestedDate: LocalDateTime = LocalDateTime.now(),
    ): ClearingQuote {
        val quote = calculateWithdrawal(investorId, requestedDate)
        val account =
            investorRepo.findAccountByInvestor(investorId)
                ?: throw IllegalArgumentException("Account not found for investor: $investorId")

        val amount = quote.estimatedWithdrawalAmount
        if (amount > BigDecimal.ZERO) {
            val updated =
                account.copy(
                    balance = account.balance.subtract(amount).max(BigDecimal.ZERO),
                    totalWithdrawn = account.totalWithdrawn.add(amount),
                )
            investorRepo.saveAccount(updated)
            investorRepo.saveTransaction(
                com.trading.bot.model.InvestorTransaction(
                    investorId = investorId,
                    accountId = account.id,
                    type = InvestorTransactionType.CLEARING.name,
                    amount = amount,
                    sharesAtTime = quote.sharesAtTime,
                    equityAtTime = quote.poolEquity,
                    description = "Клиринг на дату ${requestedDate.toLocalDate()}: баланс + атрибутированный P&L + прогноз",
                ),
            )
        }
        logger.info { "Clearing settled for $investorId: $amount ₽ on ${requestedDate.toLocalDate()}" }
        return quote
    }

    /**
     * Суммарный реализованный P&L пула (для UI).
     */
    suspend fun poolStats(): Map<String, Any> {
        val contributed = investorService.poolContributed()
        val realized = investorService.poolRealizedPnL()
        return mapOf(
            "poolContributed" to contributed,
            "poolRealizedPnL" to realized,
            "poolEquity" to contributed.add(realized),
            "openPositions" to positionRepo.findByStatus(com.trading.bot.model.PositionStatus.OPEN).size,
        )
    }
}
