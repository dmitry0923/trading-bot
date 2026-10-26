package com.trading.bot.service

import com.trading.bot.infrastructure.UuidV7
import com.trading.bot.model.dto.ClearingQuote
import com.trading.bot.model.entity.InvestorTransaction
import com.trading.bot.model.entity.InvestorTransactionType
import com.trading.bot.repository.InvestorRepository
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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
    /** Бизнес-время (МСК): дата клиринга и горизонт прогноза. */
    private val clock: Clock = Clock.system(BUSINESS_ZONE),
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
        requestedDate: LocalDateTime = LocalDateTime.now(clock),
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

        val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(clock), requestedDate.toLocalDate())
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
     *
     * Идемпотентна на уровне БД: уникальный индекс
     * `(investor_id, type, business_date)` для CLEARING (миграция 042) —
     * из двух параллельных вызовов за один бизнес-день (МСК) средства
     * списывает ровно один. Списание атомарное и условное
     * (`balance + реализованный P&L >= amount`): нехватка средств — отказ,
     * а не `max(0, …)`.
     */
    suspend fun settleWithdrawal(
        investorId: UUID,
        requestedDate: LocalDateTime = LocalDateTime.now(clock),
    ): ClearingQuote {
        val businessDate = requestedDate.toLocalDate()
        // Клиринг проводится только за сегодняшний бизнес-день (МСК): иначе бэкдейт
        // или будущая дата создают второй ключ идемпотентности и второе списание
        // (скан-2 P2). Дата из query не должна обходить уникальный индекс.
        val today = LocalDate.now(clock)
        require(businessDate == today) {
            "Clearing settle is allowed only for the current business day ($today), got $businessDate"
        }
        val quote = calculateWithdrawal(investorId, requestedDate)
        val account =
            investorRepo.findAccountByInvestor(investorId)
                ?: throw IllegalArgumentException("Account not found for investor: $investorId")

        // Выплата = капитал + реализованный P&L. Прогнозная компонента — только
        // информация в quote: включать её в списание нельзя (баланс уходил бы в минус
        // на величину прогноза, а предикат достаточности `balance + bookedProfit >= amount`
        // становился бы всегда ложным) — скан-2 P2.
        val amount = quote.estimatedWithdrawalAmount.subtract(quote.forecastComponent).max(BigDecimal.ZERO)
        if (amount <= BigDecimal.ZERO) {
            logger.info { "Clearing for $investorId on $businessDate: nothing to settle" }
            return quote
        }

        val transaction =
            InvestorTransaction(
                id = UuidV7.uuid(),
                investorId = investorId,
                accountId = account.id,
                type = InvestorTransactionType.CLEARING.name,
                amount = amount,
                sharesAtTime = quote.sharesAtTime,
                equityAtTime = quote.poolEquity,
                description = "Клиринг на дату $businessDate: баланс + атрибутированный P&L + прогноз",
            )
        // Claim дня: единственный победитель вставляет строку, остальные получают
        // конфликт по уникальному индексу и НЕ списывают средства.
        if (!investorRepo.saveClearingTransaction(transaction, businessDate)) {
            logger.info { "Clearing for $investorId on $businessDate already settled — skipped (idempotent)" }
            return quote
        }

        val newBalance =
            investorRepo.debitBalanceIfSufficient(
                accountId = account.id,
                amount = amount,
                now = LocalDateTime.now(clock),
                // Выплата = капитал + реализованный P&L: прибыль зачисляется на баланс
                // при выплате, иначе сумма всегда превышала бы баланс (прежний
                // `max(0, …)` просто прощал разницу).
                bookedProfit = quote.attributedPnL,
            )
        if (newBalance == null) {
            // Компенсация claim'а: транзакция не должна остаться в леджере без списания.
            investorRepo.deleteTransaction(transaction.id)
            throw IllegalArgumentException(
                "Insufficient balance for clearing $investorId: ${account.balance} < $amount",
            )
        }
        logger.info { "Clearing settled for $investorId: $amount ₽ on $businessDate (balance=$newBalance)" }
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

    private companion object {
        private val BUSINESS_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
