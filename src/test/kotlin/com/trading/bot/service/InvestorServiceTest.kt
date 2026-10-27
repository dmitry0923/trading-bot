package com.trading.bot.service

import com.trading.bot.model.entity.Investor
import com.trading.bot.model.entity.InvestorAccount
import com.trading.bot.repository.InvestorRepository
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Движение средств инвестора — атомарное, без read-then-write.
 *
 * Раньше `saveAccount` перезаписывал баланс снимком (параллельные ввод/вывод
 * теряли обновления), а `max(0, …)` прощал нехватку: транзакция фиксировала
 * полный вывод, деньги не списывались.
 */
class InvestorServiceTest {
    private val investorRepo = Mockito.mock(InvestorRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val clock = Clock.system(ZoneId.of("Europe/Moscow"))

    /**
     * Passthrough-мок транзакционного оператора: в unit-тесте нет БД, поэтому
     * `transactional(mono)` возвращает тот же Mono (атомарность проверяется
     * интеграционным тестом против Postgres).
     */
    private val transactionalOperator =
        Mockito.mock(TransactionalOperator::class.java).also { op ->
            Mockito
                .`when`(op.transactional(anyMono()))
                .thenAnswer { invocation -> invocation.getArgument<Mono<Unit>>(0) }
        }

    private val service =
        InvestorService(
            investorRepo = investorRepo,
            positionRepo = positionRepo,
            transactionalOperator = transactionalOperator,
            clock = clock,
        )

    private fun anyMono(): Mono<Unit> {
        Mockito.any(Mono::class.java)
        return Mono.empty()
    }

    @Test
    fun `withdrawal rejected when atomic debit cannot reserve funds`(): Unit =
        runBlocking {
            val investor = Investor(name = "Тест")
            val account = InvestorAccount(investorId = investor.id, balance = BigDecimal("1000"))
            Mockito.`when`(investorRepo.findInvestorById(investor.id)).thenReturn(investor)
            Mockito.`when`(investorRepo.findAccountByInvestor(investor.id)).thenReturn(account)
            Mockito
                .`when`(
                    investorRepo.debitBalanceIfSufficient(
                        anyAccountId(),
                        anyAmount(),
                        anyLocalDateTime(),
                        anyAmount(),
                    ),
                ).thenAnswer { null }

            val ex =
                assertThrows<IllegalArgumentException> {
                    runBlocking { service.withdraw(investor.id, BigDecimal("500")) }
                }

            assertEquals("Insufficient balance: 1000 < 500", ex.message)
            Mockito.verify(investorRepo, Mockito.never()).saveTransaction(anyTransaction())
            Mockito.verify(investorRepo, Mockito.never()).saveAccount(anyAccount())
        }

    @Test
    fun `deposit increments the balance atomically instead of overwriting the account`(): Unit =
        runBlocking {
            val investor = Investor(name = "Тест")
            val account = InvestorAccount(investorId = investor.id, balance = BigDecimal("100"))
            Mockito.`when`(investorRepo.findInvestorById(investor.id)).thenReturn(investor)
            Mockito
                .`when`(investorRepo.findAccountByInvestor(investor.id))
                .thenReturn(account, account.copy(balance = BigDecimal("150")))
            Mockito
                .`when`(
                    investorRepo.creditDeposit(
                        anyAccountId(),
                        anyAmount(),
                        anyLocalDateTime(),
                    ),
                ).thenReturn(BigDecimal("150"))
            Mockito.`when`(investorRepo.findAllAccounts()).thenReturn(listOf(account))
            Mockito.`when`(positionRepo.findClosedSince(anyLocalDateTime())).thenReturn(emptyList())

            service.deposit(investor.id, BigDecimal("50"))

            Mockito.verify(investorRepo).creditDeposit(anyAccountId(), anyAmount(), anyLocalDateTime())
            Mockito.verify(investorRepo, Mockito.never()).saveAccount(anyAccount())
            Mockito.verify(investorRepo).saveTransaction(anyTransaction())
        }

    @Test
    fun `withdrawal failure relies on transaction rollback instead of manual compensation`(): Unit =
        runBlocking {
            // Атомарность обеспечивает транзакция (баланс + проводка): ручная
            // компенсация в catch не переживала падение процесса (скан-10 P3),
            // поэтому её больше нет — проверяем, что деньги не двигаются повторно.
            val investor = Investor(name = "Тест")
            val account = InvestorAccount(investorId = investor.id, balance = BigDecimal("1000"))
            Mockito.`when`(investorRepo.findInvestorById(investor.id)).thenReturn(investor)
            Mockito.`when`(investorRepo.findAccountByInvestor(investor.id)).thenReturn(account)
            Mockito
                .`when`(
                    investorRepo.debitBalanceIfSufficient(
                        anyAccountId(),
                        anyAmount(),
                        anyLocalDateTime(),
                        anyAmount(),
                    ),
                ).thenReturn(BigDecimal("500"))
            Mockito
                .`when`(investorRepo.saveTransaction(anyTransaction()))
                .thenThrow(IllegalStateException("ledger down"))

            assertThrows<IllegalStateException> {
                runBlocking { service.withdraw(investor.id, BigDecimal("500")) }
            }

            Mockito.verify(investorRepo, Mockito.never()).reverseBalance(anyAccountId(), anyAmount(), anyLocalDateTime())
            Mockito.verify(investorRepo, Mockito.never()).creditDeposit(anyAccountId(), anyAmount(), anyLocalDateTime())
        }

    @Test
    fun `deposit failure relies on transaction rollback instead of manual compensation`(): Unit =
        runBlocking {
            val investor = Investor(name = "Тест")
            val account = InvestorAccount(investorId = investor.id, balance = BigDecimal("100"))
            Mockito.`when`(investorRepo.findInvestorById(investor.id)).thenReturn(investor)
            Mockito.`when`(investorRepo.findAccountByInvestor(investor.id)).thenReturn(account)
            Mockito
                .`when`(
                    investorRepo.creditDeposit(
                        anyAccountId(),
                        anyAmount(),
                        anyLocalDateTime(),
                    ),
                ).thenReturn(BigDecimal("150"))
            Mockito
                .`when`(investorRepo.saveTransaction(anyTransaction()))
                .thenThrow(IllegalStateException("ledger down"))

            assertThrows<IllegalStateException> {
                runBlocking { service.deposit(investor.id, BigDecimal("50")) }
            }

            // Зачисление и проводка откатываются транзакцией: никаких обратных движений.
            Mockito.verify(investorRepo, Mockito.never()).reverseBalance(anyAccountId(), anyAmount(), anyLocalDateTime())
            Mockito
                .verify(investorRepo, Mockito.never())
                .debitBalanceIfSufficient(anyAccountId(), anyAmount(), anyLocalDateTime(), anyAmount())
        }

    private fun anyAccountId(): UUID {
        Mockito.any(UUID::class.java)
        return UUID.fromString("00000000-0000-0000-0000-000000000001")
    }

    private fun anyAmount(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyLocalDateTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return LocalDateTime.of(2026, 1, 1, 0, 0)
    }

    private fun anyAccount(): InvestorAccount {
        Mockito.any(InvestorAccount::class.java)
        return InvestorAccount(investorId = UUID.fromString("00000000-0000-0000-0000-000000000001"))
    }

    private fun anyTransaction(): com.trading.bot.model.entity.InvestorTransaction {
        Mockito.any(com.trading.bot.model.entity.InvestorTransaction::class.java)
        return com.trading.bot.model.entity.InvestorTransaction(
            investorId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            accountId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            type = "WITHDRAWAL",
            amount = BigDecimal.ZERO,
        )
    }
}
