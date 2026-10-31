package com.trading.bot.service

import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [TradeAnalysisService] — статистика смешанного набора сделок: null-P&L,
 * почасовые win rate (лучший/худший час), серия убытков, время удержания и
 * границы детектора слепых зон (доля SL, частота часа).
 */
class TradeAnalysisServiceBranchesTest {
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val blindSpotRepo = Mockito.mock(BlindSpotRepository::class.java)
    private val service = TradeAnalysisService(positionRepo, blindSpotRepo)

    private fun trade(
        pnl: BigDecimal?,
        reason: CloseReason = CloseReason.STRATEGY_CLOSE,
        openedHour: Int = 10,
        closedAt: LocalDateTime? = LocalDateTime.of(2026, 10, 1, 12, 0),
    ): Position =
        Position(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            pnl = pnl,
            status = PositionStatus.CLOSED,
            closeReason = reason,
            openedAt = LocalDateTime.of(2026, 10, 1, openedHour, 0),
            closedAt = closedAt,
        )

    @Test
    fun `mixed trade set reports rates, streaks and best and worst hours`(): Unit =
        runBlocking {
            val trades =
                listOf(
                    trade(BigDecimal("10"), CloseReason.TAKE_PROFIT, openedHour = 10),
                    trade(BigDecimal("20"), CloseReason.TAKE_PROFIT, openedHour = 10),
                    trade(BigDecimal("5"), CloseReason.STRATEGY_CLOSE, openedHour = 10),
                    trade(BigDecimal("-10"), CloseReason.STOP_LOSS, openedHour = 15, closedAt = LocalDateTime.of(2026, 10, 1, 17, 0)),
                    trade(BigDecimal("-20"), CloseReason.STOP_LOSS, openedHour = 15, closedAt = LocalDateTime.of(2026, 10, 1, 17, 0)),
                    trade(BigDecimal("-5"), CloseReason.STOP_LOSS, openedHour = 15, closedAt = LocalDateTime.of(2026, 10, 1, 17, 0)),
                    // null P&L не должен ломать ни одну из сумм
                    trade(null, CloseReason.STRATEGY_CLOSE, openedHour = 12, closedAt = null),
                )
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(trades)
            Mockito.`when`(blindSpotRepo.findByTickerAndIsActiveTrue("SBER")).thenReturn(emptyList())
            Mockito.`when`(blindSpotRepo.save(any())).thenAnswer { invocation ->
                invocation.getArgument<com.trading.bot.model.entity.BlindSpotEntity>(0)
            }

            val stats = service.analyzeLastNDays(7)["SBER"]!!

            assertEquals(7, stats.totalTrades)
            assertEquals(3, stats.winningTrades)
            assertEquals(4, stats.losingTrades)
            assertEquals(3.0 / 7.0, stats.winRate, 1e-9)
            assertEquals(1.0, stats.profitFactor, 1e-9)
            assertEquals(3, stats.maxConsecutiveLosses)
            assertEquals(3.0 / 7.0, stats.slHitRate, 1e-9)
            assertEquals(2.0 / 7.0, stats.tpHitRate, 1e-9)
            assertEquals(2.0 / 7.0, stats.strategyCloseRate, 1e-9)
            assertEquals(10, stats.bestEntryHour)
            assertEquals(15, stats.worstEntryHour)
            // avgHoldTime считается только по сделкам с closedAt (2 часа = 120 минут)
            assertEquals(120L, stats.avgHoldTimeMinutes)
            // слепые зоны: SL-доля 3/4 > 60% и три убытка в 15:00
            assertTrue(stats.blindSpots.any { it.conditionPattern.contains("Stop-Loss") })
            assertTrue(stats.blindSpots.any { it.conditionPattern.contains("hour 15") })
        }

    @Test
    fun `three losses with a low stop share do not create blind spots`(): Unit =
        runBlocking {
            val trades =
                listOf(
                    trade(BigDecimal("-1"), CloseReason.STRATEGY_CLOSE, openedHour = 11),
                    trade(BigDecimal("-2"), CloseReason.STRATEGY_CLOSE, openedHour = 12),
                    trade(BigDecimal("-3"), CloseReason.STOP_LOSS, openedHour = 13),
                )
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(trades)
            Mockito.`when`(blindSpotRepo.findByTickerAndIsActiveTrue("SBER")).thenReturn(emptyList())

            val stats = service.analyzeLastNDays(7)["SBER"]!!

            // SL-доля 1/3 < 60%, каждый час встречается один раз
            assertTrue(stats.blindSpots.isEmpty())
        }

    @Test
    fun `fewer than three losses never produce blind spots`(): Unit =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(
                listOf(trade(BigDecimal("-1"), CloseReason.STOP_LOSS), trade(BigDecimal("-2"), CloseReason.STOP_LOSS)),
            )

            val stats = service.analyzeLastNDays(7)["SBER"]!!

            assertTrue(stats.blindSpots.isEmpty())
        }

    @Test
    fun `all winners leave no worst hour`(): Unit =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(
                listOf(trade(BigDecimal("5"), openedHour = 10), trade(BigDecimal("7"), openedHour = 11)),
            )
            Mockito.`when`(blindSpotRepo.findByTickerAndIsActiveTrue("SBER")).thenReturn(emptyList())

            val stats = service.analyzeLastNDays(7)["SBER"]!!

            assertEquals(10, stats.bestEntryHour)
            assertNull(stats.worstEntryHour)
        }

    @Test
    fun `all losers leave no best hour`(): Unit =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(
                listOf(trade(BigDecimal("-5"), openedHour = 10), trade(BigDecimal("-7"), openedHour = 11)),
            )
            Mockito.`when`(blindSpotRepo.findByTickerAndIsActiveTrue("SBER")).thenReturn(emptyList())

            val stats = service.analyzeLastNDays(7)["SBER"]!!

            assertNull(stats.bestEntryHour)
            assertEquals(10, stats.worstEntryHour)
        }

    @Test
    fun `hold time is zero when no trade has a close timestamp`(): Unit =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(
                listOf(trade(BigDecimal("5"), closedAt = null)),
            )

            val stats = service.analyzeLastNDays(7)["SBER"]!!

            assertEquals(0L, stats.avgHoldTimeMinutes)
        }

    @Test
    fun `time pattern groups win rate by entry hour`(): Unit =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedByTickerSince(eq("SBER"), any())).thenReturn(
                listOf(
                    trade(BigDecimal("5"), openedHour = 10),
                    trade(BigDecimal("-5"), openedHour = 10),
                    trade(BigDecimal("7"), openedHour = 11),
                ),
            )

            val pattern = service.timePatternAnalysis("SBER", days = 30)

            assertEquals(0.5, pattern.hourlyWinRates[10]!!, 1e-9)
            assertEquals(1.0, pattern.hourlyWinRates[11]!!, 1e-9)
        }

    @Test
    fun `time pattern without trades is empty`(): Unit =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedByTickerSince(eq("SBER"), any())).thenReturn(emptyList())

            val pattern = service.timePatternAnalysis("SBER", days = 30)

            assertTrue(pattern.hourlyWinRates.isEmpty())
        }
}
