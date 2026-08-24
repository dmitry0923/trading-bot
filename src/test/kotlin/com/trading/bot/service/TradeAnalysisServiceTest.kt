package com.trading.bot.service

import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Profit factor закрытых сделок (roadmap 13.24, FIND-BUG-1): НЕТ проигрышей при наличии
 * прибыли -> +Infinity, а не 0.0. Иначе shouldPauseTrading (profitFactor in 0.0..0.5)
 * ставит на паузу тикер со 100% win rate.
 */
class TradeAnalysisServiceTest {
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val blindSpotRepo = Mockito.mock(BlindSpotRepository::class.java)
    private val service = TradeAnalysisService(positionRepo, blindSpotRepo)

    private fun closed(
        pnl: BigDecimal,
        reason: CloseReason = CloseReason.STRATEGY_CLOSE,
    ): Position =
        Position(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            pnl = pnl,
            status = PositionStatus.CLOSED,
            closeReason = reason,
        )

    @Test
    fun `all profitable trades yield infinite profit factor`() =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(
                listOf(closed(BigDecimal("50")), closed(BigDecimal("30")), closed(BigDecimal("20"))),
            )

            val stats = service.analyzeLastNDays(7)["SBER"]!!

            assertEquals(Double.POSITIVE_INFINITY, stats.profitFactor)
            assertTrue(stats.totalTrades == 3 && stats.winningTrades == 3 && stats.losingTrades == 0)
        }

    @Test
    fun `classic profit factor computed from gross profit over gross loss`() =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(
                listOf(closed(BigDecimal("100")), closed(BigDecimal("-50")), closed(BigDecimal("-50"))),
            )

            val stats = service.analyzeLastNDays(7)["SBER"]!!

            assertEquals(1.0, stats.profitFactor, 1e-9)
        }

    @Test
    fun `break-even trades yield zero profit factor`() =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(
                listOf(closed(BigDecimal.ZERO), closed(BigDecimal.ZERO)),
            )

            val stats = service.analyzeLastNDays(7)["SBER"]!!

            assertEquals(0.0, stats.profitFactor, 1e-9)
        }

    @Test
    fun `no closed positions yields empty map`() =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(emptyList())

            assertTrue(service.analyzeLastNDays(7).isEmpty())
        }

    // ─── Account isolation (a8024ad review priority 3) ──────────────────────────

    @Test
    fun `accountId=123 calls findClosedByAccountSince not findClosedSince`() =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedByAccountSince(eq(123L), any())).thenReturn(
                listOf(closed(BigDecimal("10"))),
            )

            val stats = service.analyzeLastNDays(7, accountId = 123L)

            assertTrue(stats.containsKey("SBER"))
            assertEquals(1, stats["SBER"]!!.totalTrades)
            verify(positionRepo).findClosedByAccountSince(eq(123L), any())
            verify(positionRepo, Mockito.never()).findClosedSince(any())
            Unit
        }

    @Test
    fun `accountId=null calls findClosedSince not findClosedByAccountSince`() =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(
                listOf(closed(BigDecimal("20"))),
            )

            val stats = service.analyzeLastNDays(7, accountId = null)

            assertTrue(stats.containsKey("SBER"))
            verify(positionRepo).findClosedSince(any())
            verify(positionRepo, Mockito.never()).findClosedByAccountSince(any(), any())
            Unit
        }

    @Test
    fun `accountId=10 sees only its own positions, accountId=20 sees only its own`() =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedByAccountSince(eq(10L), any())).thenReturn(
                listOf(closed(BigDecimal("50"))),
            )
            Mockito.`when`(positionRepo.findClosedByAccountSince(eq(20L), any())).thenReturn(
                listOf(closed(BigDecimal("-30"))),
            )

            val stats10 = service.analyzeLastNDays(7, accountId = 10L)
            val stats20 = service.analyzeLastNDays(7, accountId = 20L)

            assertEquals(1, stats10["SBER"]!!.winningTrades)
            assertEquals(0, stats10["SBER"]!!.losingTrades)

            assertEquals(0, stats20["SBER"]!!.winningTrades)
            assertEquals(1, stats20["SBER"]!!.losingTrades)
        }

    // ─── Clock determinism (a8024ad review priority 3) ───────────────────────────

    @Test
    fun `FIXED clock produces deterministic since calculation`() =
        runBlocking {
            val moscowOffset = ZoneOffset.of("+03:00")
            val fixedInstant = LocalDateTime.of(2025, 6, 15, 12, 0).toInstant(moscowOffset)
            val fixedClock = Clock.fixed(fixedInstant, ZoneId.of("Europe/Moscow"))
            val svc = TradeAnalysisService(positionRepo, blindSpotRepo, clock = fixedClock)

            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(emptyList())

            svc.analyzeLastNDays(7)

            verify(positionRepo).findClosedSince(
                eq(LocalDateTime.of(2025, 6, 8, 12, 0)),
            )
            Unit
        }

    @Test
    fun `timePatternAnalysis with accountId calls findClosedByTickerAndAccountSince`() =
        runBlocking {
            Mockito
                .`when`(
                    positionRepo.findClosedByTickerAndAccountSince(eq("SBER"), eq(42L), any()),
                ).thenReturn(
                    listOf(closed(BigDecimal("15"))),
                )

            val pattern = service.timePatternAnalysis("SBER", days = 30, accountId = 42L)

            assertEquals("SBER", pattern.ticker)
            verify(positionRepo).findClosedByTickerAndAccountSince(eq("SBER"), eq(42L), any())
            verify(positionRepo, Mockito.never()).findClosedByTickerSince(any(), any())
            Unit
        }

    @Test
    fun `timePatternAnalysis without accountId calls findClosedByTickerSince`() =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedByTickerSince(any(), any())).thenReturn(
                listOf(closed(BigDecimal("-5"))),
            )

            val pattern = service.timePatternAnalysis("SBER", days = 30, accountId = null)

            assertEquals("SBER", pattern.ticker)
            verify(positionRepo).findClosedByTickerSince(any(), any())
            verify(positionRepo, Mockito.never()).findClosedByTickerAndAccountSince(any(), any(), any())
            Unit
        }
}
