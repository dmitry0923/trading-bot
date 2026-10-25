package com.trading.bot.backtest.pairs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.exp
import kotlin.random.Random

/**
 * Покрытие «хвостовых» ветвей [PairsBacktestEngine]: удержание позиции до конца периода
 * (`closePositionAt` по close-ценам) и начисление funding за пережитые клиринги
 * (непустые карты `fundingA`/`fundingB` обоих ног).
 */
class PairsBacktestEngineExitTest {
    /** Понедельник: чтобы позиция пережила несколько клирингов будних дней. */
    private val start = LocalDateTime.of(2025, 1, 6, 10, 0)

    private fun legSpec(ticker: String) =
        PairsBacktestEngine.LegSpec(
            ticker = ticker,
            lotSize = 1000,
            commissionPerContractSide = 0.0,
            slippageBps = 0.0,
            fundingPerContractClearing = 0.0,
        )

    private fun barsFromLogs(
        la: DoubleArray,
        lb: DoubleArray,
    ): List<PairsBacktestEngine.Bar> =
        la.indices.map { i ->
            val a = exp(la[i])
            val b = exp(lb[i])
            PairsBacktestEngine.Bar(start.plusMinutes(10L * i), a, a, b, b)
        }

    private fun fundingMap(value: Double): Map<LocalDate, Double> {
        val firstDay = start.toLocalDate()
        return (0..4).associate { firstDay.plusDays(it.toLong()) to value }
    }

    /**
     * Спред стоит на нуле, затем ступенчато уходит на +0.3 и держится: вход происходит на
     * переходном окне z-score, а после него дисперсия остатка вырождается (z = NaN) — ни
     * CONVERGED, ни STOP, ни MAX_HOLD не срабатывают, позиция доживает до конца периода.
     */
    private fun divergingBars(bars: Int): List<PairsBacktestEngine.Bar> {
        val rnd = Random(11)
        val logB = DoubleArray(bars) { 2.4 }
        for (i in 1 until bars) logB[i] = logB[i - 1] + (rnd.nextDouble() - 0.5) * 0.004
        val logA = DoubleArray(bars) { if (it < 50) logB[it] else logB[it] + 0.3 }
        return barsFromLogs(logA, logB)
    }

    @Test
    fun `position held to the end is closed at the last close`() {
        val bars = divergingBars(300)
        val result =
            PairsBacktestEngine.run(
                bars = bars,
                specA = legSpec("CNYRUBF"),
                specB = legSpec("USDRUBF"),
                params =
                    PairsBacktestEngine.Params(
                        initialCapital = 100_000.0,
                        lookbackBars = 20,
                        entryZ = 1.5,
                        exitZ = -1.0,
                        stopZ = 100.0,
                        maxHoldBars = 0,
                        capitalSlice = 0.5,
                    ),
            )

        assertTrue(result.trades.isNotEmpty(), "linear divergence must open a position")
        val trade = result.trades.last()
        assertEquals("END_OF_PERIOD", trade.closeReason, "no CONVERGED/STOP exit expected")
        assertEquals(bars.last().time, trade.exitTime)
        assertTrue(trade.holdBars > 0)
        // Форсированное закрытие пересчитывает капитал последней точки кривой.
        assertEquals(100_000.0 + trade.netPnl, result.equityCurve.last(), 1e-6)
        assertEquals(bars.size, result.equityCurve.size)
    }

    @Test
    fun `funding is charged for every cleared day of both legs`() {
        val bars = divergingBars(300)
        val result =
            PairsBacktestEngine.run(
                bars = bars,
                specA = legSpec("CNYRUBF"),
                specB = legSpec("USDRUBF"),
                params =
                    PairsBacktestEngine.Params(
                        initialCapital = 100_000.0,
                        lookbackBars = 20,
                        entryZ = 1.5,
                        exitZ = -1.0,
                        stopZ = 100.0,
                        maxHoldBars = 0,
                        capitalSlice = 0.5,
                    ),
                fundingA = fundingMap(3.0),
                fundingB = fundingMap(1.0),
            )

        val trade = result.trades.last()
        assertTrue(trade.funding != 0.0, "both legs must charge funding, got ${trade.funding}")
        assertEquals(
            trade.grossPnl - trade.commission - trade.funding,
            trade.netPnl,
            1e-6,
            "net P&L must be gross minus commission minus funding",
        )
    }

    @Test
    fun `spec funding is used when history for the clearing date is missing`() {
        val bars = divergingBars(200)
        val spec =
            PairsBacktestEngine.LegSpec(
                ticker = "CNYRUBF",
                lotSize = 1000,
                commissionPerContractSide = 0.0,
                slippageBps = 0.0,
                fundingPerContractClearing = 2.0,
            )
        val result =
            PairsBacktestEngine.run(
                bars = bars,
                specA = spec,
                specB = spec.copy(ticker = "USDRUBF"),
                params =
                    PairsBacktestEngine.Params(
                        initialCapital = 100_000.0,
                        lookbackBars = 20,
                        entryZ = 1.5,
                        exitZ = -1.0,
                        stopZ = 100.0,
                    ),
            )

        val trade = result.trades.last()
        // Обе ноги: fallback-ставка по спецификации (история пуста).
        assertTrue(trade.funding != 0.0, "spec fallback funding must be charged, got ${trade.funding}")
    }
}
