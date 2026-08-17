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
import java.math.BigDecimal

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
}
