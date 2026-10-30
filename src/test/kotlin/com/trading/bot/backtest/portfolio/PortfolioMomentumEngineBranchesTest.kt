package com.trading.bot.backtest.portfolio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.exp

/**
 * Непокрытые ветви [PortfolioMomentumEngine] (скан-19: 56 из 220): вырожденное
 * сечение (z-score без дисперсии — «сжатый» рынок не даёт сигнала), отсев
 * инструментов с непригодными ценами/нотионалом, carry без point-in-time истории и
 * spec-fallback, fallback risk-parity при плоских ценах и нехватке окна, нулевая
 * целевая книга, ликвидация книги на последнем баре (`END_OF_PERIOD`).
 */
class PortfolioMomentumEngineBranchesTest {
    private val start = LocalDateTime.of(2025, 1, 1, 0, 0)

    private fun spec(
        ticker: String,
        funding: Double = 0.0,
    ) = PortfolioMomentumEngine.LegSpec(
        ticker = ticker,
        lotSize = 1,
        commissionPerContractSide = 0.0,
        slippageBps = 0.0,
        fundingPerContractClearing = funding,
    )

    private fun bars(
        n: Int,
        closes: List<(Int) -> Double>,
        opens: List<(Int) -> Double> = closes,
    ): List<PortfolioMomentumEngine.Bar> =
        (0 until n).map { i ->
            PortfolioMomentumEngine.Bar(
                time = start.plusMinutes(10L * i),
                opens = DoubleArray(closes.size) { k -> opens[k](i) },
                closes = DoubleArray(closes.size) { k -> closes[k](i) },
            )
        }

    private fun params() =
        PortfolioMomentumEngine.Params(
            initialCapital = 100_000.0,
            lookbackBars = 60,
            rebalanceBars = 40,
            capitalSlice = 0.5,
            minUniverse = 3,
        )

    /** UP растёт, FLAT стоит, DOWN падает. */
    private fun trending(n: Int) =
        bars(
            n,
            closes =
                listOf(
                    { i: Int -> exp(2.4 + 0.0008 * i) },
                    { _: Int -> exp(2.4) },
                    { i: Int -> exp(2.4 - 0.0008 * i) },
                ),
        )

    @Test
    fun `flat cross section has no dispersion and produces no signal`() {
        val flat = bars(200, List(3) { _ -> { _: Int -> exp(2.4) } })

        val result = PortfolioMomentumEngine.run(flat, listOf(spec("A"), spec("B"), spec("C")), params())

        assertTrue(result.trades.isEmpty(), "z-score без дисперсии — сигнала нет")
        assertEquals(0, result.rebalances)
        assertEquals(200, result.equityCurve.size, "equity-кривая строится всё равно")
    }

    @Test
    fun `both weights zero disables the selection`() {
        val result =
            PortfolioMomentumEngine.run(
                trending(200),
                listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params().copy(momentumWeight = 0.0, carryWeight = 0.0),
            )

        assertTrue(result.trades.isEmpty())
        assertEquals(0, result.rebalances)
    }

    @Test
    fun `tickers with unusable prices are excluded from the cross section`() {
        // Третий инструмент имеет нулевую цену в окне momentum → невалиден.
        val broken =
            bars(
                200,
                closes =
                    listOf(
                        { i: Int -> exp(2.4 + 0.0008 * i) },
                        { i: Int -> exp(2.4 - 0.0008 * i) },
                        { _: Int -> 0.0 },
                    ),
            )

        val result =
            PortfolioMomentumEngine.run(
                broken,
                listOf(spec("UP"), spec("DOWN"), spec("BROKEN")),
                params(),
            )

        assertTrue(result.trades.isEmpty(), "минимум юниверса не набран — сигнала нет")
        assertEquals(0, result.rebalances)
    }

    @Test
    fun `requested legs above the valid universe give no selection`() {
        val result =
            PortfolioMomentumEngine.run(
                trending(200),
                listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params().copy(longCount = 2, shortCount = 2),
            )

        assertTrue(result.trades.isEmpty(), "4 ноги на 3 валидных инструмента невозможны")
    }

    @Test
    fun `carry without point in time history is unknown and gives no signal`() {
        // Все клиринги — в будущем относительно бара решения → point-in-time отсекает их.
        val day = start.toLocalDate().plusDays(30)
        val funding =
            listOf(
                mapOf(day to -5.0),
                mapOf(day to 0.0),
                mapOf(day to 5.0),
            )

        val result =
            PortfolioMomentumEngine.run(
                bars(200, List(3) { _ -> { _: Int -> exp(2.4) } }),
                listOf(spec("A"), spec("B"), spec("C")),
                params().copy(momentumWeight = 0.0, carryWeight = 1.0),
                funding = funding,
            )

        assertTrue(result.trades.isEmpty(), "нет завершённых клирингов — carry неизвестен")
    }

    @Test
    fun `carry falls back to the spec rate when the funding series is short`() {
        val specs =
            listOf(
                spec("RECEIVER", funding = -5.0),
                spec("ZERO", funding = 0.0),
                spec("PAYER", funding = 5.0),
            )
        // История есть только для первого инструмента, остальные — spec-fallback.
        val funding = listOf(mapOf(LocalDate.of(2024, 12, 31) to 0.0))

        val result =
            PortfolioMomentumEngine.run(
                bars(200, List(3) { _ -> { _: Int -> exp(2.4) } }),
                specs,
                params().copy(momentumWeight = 0.0, carryWeight = 1.0),
                funding = funding,
            )

        val longTickers = result.trades.filter { it.side == "LONG" }.map { it.ticker }
        val shortTickers = result.trades.filter { it.side == "SHORT" }.map { it.ticker }
        assertEquals(setOf("RECEIVER"), longTickers.toSet(), "лонг получает funding")
        assertEquals(setOf("PAYER"), shortTickers.toSet(), "шорт платит funding")
    }

    @Test
    fun `zero notional ticker is excluded from the carry cross section`() {
        val broken =
            bars(
                200,
                closes =
                    listOf(
                        { i: Int -> exp(2.4 + 0.0008 * i) },
                        { i: Int -> exp(2.4 - 0.0008 * i) },
                        { _: Int -> 0.0 },
                    ),
            )

        val result =
            PortfolioMomentumEngine.run(
                broken,
                listOf(spec("UP"), spec("DOWN"), spec("ZERO")),
                params().copy(minUniverse = 2, longCount = 1, shortCount = 1),
                funding = listOf(emptyMap(), emptyMap(), emptyMap()),
            )

        assertTrue(result.trades.isNotEmpty(), "два валидных инструмента достаточно")
        assertTrue(result.trades.none { it.ticker == "ZERO" }, "нулевой нотионал не торгуется")
    }

    @Test
    fun `opening leg with a non positive price uses a single contract`() {
        // Цена открытия для победителя нулевая, но закрытия растут (сигнал валиден).
        val oddOpens: List<(Int) -> Double> =
            listOf(
                { _: Int -> 0.0 },
                { i: Int -> exp(2.4 - 0.0008 * i) },
                { _: Int -> exp(2.4) },
            )
        val closes: List<(Int) -> Double> =
            listOf(
                { i: Int -> exp(2.4 + 0.0008 * i) },
                { i: Int -> exp(2.4 - 0.0008 * i) },
                { _: Int -> exp(2.4) },
            )

        val result =
            PortfolioMomentumEngine.run(
                bars(200, closes = closes, opens = oddOpens),
                listOf(spec("UP"), spec("DOWN"), spec("FLAT")),
                params(),
            )

        val upLegs = result.trades.filter { it.ticker == "UP" }
        assertTrue(upLegs.isNotEmpty())
        assertTrue(upLegs.all { it.qty == 1 }, "нулевой нотионал лота — минимум один контракт")
    }

    @Test
    fun `vol scaling falls back to equal weights on flat prices`() {
        // Цены не меняются → σ = 0 (вырождена) → risk-parity непригоден.
        val flat = bars(200, List(3) { k -> { _: Int -> exp(2.0 + k * 0.5) } })

        val result =
            PortfolioMomentumEngine.run(
                flat,
                listOf(spec("A"), spec("B"), spec("C")),
                params().copy(momentumWeight = 0.0, carryWeight = 1.0, volScalingEnabled = true),
                funding = listOf(emptyMap(), emptyMap(), emptyMap()),
            )

        assertTrue(result.trades.isEmpty(), "carry одинаков по всем ногам (spec-fallback 0) — дисперсии нет")
    }

    @Test
    fun `vol scaling with a one bar window falls back to equal notionals`() {
        val result =
            PortfolioMomentumEngine.run(
                trending(200),
                listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params().copy(volScalingEnabled = true, volLookbackBars = 1),
            )

        assertTrue(result.trades.isNotEmpty())
        val firstLong = result.trades.first { it.side == "LONG" }
        val firstShort = result.trades.first { it.side == "SHORT" }
        val longNotional = firstLong.entryPrice * firstLong.qty
        val shortNotional = firstShort.entryPrice * firstShort.qty
        assertTrue(
            abs(longNotional - shortNotional) <= maxOf(longNotional, shortNotional),
            "равные доли при недоступной волатильности ($longNotional vs $shortNotional)",
        )
    }

    @Test
    fun `zero capital slice still opens one contract legs`() {
        val result =
            PortfolioMomentumEngine.run(
                trending(200),
                listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params().copy(capitalSlice = 0.0, volTargetEnabled = true),
            )

        assertTrue(result.trades.isNotEmpty())
        assertTrue(result.trades.all { it.qty == 1 }, "нулевая книга → минимальный размер (1 контракт)")
    }

    @Test
    fun `vol targeting stays inactive without a volatility window`() {
        val result =
            PortfolioMomentumEngine.run(
                trending(200),
                listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params().copy(volTargetEnabled = true, volLookbackBars = 1),
            )

        assertTrue(result.trades.isNotEmpty(), "нехватка окна — экспозиция не масштабируется")
    }

    @Test
    fun `open book is liquidated at the last bar`() {
        val result =
            PortfolioMomentumEngine.run(
                trending(140),
                listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params(),
            )

        assertTrue(result.trades.any { it.closeReason == "END_OF_PERIOD" }, "книга закрывается на последнем баре")
        val expectedCash = 100_000.0 + result.trades.sumOf { it.netPnl }
        assertEquals(expectedCash, result.equityCurve.last(), 1e-6, "equity последнего бара — после ликвидации")
        assertTrue(result.trades.count { it.closeReason == "REBALANCE" } > 0)
    }
}
