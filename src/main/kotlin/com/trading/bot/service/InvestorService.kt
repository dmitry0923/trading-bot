package com.trading.bot.service

import com.trading.bot.infrastructure.UuidV7
import com.trading.bot.model.dto.InvestorView
import com.trading.bot.model.entity.Investor
import com.trading.bot.model.entity.InvestorAccount
import com.trading.bot.model.entity.InvestorAllocation
import com.trading.bot.model.entity.InvestorTransaction
import com.trading.bot.model.entity.InvestorTransactionType
import com.trading.bot.repository.InvestorRepository
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Сервис управления инвесторами.
 *
 * - Регистрация инвестора с первичным взносом
 * - Ввод / вывод капитала (аллокация в пул бота)
 * - Доли: текущий баланс инвестора / суммарный внесённый капитал пула
 * - Атрибуция прибыли: доля инвестора * реализованный P&L пула (реальные сделки)
 *
 * Все идентификаторы — UUIDv7 ([com.trading.bot.infrastructure.UuidV7]).
 */
@Service
class InvestorService(
    private val investorRepo: InvestorRepository,
    private val positionRepo: PositionRepository,
    /** Оператор транзакций: деньги и проводка применяются атомарно (скан-10 P3). */
    private val transactionalOperator: TransactionalOperator,
    /** Бизнес-время (МСК): метки операций и дата делового дня. */
    private val clock: Clock = Clock.system(BUSINESS_ZONE),
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createInvestor(
        name: String,
        email: String? = null,
        initialDeposit: BigDecimal = BigDecimal.ZERO,
    ): InvestorView {
        val investorId = UuidV7.uuid()
        val investor =
            Investor(
                id = investorId,
                name = name,
                email = email,
            )
        val account =
            InvestorAccount(
                id = UuidV7.uuid(),
                investorId = investorId,
            )
        investorRepo.saveInvestor(investor)
        investorRepo.saveAccount(account)

        if (initialDeposit > BigDecimal.ZERO) {
            recordDeposit(investor, account, initialDeposit)
        }
        logger.info { "Investor created: $name (id=$investorId), deposit=$initialDeposit" }
        return viewOf(investor, requireAccount(investorId))
    }

    suspend fun deposit(
        investorId: UUID,
        amount: BigDecimal,
    ): InvestorView {
        require(amount > BigDecimal.ZERO) { "Deposit amount must be positive" }
        val investor = requireInvestor(investorId)
        val account = requireAccount(investorId)
        recordDeposit(investor, account, amount)
        return viewOf(investor, requireAccount(investorId))
    }

    suspend fun withdraw(
        investorId: UUID,
        amount: BigDecimal,
        description: String? = null,
    ): InvestorView {
        require(amount > BigDecimal.ZERO) { "Withdrawal amount must be positive" }
        val investor = requireInvestor(investorId)
        val account = requireAccount(investorId)

        // Атомарное условное списание и проводка — одна транзакция: прежняя
        // компенсация в catch не покрывала падение процесса (скан-10 P3).
        inTransaction {
            // Атомарное условное списание: проверка достаточности и списание — один
            // UPDATE. Read-then-write (`saveAccount` снимком) терял параллельные
            // операции, а `max(0, …)` прощал нехватку.
            investorRepo.debitBalanceIfSufficient(account.id, amount, LocalDateTime.now(clock))
                ?: throw IllegalArgumentException("Insufficient balance: ${account.balance} < $amount")
            recordTransaction(
                investorId = investorId,
                accountId = account.id,
                type = InvestorTransactionType.WITHDRAWAL,
                amount = amount,
                description = description ?: "Ручной вывод",
            )
        }
        logger.info { "Withdrawal for $investorId: $amount ₽" }
        return viewOf(investor, requireAccount(investorId))
    }

    suspend fun listInvestors(): List<InvestorView> {
        val investors = investorRepo.findAllInvestors()
        val accounts = investorRepo.findAllAccounts().associateBy { it.investorId }
        val poolContributed = poolContributed()
        val poolRealized = poolRealizedPnL()
        return investors.map { investor ->
            val account = accounts[investor.id]
            if (account == null) {
                InvestorView(investor, InvestorAccount(investorId = investor.id), BigDecimal.ZERO, 0.0)
            } else {
                viewOf(investor, account, poolContributed, poolRealized)
            }
        }
    }

    suspend fun getInvestor(investorId: UUID): InvestorView {
        val investor = requireInvestor(investorId)
        val account = requireAccount(investorId)
        return viewOf(investor, account)
    }

    suspend fun transactions(investorId: UUID): List<InvestorTransaction> = investorRepo.findTransactionsByInvestor(investorId)

    suspend fun allocations(): List<InvestorAllocation> = investorRepo.findAllAllocations()

    /**
     * Суммарный внесённый капитал пула (все инвесторы, без учёта вывода).
     */
    suspend fun poolContributed(): BigDecimal = investorRepo.findAllAccounts().sumOf { it.totalDeposited }

    /**
     * Реализованный P&L пула по реальным закрытым сделкам за всё время.
     */
    suspend fun poolRealizedPnL(): BigDecimal {
        val since = LocalDateTime.now().minusYears(10)
        return positionRepo.findClosedSince(since).sumOf { it.pnl ?: BigDecimal.ZERO }
    }

    private suspend fun recordDeposit(
        investor: Investor,
        account: InvestorAccount,
        amount: BigDecimal,
    ) {
        // Баланс, проводка и аллокация — одна транзакция: крах процесса между
        // ними больше не оставляет «деньги без записи» (скан-10 P3). Прежняя
        // try/catch-компенсация покрывала только исключение, не сбой процесса.
        inTransaction {
            // Атомарный инкремент: снимок `saveAccount` затирал параллельные операции.
            investorRepo.creditDeposit(account.id, amount, LocalDateTime.now(clock))
                ?: throw IllegalStateException("Account not found: ${account.id}")
            recordTransaction(
                investorId = investor.id,
                accountId = account.id,
                type = InvestorTransactionType.DEPOSIT,
                amount = amount,
                description = "Взнос ${investor.name}",
            )
            investorRepo.saveAllocation(
                InvestorAllocation(
                    id = UuidV7.uuid(),
                    investorId = investor.id,
                    accountId = account.id,
                    amount = amount,
                ),
            )
        }
    }

    /**
     * Выполняет блок в одной транзакции R2DBC.
     *
     * Контекст транзакции передаётся через Reactor-context, поэтому `mono { }`
     * (корутинный мост) исполняется внутри того же соединения: все записи
     * репозиториев коммитятся или откатываются вместе.
     */
    private suspend fun inTransaction(block: suspend () -> Unit) {
        transactionalOperator.transactional(mono<Unit> { block() }).awaitSingle()
    }

    private suspend fun recordTransaction(
        investorId: UUID,
        accountId: UUID,
        type: InvestorTransactionType,
        amount: BigDecimal,
        description: String?,
    ) {
        investorRepo.saveTransaction(
            InvestorTransaction(
                id = UuidV7.uuid(),
                investorId = investorId,
                accountId = accountId,
                type = type.name,
                amount = amount,
                description = description,
            ),
        )
    }

    private suspend fun viewOf(
        investor: Investor,
        account: InvestorAccount,
    ): InvestorView {
        val poolContributed = poolContributed()
        val poolRealized = poolRealizedPnL()
        return viewOf(investor, account, poolContributed, poolRealized)
    }

    private fun viewOf(
        investor: Investor,
        account: InvestorAccount,
        poolContributed: BigDecimal,
        poolRealized: BigDecimal,
    ): InvestorView {
        val attributed = attributed(account.balance, poolContributed, poolRealized)
        val invested = account.totalDeposited.max(BigDecimal.ONE)
        val totalReturn = attributed.divide(invested, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100))
        return InvestorView(
            investor = investor,
            account = account,
            realizedPnL = attributed,
            totalReturnPercent = totalReturn.toDouble(),
        )
    }

    private fun attributed(
        balance: BigDecimal,
        poolContributed: BigDecimal,
        poolRealized: BigDecimal,
    ): BigDecimal {
        if (poolContributed <= BigDecimal.ZERO) return BigDecimal.ZERO
        val share = balance.divide(poolContributed, 8, RoundingMode.HALF_UP)
        return poolRealized.multiply(share).setScale(2, RoundingMode.HALF_UP)
    }

    private suspend fun requireInvestor(investorId: UUID): Investor =
        investorRepo.findInvestorById(investorId)
            ?: throw IllegalArgumentException("Investor not found: $investorId")

    private suspend fun requireAccount(investorId: UUID): InvestorAccount =
        investorRepo.findAccountByInvestor(investorId)
            ?: throw IllegalArgumentException("Account not found for investor: $investorId")

    private companion object {
        private val BUSINESS_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
