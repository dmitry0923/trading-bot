package com.trading.bot.service

import com.trading.bot.model.dto.ProfitForecast
import com.trading.bot.model.entity.Investor
import com.trading.bot.model.entity.InvestorAccount
import com.trading.bot.model.entity.InvestorTransaction
import com.trading.bot.repository.InvestorRepository
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Клиринг: идемпотентность (claim дня) и отсутствие «прощённой» нехватки средств.
 *
 * Раньше идемпотентность была read-then-write (два параллельных settle проходили
 * проверку и списывали дважды), а `max(0, …)` тихо обнулял баланс: леджер
 * фиксировал полный вывод, которого не было.
 */
class ClearingServiceTest {
    private val investorRepo = Mockito.mock(InvestorRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val investorService = Mockito.mock(InvestorService::class.java)
    private val profitForecastService = Mockito.mock(ProfitForecastService::class.java)
    private val clock = Clock.system(ZoneId.of("Europe/Moscow"))
    private val service =
        ClearingService(investorRepo, positionRepo, investorService, profitForecastService, clock)

    private val investor = Investor(name = "Тест")
    private val account = InvestorAccount(investorId = investor.id, balance = BigDecimal("1000"))

    private fun stubInvestor() {
        runBlocking {
            Mockito.`when`(investorRepo.findInvestorById(investor.id)).thenReturn(investor)
            Mockito.`when`(investorRepo.findAccountByInvestor(investor.id)).thenReturn(account)
            Mockito.`when`(investorService.poolContributed()).thenReturn(BigDecimal("1000"))
            Mockito.`when`(investorService.poolRealizedPnL()).thenReturn(BigDecimal.ZERO)
        }
    }

    @Test
    fun `already settled business day does not debit the account again`(): Unit =
        runBlocking {
            stubInvestor()
            Mockito
                .`when`(investorRepo.saveClearingTransaction(anyTransaction(), anyBusinessDate()))
                .thenReturn(false)

            val quote = service.settleWithdrawal(investor.id, LocalDateTime.now(clock))

            // День уже проведён: claim проигран, списания нет.
            Mockito
                .verify(investorRepo, Mockito.never())
                .debitBalanceIfSufficient(anyAccountId(), anyAmount(), anyLocalDateTime(), anyAmount())
            Mockito.verify(investorRepo, Mockito.never()).deleteTransaction(anyAccountId())
            Mockito.verify(investorRepo, Mockito.never()).saveTransaction(anyTransaction())
            assert(quote.estimatedWithdrawalAmount >= BigDecimal.ZERO)
        }

    @Test
    fun `clearing whose payout is not fundable is rejected without silent clamp`(): Unit =
        runBlocking {
            stubInvestor()
            Mockito
                .`when`(investorRepo.saveClearingTransaction(anyTransaction(), anyBusinessDate()))
                .thenReturn(true)
            // Средств не хватает (спекулятивная прогнозная часть): списание не выполнено.
            Mockito
                .`when`(
                    investorRepo.debitBalanceIfSufficient(
                        anyAccountId(),
                        anyAmount(),
                        anyLocalDateTime(),
                        anyAmount(),
                    ),
                ).thenAnswer { null }
            Mockito
                .`when`(profitForecastService.forecast(Mockito.anyInt(), anyAmount(), Mockito.anyInt()))
                .thenReturn(forecast(expectedReturnPercent = 10.0))

            assertThrows<IllegalArgumentException> {
                runBlocking { service.settleWithdrawal(investor.id, LocalDateTime.now(clock).plusDays(5)) }
            }
            // Claim компенсирован: транзакция не остаётся в леджере без списания.
            Mockito.verify(investorRepo).deleteTransaction(anyAccountId())
        }

    private fun forecast(expectedReturnPercent: Double): ProfitForecast =
        ProfitForecast(
            asOf = LocalDateTime.now(clock),
            horizonDays = 5,
            expectedReturnPercent = expectedReturnPercent,
            expectedReturnAnnualPercent = expectedReturnPercent,
            confidenceLowPercent = 0.0,
            confidenceHighPercent = expectedReturnPercent,
            dailyMeanReturnPercent = 0.0,
            dailyVolatilityPercent = 0.0,
            tradesAnalyzed = 0,
            note = "test",
        )

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

    private fun anyBusinessDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return LocalDate.of(2026, 1, 1)
    }

    private fun anyTransaction(): InvestorTransaction {
        Mockito.any(InvestorTransaction::class.java)
        return InvestorTransaction(
            investorId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            accountId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            type = "CLEARING",
            amount = BigDecimal.ZERO,
        )
    }
}
