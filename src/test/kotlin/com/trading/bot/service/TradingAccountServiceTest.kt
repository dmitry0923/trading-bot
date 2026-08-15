package com.trading.bot.service

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.entity.TradingAccount
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.TradingAccountRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import java.math.BigDecimal

/**
 * Unit-тесты [TradingAccountService]: legacy-режим (пустая таблица), весовой
 * round-robin выбора аккаунта, резолв портфеля и персональных лимитов.
 */
class TradingAccountServiceTest {
    private val repository = Mockito.mock(TradingAccountRepository::class.java)
    private val positionRepository = Mockito.mock(PositionRepository::class.java)
    private val alorConfig = AlorConfig().apply { portfolio = "D12345" }
    private val riskConfig = RiskConfig().apply { maxOpenPositions = 10 }

    private val service =
        TradingAccountService(
            repository,
            positionRepository,
            alorConfig,
            riskConfig,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(repository, positionRepository)
        runBlocking {
            Mockito.`when`(repository.findEnabled()).thenReturn(emptyList())
        }
    }

    @Test
    fun `legacy mode when table is empty - selectAccount returns null and portfolioOf returns config`() {
        runBlocking {
            assertNull(service.selectAccount())
            assertEquals("D12345", service.portfolioOf(null))
            assertEquals("D12345", service.portfolioOf(42L))
        }
    }

    @Test
    fun `selectAccount skips full accounts and round-robins weighted candidates`() {
        val a = account(1L, "A", "PA", weight = 2)
        val b = account(2L, "B", "PB", weight = 1)
        val c = account(3L, "C", "PC", weight = 1)
        runBlocking {
            Mockito.`when`(repository.findEnabled()).thenReturn(listOf(a, b, c))
            Mockito.`when`(positionRepository.findOpenCountByAccount(1L)).thenReturn(10) // full
            Mockito.`when`(positionRepository.findOpenCountByAccount(2L)).thenReturn(0)
            Mockito.`when`(positionRepository.findOpenCountByAccount(3L)).thenReturn(0)
        }

        runBlocking {
            val picks = (0 until 8).map { service.selectAccount() }
            assertTrue(picks.all { it == 2L || it == 3L }, "full account 1 must never be selected: $picks")
            val bCount = picks.count { it == 2L }
            val cCount = picks.count { it == 3L }
            assertEquals(4, bCount, "weight 2 should get ~2x of weight 1: $picks")
            assertEquals(4, cCount, "weight 1 gets the rest: $picks")
        }
    }

    @Test
    fun `portfolioOf resolves account portfolio and falls back to config for unknown`() {
        runBlocking {
            Mockito.`when`(repository.findById(7L)).thenReturn(account(7L, "Seven", "P7"))
            assertEquals("P7", service.portfolioOf(7L))
            assertEquals("D12345", service.portfolioOf(999L))
            assertEquals("D12345", service.portfolioOf(null))
        }
    }

    @Test
    fun `aumRubOverrideFor and maxDailyLossRubFor use account overrides or null for legacy`() {
        runBlocking {
            Mockito
                .`when`(repository.findById(7L))
                .thenReturn(account(7L, "Seven", "P7", aumRub = BigDecimal("500000"), maxDailyLossRub = BigDecimal("10000")))
            assertEquals(BigDecimal("500000"), service.aumRubOverrideFor(7L))
            assertEquals(BigDecimal("10000"), service.maxDailyLossRubFor(7L))
            assertNull(service.aumRubOverrideFor(null))
            assertNull(service.maxDailyLossRubFor(999L))
        }
    }

    @Test
    fun `create saves account and returns with generated id`() {
        runBlocking {
            Mockito
                .`when`(repository.save(any()))
                .thenAnswer { inv -> inv.getArgument<TradingAccount>(0).copy(id = 1L) }
        }

        val created =
            runBlocking {
                service.create(
                    name = "New",
                    alorPortfolio = "P1",
                    weight = 3,
                )
            }
        assertEquals(1L, created.id)
        assertEquals(3, created.weight)
        runBlocking { verify(repository).save(any()) }
    }

    @Test
    fun `delete removes existing account`() {
        runBlocking {
            Mockito.`when`(repository.findById(1L)).thenReturn(account(1L, "A", "PA"))
            assertTrue(service.delete(1L))
            Mockito.verify(repository).deleteById(1L)
            assertTrue(!service.delete(99L))
        }
    }

    @Test
    fun `cachedMaxDailyLossRubFor reads from enabled cache`() {
        runBlocking {
            Mockito
                .`when`(repository.findEnabled())
                .thenReturn(listOf(account(1L, "A", "PA", maxDailyLossRub = BigDecimal("5000"))))
            // первый вызов наполняет кэш
            service.findEnabled()
        }
        assertEquals(BigDecimal("5000"), service.cachedMaxDailyLossRubFor(1L))
        assertNull(service.cachedMaxDailyLossRubFor(null))
        assertNull(service.cachedMaxDailyLossRubFor(999L))
    }

    @Test
    fun `hasEnabledAccounts distinguishes legacy empty table from configured accounts`() {
        runBlocking {
            assertFalse(
                TradingAccountService(repository, positionRepository, alorConfig, riskConfig)
                    .hasEnabledAccounts(),
            )
        }
        runBlocking {
            Mockito.`when`(repository.findEnabled()).thenReturn(listOf(account(1L, "A", "PA")))
            assertTrue(
                TradingAccountService(repository, positionRepository, alorConfig, riskConfig)
                    .hasEnabledAccounts(),
            )
        }
    }

    private fun account(
        id: Long,
        name: String,
        alorPortfolio: String,
        weight: Int = 1,
        aumRub: BigDecimal? = null,
        maxDailyLossRub: BigDecimal? = null,
    ): TradingAccount =
        TradingAccount(
            id = id,
            name = name,
            alorPortfolio = alorPortfolio,
            weight = weight,
            aumRub = aumRub,
            maxDailyLossRub = maxDailyLossRub,
        )
}
