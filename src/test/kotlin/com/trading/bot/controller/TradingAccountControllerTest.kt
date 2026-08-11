package com.trading.bot.controller

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.TradingAccount
import com.trading.bot.repository.DailyRiskSnapshotRepository
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.AumProvider
import com.trading.bot.service.DrawdownProtectionService
import com.trading.bot.service.TradingAccountService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Unit-тесты [TradingAccountController]: CRUD аккаунтов, валидация и защита
 * удаления аккаунта с позициями / неотправленными outbox-ордерами.
 */
class TradingAccountControllerTest {
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)
    private val positionRepository = Mockito.mock(PositionRepository::class.java)
    private val orderOutboxRepository = Mockito.mock(OrderOutboxRepository::class.java)
    private val dailyRiskSnapshotRepository = Mockito.mock(DailyRiskSnapshotRepository::class.java)
    private val aumProvider = Mockito.mock(AumProvider::class.java)
    private val drawdownProtectionService = Mockito.mock(DrawdownProtectionService::class.java)
    private val controller =
        TradingAccountController(
            tradingAccountService,
            positionRepository,
            orderOutboxRepository,
            dailyRiskSnapshotRepository,
            aumProvider,
            drawdownProtectionService,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(
            tradingAccountService,
            positionRepository,
            orderOutboxRepository,
            dailyRiskSnapshotRepository,
            aumProvider,
            drawdownProtectionService,
        )
    }

    @Test
    fun `list returns accounts with open position counts`() {
        val account = account(1L, "A", "PA")
        runBlocking {
            Mockito.`when`(tradingAccountService.findAll()).thenReturn(listOf(account))
            Mockito.`when`(positionRepository.findOpenCountByAccount(1L)).thenReturn(3)
        }

        val list = runBlocking { controller.list() }
        assertEquals(1, list.size)
        assertEquals("A", list[0]["name"])
        assertEquals(3, list[0]["openPositions"])
    }

    @Test
    fun `get returns account with open positions`() {
        runBlocking {
            Mockito.`when`(tradingAccountService.findById(1L)).thenReturn(account(1L, "A", "PA"))
            Mockito.`when`(positionRepository.findOpenByAccount(1L)).thenReturn(listOf(openPosition()))
            Mockito.`when`(positionRepository.findOpenCountByAccount(1L)).thenReturn(1)
        }

        val detail = runBlocking { controller.get(1L) }
        assertEquals("PA", detail["alorPortfolio"])
        assertEquals(1, detail["openPositionsCount"])
        assertEquals(1, (detail["openPositions"] as List<*>).size)
    }

    @Test
    fun `get returns 404 for unknown account`() {
        runBlocking {
            Mockito.`when`(tradingAccountService.findById(99L)).thenReturn(null)
        }
        try {
            runBlocking { controller.get(99L) }
            assertTrue(false, "expected ResponseStatusException")
        } catch (e: ResponseStatusException) {
            assertEquals(404, e.statusCode.value())
        }
    }

    @Test
    fun `create rejects blank name and blank portfolio`() {
        try {
            runBlocking {
                controller.create(TradingAccountRequest(name = "  ", alorPortfolio = "PA"))
            }
            assertTrue(false, "expected ResponseStatusException")
        } catch (e: ResponseStatusException) {
            assertEquals(400, e.statusCode.value())
        }
    }

    @Test
    fun `create validates negative weight`() {
        try {
            runBlocking {
                controller.create(TradingAccountRequest(name = "A", alorPortfolio = "PA", weight = 0))
            }
            assertTrue(false, "expected ResponseStatusException")
        } catch (e: ResponseStatusException) {
            assertEquals(400, e.statusCode.value())
        }
    }

    @Test
    fun `create delegates to service`() {
        val created = account(1L, "A", "PA", weight = 2)
        runBlocking {
            Mockito.`when`(tradingAccountService.create(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt()))
                .thenReturn(created)
        }

        val result =
            runBlocking {
                controller.create(TradingAccountRequest(name = "A", alorPortfolio = "PA", weight = 2))
            }
        assertEquals(1L, result["id"])
        assertEquals(2, result["weight"])
        runBlocking { Mockito.verify(tradingAccountService).create(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt()) }
    }

    @Test
    fun `update returns 404 for unknown account`() {
        runBlocking {
            Mockito.`when`(tradingAccountService.update(Mockito.eq(99L), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt()))
                .thenReturn(null)
        }
        try {
            runBlocking {
                controller.update(99L, TradingAccountRequest(name = "A", alorPortfolio = "PA"))
            }
            assertTrue(false, "expected ResponseStatusException")
        } catch (e: ResponseStatusException) {
            assertEquals(404, e.statusCode.value())
        }
    }

    @Test
    fun `delete rejects account with any referencing positions`() {
        runBlocking {
            Mockito.`when`(tradingAccountService.findById(1L)).thenReturn(account(1L, "A", "PA"))
            Mockito.`when`(positionRepository.countByAccount(1L)).thenReturn(5)
        }
        try {
            runBlocking { controller.delete(1L) }
            assertTrue(false, "expected ResponseStatusException")
        } catch (e: ResponseStatusException) {
            assertEquals(409, e.statusCode.value())
        }
        runBlocking { Mockito.verify(tradingAccountService, Mockito.never()).delete(Mockito.anyLong()) }
    }

    @Test
    fun `delete rejects account with undelivered outbox orders`() {
        runBlocking {
            Mockito.`when`(tradingAccountService.findById(1L)).thenReturn(account(1L, "A", "PA"))
            Mockito.`when`(positionRepository.countByAccount(1L)).thenReturn(0)
            Mockito.`when`(orderOutboxRepository.countPendingByAccount(1L)).thenReturn(3)
        }
        try {
            runBlocking { controller.delete(1L) }
            assertTrue(false, "expected ResponseStatusException")
        } catch (e: ResponseStatusException) {
            assertEquals(409, e.statusCode.value())
        }
        runBlocking { Mockito.verify(tradingAccountService, Mockito.never()).delete(Mockito.anyLong()) }
    }

    @Test
    fun `delete removes account when no positions and no pending outbox`() {
        runBlocking {
            Mockito.`when`(tradingAccountService.findById(1L)).thenReturn(account(1L, "A", "PA"))
            Mockito.`when`(positionRepository.countByAccount(1L)).thenReturn(0)
            Mockito.`when`(orderOutboxRepository.countPendingByAccount(1L)).thenReturn(0)
            Mockito.`when`(tradingAccountService.delete(1L)).thenReturn(true)
        }

        val result = runBlocking { controller.delete(1L) }
        assertEquals(true, result["deleted"])
        runBlocking { Mockito.verify(tradingAccountService).delete(1L) }
    }

    @Test
    fun `daily pnl history returns per-account points`() {
        val snapshot =
            com.trading.bot.model.entity.DailyRiskSnapshot(
                id = 1L,
                tradeDate = java.time.LocalDate.of(2026, 8, 10),
                dailyPnl = BigDecimal("1500"),
                limitReached = false,
                maxDrawdownToday = BigDecimal.ZERO,
            )
        runBlocking {
            Mockito.`when`(tradingAccountService.findById(1L)).thenReturn(account(1L, "A", "PA"))
            Mockito.`when`(dailyRiskSnapshotRepository.findRecent(Mockito.anyInt(), Mockito.eq(1L)))
                .thenReturn(listOf(snapshot))
        }

        val result = runBlocking { controller.dailyPnlHistory(1L, 30) }
        assertEquals(1L, result["accountId"])
        assertEquals(1, (result["points"] as List<*>).size)
        runBlocking {
            Mockito.verify(dailyRiskSnapshotRepository).findRecent(30, 1L)
        }
    }

    @Test
    fun `account dashboard aggregates aum, daily pnl, limits and positions`() {
        val pos = openPosition().copy(pnl = BigDecimal("250"))
        runBlocking {
            Mockito.`when`(tradingAccountService.findById(1L)).thenReturn(account(1L, "A", "PA"))
            Mockito.`when`(positionRepository.findOpenByAccount(1L)).thenReturn(listOf(pos))
            Mockito.`when`(positionRepository.findClosedByAccountSince(Mockito.eq(1L), any<LocalDateTime>()))
                .thenReturn(emptyList())
            Mockito.`when`(aumProvider.currentAum(1L)).thenReturn(BigDecimal("1000000"))
            Mockito.`when`(drawdownProtectionService.getDailyPnl(1L)).thenReturn(BigDecimal("300"))
            Mockito.`when`(drawdownProtectionService.isDailyLossLimitReached(1L)).thenReturn(false)
            Mockito.`when`(drawdownProtectionService.isEntryBlocked(1L)).thenReturn(true)
            Mockito.`when`(tradingAccountService.maxDailyLossRubFor(1L)).thenReturn(BigDecimal("20000"))
            Mockito.`when`(tradingAccountService.maxOpenPositionsFor(1L)).thenReturn(5)
        }

        val result = runBlocking { controller.accountDashboard(1L) }
        assertEquals("PA", result["portfolio"])
        assertEquals(BigDecimal("1000000"), result["aum"])
        assertEquals(BigDecimal("300"), result["dailyPnl"])
        assertEquals(true, result["entryBlocked"])
        assertEquals(5, result["maxOpenPositions"])
        assertEquals(1, result["openPositionsCount"])
        assertEquals(BigDecimal("250"), result["openPnl"])
        assertEquals(0, result["closedTodayCount"])
    }

    @Test
    fun `account dashboard returns 404 for unknown account`() {
        runBlocking {
            Mockito.`when`(tradingAccountService.findById(99L)).thenReturn(null)
        }
        try {
            runBlocking { controller.accountDashboard(99L) }
            assertTrue(false, "expected ResponseStatusException")
        } catch (e: ResponseStatusException) {
            assertEquals(404, e.statusCode.value())
        }
    }

    private fun account(
        id: Long,
        name: String,
        alorPortfolio: String,
        weight: Int = 1,
    ): TradingAccount =
        TradingAccount(
            id = id,
            name = name,
            alorPortfolio = alorPortfolio,
            weight = weight,
        )

    private fun openPosition(): Position =
        Position(
            id = 1L,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            status = PositionStatus.OPEN,
        )
}
