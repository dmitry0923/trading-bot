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
    private val service = InvestorService(investorRepo, positionRepo, clock)

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
    fun `withdrawal restores the balance when the ledger write fails`(): Unit =
        runBlocking {
            // Баланс уже списан: сбой записи в леджер не должен «съедать» деньги
            // инвестора без проводки (скан-4 P2).
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

            Mockito.verify(investorRepo).reverseBalance(anyAccountId(), anyAmount(), anyLocalDateTime())
            // Откат не должен двигать кумулятивную статистику ввода/вывода.
            Mockito.verify(investorRepo, Mockito.never()).creditDeposit(anyAccountId(), anyAmount(), anyLocalDateTime())
        }

    @Test
    fun `deposit reverses the credit when the ledger write fails`(): Unit =
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

            // Компенсация: зачисление откатывается обратным движением баланса.
            Mockito.verify(investorRepo).reverseBalance(anyAccountId(), anyAmount(), anyLocalDateTime())
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
