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
import java.math.BigDecimal

/**
 * Ветви [TradingAccountService], не покрытые основным тестом (скан-19: 51 из 106):
 * синхронные cached-геттеры (до и после прогрева кэша), TTL-кэш включённых аккаунтов,
 * fallback персональных лимитов на глобальные, пропуск аккаунтов без id, сброс
 * round-robin при смене набора кандидатов, валидация уникальности портфеля Alor
 * (create/update/self) и частичное обновление полей.
 */
class TradingAccountServiceBranchesTest {
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
            Mockito.`when`(repository.findAll()).thenReturn(emptyList())
        }
    }

    private fun account(
        id: Long?,
        name: String = "acc-$id",
        portfolio: String = "P$id",
        weight: Int = 1,
        aumRub: BigDecimal? = null,
        maxOpenPositions: Int? = null,
        maxDailyLossRub: BigDecimal? = null,
        enabled: Boolean = true,
    ): TradingAccount =
        TradingAccount(
            id = id,
            name = name,
            alorPortfolio = portfolio,
            weight = weight,
            aumRub = aumRub,
            maxOpenPositions = maxOpenPositions,
            maxDailyLossRub = maxDailyLossRub,
            enabled = enabled,
        )

    // ------------------------------------------------------------ cache getters

    @Test
    fun `cached getters are null before the cache is warmed and for a null id`() {
        assertNull(service.cachedAumRubFor(1L))
        assertNull(service.cachedPortfolioFor(1L))
        assertNull(service.cachedMaxOpenPositionsFor(1L))
        assertNull(service.cachedMaxDailyLossRubFor(1L))

        assertNull(service.cachedAumRubFor(null))
        assertNull(service.cachedPortfolioFor(null))
        assertNull(service.cachedMaxOpenPositionsFor(null))
        assertNull(service.cachedMaxDailyLossRubFor(null))
    }

    @Test
    fun `cached getters read the warmed enabled cache by account id`() {
        val rich =
            account(
                id = 1L,
                portfolio = "PRICH",
                aumRub = BigDecimal("50000"),
                maxOpenPositions = 3,
                maxDailyLossRub = BigDecimal("1000"),
            )
        runBlocking {
            Mockito.`when`(repository.findEnabled()).thenReturn(listOf(rich))
            service.findEnabled()
        }

        assertEquals(BigDecimal("50000"), service.cachedAumRubFor(1L))
        assertEquals("PRICH", service.cachedPortfolioFor(1L))
        assertEquals(3, service.cachedMaxOpenPositionsFor(1L))
        assertEquals(BigDecimal("1000"), service.cachedMaxDailyLossRubFor(1L))

        assertNull(service.cachedAumRubFor(99L), "неизвестный id — нет данных")
        assertNull(service.cachedPortfolioFor(99L))
        assertNull(service.cachedMaxOpenPositionsFor(99L))
        assertNull(service.cachedMaxDailyLossRubFor(99L))
    }

    @Test
    fun `enabled cache is reused within the ttl`() {
        runBlocking {
            Mockito.`when`(repository.findEnabled()).thenReturn(listOf(account(1L)))
            service.findEnabled()
            service.findEnabled()
            Mockito.verify(repository, Mockito.times(1)).findEnabled()
        }
    }

    @Test
    fun `crud operations invalidate the enabled cache`() {
        runBlocking {
            Mockito.`when`(repository.findEnabled()).thenReturn(listOf(account(1L)))
            service.findEnabled()
            Mockito.`when`(repository.save(org.mockito.kotlin.any())).thenAnswer { it.getArgument<TradingAccount>(0) }
            service.create(name = "new", alorPortfolio = "PNEW")
            service.findEnabled()
            Mockito.verify(repository, Mockito.times(2)).findEnabled()
        }
    }

    // --------------------------------------------------------- per-account limits

    @Test
    fun `max open positions falls back to the global limit`() {
        runBlocking {
            Mockito.`when`(repository.findById(1L)).thenReturn(account(1L, maxOpenPositions = 4))
            Mockito.`when`(repository.findById(2L)).thenReturn(account(2L, maxOpenPositions = null))

            assertEquals(4, service.maxOpenPositionsFor(1L))
            assertEquals(10, service.maxOpenPositionsFor(2L), "персональный лимит не задан — глобальный")
            assertEquals(10, service.maxOpenPositionsFor(null), "legacy-режим")
        }
    }

    @Test
    fun `aum and daily loss overrides are null for a null account`() {
        runBlocking {
            Mockito
                .`when`(repository.findById(1L))
                .thenReturn(account(1L, aumRub = BigDecimal("70000"), maxDailyLossRub = BigDecimal("2000")))

            assertEquals(BigDecimal("70000"), service.aumRubOverrideFor(1L))
            assertEquals(BigDecimal("2000"), service.maxDailyLossRubFor(1L))
            assertNull(service.aumRubOverrideFor(null))
            assertNull(service.maxDailyLossRubFor(null))
        }
    }

    // -------------------------------------------------------------- selectAccount

    @Test
    fun `selectAccount skips accounts without an id and returns the single candidate`() {
        runBlocking {
            Mockito.`when`(repository.findEnabled()).thenReturn(listOf(account(null), account(5L)))
            Mockito.`when`(positionRepository.findOpenCountByAccount(5L)).thenReturn(0)

            assertEquals(5L, service.selectAccount(), "аккаунт без id не участвует в выборе")
        }
    }

    @Test
    fun `selectAccount restarts the round robin when the candidate set changes`() {
        val a = account(1L)
        val b = account(2L)
        runBlocking {
            Mockito.`when`(repository.findEnabled()).thenReturn(listOf(a, b))
            Mockito.`when`(positionRepository.findOpenCountByAccount(1L)).thenReturn(0)
            // Сначала второй аккаунт переполнен — кандидат один.
            Mockito.`when`(positionRepository.findOpenCountByAccount(2L)).thenReturn(10)
            assertEquals(1L, service.selectAccount())

            // Аккаунт освободился: состав кандидатов изменился → счётчик сбрасывается.
            Mockito.`when`(positionRepository.findOpenCountByAccount(2L)).thenReturn(0)
            val picks = (0 until 4).map { service.selectAccount() }
            assertEquals(listOf(1L, 2L, 1L, 2L), picks)
        }
    }

    @Test
    fun `selectAccount treats non positive weights as weight one`() {
        runBlocking {
            Mockito.`when`(repository.findEnabled()).thenReturn(listOf(account(1L, weight = 0), account(2L, weight = 0)))
            Mockito.`when`(positionRepository.findOpenCountByAccount(1L)).thenReturn(0)
            Mockito.`when`(positionRepository.findOpenCountByAccount(2L)).thenReturn(0)

            val picks = (0 until 4).map { service.selectAccount() }
            assertEquals(listOf(1L, 2L, 1L, 2L), picks, "вес 0 не должен делить на ноль")
        }
    }

    // --------------------------------------------------------------------- create

    @Test
    fun `create rejects a blank portfolio`() {
        val failure = runBlocking { runCatching { service.create(name = "x", alorPortfolio = "   ") }.exceptionOrNull() }

        assertTrue(failure is IllegalArgumentException, "пустой портфель запрещён: $failure")
    }

    @Test
    fun `create rejects a portfolio already used by another account`() {
        runBlocking {
            Mockito.`when`(repository.findAll()).thenReturn(listOf(account(1L, portfolio = "PA")))
        }

        val failure =
            runBlocking {
                runCatching { service.create(name = "dup", alorPortfolio = " pa ") }.exceptionOrNull()
            }

        assertTrue(failure is IllegalArgumentException, "портфель сравнивается после trim/uppercase: $failure")
    }

    // --------------------------------------------------------------------- update

    @Test
    fun `update returns null for an unknown account`() {
        assertNull(runBlocking { service.update(99L, name = "ghost") })
    }

    @Test
    fun `update rejects a portfolio owned by another account but accepts its own`() {
        runBlocking {
            Mockito.`when`(repository.findById(1L)).thenReturn(account(1L, portfolio = "PA"))
            Mockito.`when`(repository.findAll()).thenReturn(listOf(account(1L, portfolio = "PA"), account(2L, portfolio = "PB")))
            Mockito.`when`(repository.save(org.mockito.kotlin.any())).thenAnswer { it.getArgument<TradingAccount>(0) }

            // Свой же портфель (в другом регистре) — не конфликт (значение сохраняется как передано).
            assertEquals(" pa ", service.update(1L, alorPortfolio = " pa ")?.alorPortfolio)

            val failure = runCatching { service.update(1L, alorPortfolio = "pb") }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException, "портфель занят другим аккаунтом: $failure")
        }
    }

    @Test
    fun `update keeps untouched fields and replaces overrides`() {
        runBlocking {
            val existing =
                account(
                    id = 1L,
                    name = "old",
                    portfolio = "PA",
                    weight = 3,
                    aumRub = BigDecimal("100"),
                    maxOpenPositions = 2,
                    maxDailyLossRub = BigDecimal("500"),
                )
            Mockito.`when`(repository.findById(1L)).thenReturn(existing)
            Mockito.`when`(repository.findAll()).thenReturn(listOf(existing))
            Mockito.`when`(repository.save(org.mockito.kotlin.any())).thenAnswer { it.getArgument<TradingAccount>(0) }

            val renamed = service.update(1L, name = "new") ?: error("update must return the saved account")
            assertEquals("new", renamed.name)
            assertEquals("PA", renamed.alorPortfolio)
            assertEquals(3, renamed.weight, "вес не передан — сохраняется прежний")
            assertNull(renamed.aumRub, "переопределение AUM сброшено (передано null)")
            assertNull(renamed.maxOpenPositions)
            assertNull(renamed.maxDailyLossRub)

            val full =
                service.update(
                    1L,
                    alorPortfolio = "PZ",
                    exchange = "SPB",
                    enabled = false,
                    aumRub = BigDecimal("900"),
                    maxOpenPositions = 7,
                    maxDailyLossRub = BigDecimal("300"),
                    weight = 1,
                ) ?: error("update must return the saved account")
            assertEquals("PZ", full.alorPortfolio)
            assertEquals("SPB", full.exchange)
            assertFalse(full.enabled)
            assertEquals(BigDecimal("900"), full.aumRub)
            assertEquals(7, full.maxOpenPositions)
            assertEquals(BigDecimal("300"), full.maxDailyLossRub)
            assertEquals(1, full.weight)
        }
    }

    // --------------------------------------------------------------------- delete

    @Test
    fun `delete returns false for an unknown account`() {
        runBlocking {
            assertFalse(service.delete(99L))
            Mockito.verify(repository, Mockito.never()).deleteById(Mockito.anyLong())
        }
    }
}
