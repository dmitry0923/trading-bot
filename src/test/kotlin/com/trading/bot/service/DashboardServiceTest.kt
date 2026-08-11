package com.trading.bot.service

import com.trading.bot.config.TradingConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.StrategyRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.verify
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit-тесты [DashboardService]: агрегированный вид (без фильтра) и per-account
 * фильтрация позиций и дневного P&L (multi-account, roadmap v2.2).
 */
class DashboardServiceTest {
    private val tradingConfig = TradingConfig()
    private val positionRepository = Mockito.mock(PositionRepository::class.java)
    private val strategyRepository = Mockito.mock(StrategyRepository::class.java)
    private val tradeAnalysisService = Mockito.mock(TradeAnalysisService::class.java)
    private val adaptiveRiskService = Mockito.mock(AdaptiveRiskService::class.java)
    private val riskManagementService = Mockito.mock(RiskManagementService::class.java)
    private val service =
        DashboardService(
            tradingConfig,
            positionRepository,
            strategyRepository,
            tradeAnalysisService,
            adaptiveRiskService,
            riskManagementService,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(positionRepository, strategyRepository, tradeAnalysisService, adaptiveRiskService, riskManagementService)
        tradingConfig.tickers = emptyList()
        runBlocking {
            Mockito.`when`(strategyRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(emptyList())
            Mockito.`when`(tradeAnalysisService.analyzeLastNDays(7)).thenReturn(emptyMap())
        }
    }

    @Test
    fun `build without filter aggregates all positions and global daily pnl`() {
        val open = openPosition(pnl = "100")
        val closedToday = closedPosition(pnl = "-40")
        val todayStart = LocalDate.now().atStartOfDay()
        runBlocking {
            Mockito.`when`(positionRepository.findByStatus(PositionStatus.OPEN)).thenReturn(listOf(open))
            Mockito.`when`(positionRepository.findClosedSince(todayStart)).thenReturn(listOf(closedToday))
            Mockito.`when`(riskManagementService.getDailyPnL()).thenReturn(BigDecimal("60"))
        }

        val result = runBlocking { service.build() }

        assertEquals(null, result["accountId"])
        assertEquals(BigDecimal("100"), result["openPnl"])
        assertEquals(BigDecimal("-40"), result["realizedPnlToday"])
        assertEquals(1, result["closedTodayCount"])
        assertEquals(1, result["openPositionsCount"])
        assertEquals(BigDecimal("60"), result["dailyPnl"])
        runBlocking {
            verify(positionRepository).findByStatus(PositionStatus.OPEN)
            verify(positionRepository).findClosedSince(todayStart)
            verify(riskManagementService).getDailyPnL()
        }
    }

    @Test
    fun `build filters open and closed positions and daily pnl by account`() {
        val open = openPosition(pnl = "250").copy(accountId = 1L)
        val closedToday = closedPosition(pnl = "-150").copy(accountId = 1L)
        val todayStart = LocalDate.now().atStartOfDay()
        runBlocking {
            Mockito.`when`(positionRepository.findOpenByAccount(1L)).thenReturn(listOf(open))
            Mockito.`when`(positionRepository.findClosedByAccountSince(1L, todayStart)).thenReturn(listOf(closedToday))
            Mockito.`when`(riskManagementService.getDailyPnL(1L)).thenReturn(BigDecimal("100"))
        }

        val result = runBlocking { service.build(1L) }

        assertEquals(1L, result["accountId"])
        assertEquals(BigDecimal("250"), result["openPnl"])
        assertEquals(BigDecimal("-150"), result["realizedPnlToday"])
        assertEquals(1, result["closedTodayCount"])
        assertEquals(BigDecimal("100"), result["dailyPnl"])
        runBlocking {
            verify(positionRepository).findOpenByAccount(1L)
            verify(positionRepository).findClosedByAccountSince(1L, todayStart)
            verify(riskManagementService).getDailyPnL(1L)
        }
    }

    @Test
    fun `filtered account with no positions returns zeros`() {
        val todayStart = LocalDate.now().atStartOfDay()
        runBlocking {
            Mockito.`when`(positionRepository.findOpenByAccount(7L)).thenReturn(emptyList())
            Mockito.`when`(positionRepository.findClosedByAccountSince(7L, todayStart)).thenReturn(emptyList())
            Mockito.`when`(riskManagementService.getDailyPnL(7L)).thenReturn(BigDecimal.ZERO)
        }

        val result = runBlocking { service.build(7L) }

        assertEquals(0, result["openPositionsCount"])
        assertEquals(0, result["closedTodayCount"])
        assertTrue((result["openPositions"] as List<*>).isEmpty())
    }

    private fun openPosition(pnl: String) =
        Position(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("250"),
            currentPrice = BigDecimal("250"),
            pnl = BigDecimal(pnl),
            status = PositionStatus.OPEN,
            instrumentType = InstrumentType.STOCK,
            openedAt = LocalDateTime.now(),
        )

    private fun closedPosition(pnl: String) =
        Position(
            ticker = "GAZP",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("200"),
            closePrice = BigDecimal("160"),
            pnl = BigDecimal(pnl),
            status = PositionStatus.CLOSED,
            instrumentType = InstrumentType.STOCK,
            openedAt = LocalDateTime.now(),
            closedAt = LocalDateTime.now(),
        )
}
