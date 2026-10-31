package com.trading.bot.backtest

import com.trading.bot.model.PositionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [BacktestTradeAnalysisService] — in-memory статистика закрытых сделок:
 * фильтр по окну, дневной/скользящий P&L, серии убытков, экстремальные
 * profit factor (∞ при отсутствии убытков и 0 при нулевом P&L).
 */
class BacktestTradeAnalysisServiceTest {
    private val day = LocalDateTime.of(2026, 10, 1, 18, 0)

    private fun trade(
        ticker: String = "SBER",
        pnl: String,
        exitTime: LocalDateTime = day,
        closeReason: String = "TAKE_PROFIT",
        entryTime: LocalDateTime = exitTime.minusHours(1),
    ): BacktestTradeAnalysisService.ClosedTradeRecord =
        BacktestTradeAnalysisService.ClosedTradeRecord(
            ticker = ticker,
            pnl = BigDecimal(pnl),
            entryPrice = BigDecimal("100"),
            exitPrice = BigDecimal("101"),
            direction = PositionDirection.LONG,
            entryTime = entryTime,
            exitTime = exitTime,
            closeReason = closeReason,
            commission = BigDecimal("1"),
        )

    @Test
    fun `empty service returns no stats and zero pnl`() {
        val service = BacktestTradeAnalysisService()

        assertTrue(service.analyze().isEmpty())
        assertEquals(0, BigDecimal.ZERO.compareTo(service.dailyPnl(day)))
        assertEquals(0, BigDecimal.ZERO.compareTo(service.rollingPnl(day, 7)))
        assertEquals(0, service.maxConsecutiveLosses())
        assertEquals(0, service.currentConsecutiveLosses())
        assertEquals(0, service.totalTrades())
        assertTrue(service.allTrades().isEmpty())
    }

    @Test
    fun `stats aggregate wins losses and close reasons per ticker`() {
        val service = BacktestTradeAnalysisService()
        service.recordClose(trade(pnl = "100", closeReason = "TAKE_PROFIT"))
        service.recordClose(trade(pnl = "-40", closeReason = "STOP_LOSS"))
        service.recordClose(trade(pnl = "-60", closeReason = "REVERSAL"))
        service.recordClose(trade(ticker = "GAZP", pnl = "10", closeReason = "STRATEGY_CLOSE"))

        val stats = service.analyze()

        val sber = stats.getValue("SBER")
        assertEquals(3, sber.totalTrades)
        assertEquals(1, sber.winningTrades)
        assertEquals(2, sber.losingTrades)
        assertEquals(1.0 / 3.0, sber.winRate, 1e-9)
        assertEquals(0, BigDecimal("100.00").compareTo(sber.avgWin))
        assertEquals(0, BigDecimal("50.00").compareTo(sber.avgLoss))
        assertEquals(1.0, sber.profitFactor, 1e-9)
        assertEquals(2, sber.maxConsecutiveLosses)
        assertEquals(1.0 / 3.0, sber.strategyCloseRate, 1e-9)
        assertEquals(1.0 / 3.0, sber.slHitRate, 1e-9)
        assertEquals(1.0 / 3.0, sber.tpHitRate, 1e-9)
        assertEquals(60, sber.avgHoldTimeMinutes)

        val gazp = stats.getValue("GAZP")
        assertEquals(Double.POSITIVE_INFINITY, gazp.profitFactor, 0.0)
        assertEquals(0.0, gazp.tpHitRate, 1e-9)

        assertEquals(4, service.totalTrades())
        assertEquals(4, service.allTrades().size)
        service.clear()
        assertEquals(0, service.totalTrades())
    }

    @Test
    fun `window filter keeps only trades inside the requested days`() {
        val service = BacktestTradeAnalysisService()
        service.recordClose(trade(pnl = "10", exitTime = day))
        service.recordClose(trade(pnl = "20", exitTime = day.minusDays(10)))

        val recent = service.analyze(days = 3, currentTime = day)

        assertEquals(1, recent.getValue("SBER").totalTrades)
        // Без currentTime фильтр не применяется (days игнорируется).
        assertEquals(2, service.analyze(days = 3).getValue("SBER").totalTrades)
    }

    @Test
    fun `daily and rolling pnl sum only the requested window`() {
        val service = BacktestTradeAnalysisService()
        service.recordClose(trade(pnl = "10", exitTime = day))
        service.recordClose(trade(pnl = "-4", exitTime = day.plusHours(3)))
        service.recordClose(trade(pnl = "100", exitTime = day.minusDays(10)))

        assertEquals(0, BigDecimal("6").compareTo(service.dailyPnl(day)))
        assertEquals(0, BigDecimal("6").compareTo(service.rollingPnl(day, 1)))
        assertEquals(0, BigDecimal("106").compareTo(service.rollingPnl(day, 30)))
        // Явный список сделок перекрывает накопленный.
        assertEquals(
            0,
            BigDecimal("100").compareTo(service.dailyPnl(day, listOf(trade(pnl = "100", exitTime = day)))),
        )
    }

    @Test
    fun `consecutive loss counters use chronological and reverse order`() {
        val service = BacktestTradeAnalysisService()
        service.recordClose(trade(pnl = "-1", exitTime = day.minusDays(3)))
        service.recordClose(trade(pnl = "-1", exitTime = day.minusDays(2)))
        service.recordClose(trade(pnl = "5", exitTime = day.minusDays(1)))
        service.recordClose(trade(pnl = "-1", exitTime = day))

        assertEquals(2, service.maxConsecutiveLosses())
        assertEquals(1, service.currentConsecutiveLosses())

        service.recordClose(trade(pnl = "-1", exitTime = day.plusHours(1)))
        assertEquals(2, service.currentConsecutiveLosses())
    }

    @Test
    fun `zero pnl trades give a zero profit factor`() {
        val service = BacktestTradeAnalysisService()
        service.recordClose(trade(pnl = "0", closeReason = "STRATEGY_CLOSE"))

        val stats = service.analyze().getValue("SBER")

        assertEquals(0.0, stats.profitFactor, 1e-9)
        assertEquals(0.0, stats.winRate, 1e-9)
    }
}
