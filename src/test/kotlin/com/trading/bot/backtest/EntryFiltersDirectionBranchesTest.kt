package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Непокрытые ветви research-фильтров [EntryFilters] (скан-19: 61 из 266):
 * undefined-состояния с обеими сторонами `blockOnUnknown` (fail-closed / pass-through),
 * границы ORB-окна (нулевой диапазон, незакрытое окно), ATR- и σ-режимы VWAP-MR,
 * ветка «сжатие было» по предрассчитанному ряду и без него, нулевой lookback squeeze,
 * паника без session-drop, макро-тренд вниз/откат за пределы, а также
 * вырожденные входы (пустое окно, нулевые периоды, нулевой объём).
 */
class EntryFiltersDirectionBranchesTest {
    private fun filter(overrides: EntryFilterOverrides): EntryFilters = EntryFilters.from(BacktestConfig(), overrides)

    private fun bar(
        idx: Int,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long = 1_000,
        hour: Int = 10,
    ): Candle =
        Candle(
            ticker = "TEST",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(open),
            highPrice = BigDecimal.valueOf(high),
            lowPrice = BigDecimal.valueOf(low),
            closePrice = BigDecimal.valueOf(close),
            volume = volume,
            time = LocalDateTime.of(LocalDate.of(2026, 10, 1), LocalTime.of(hour, 0)).plusMinutes(idx * 10L),
        )

    private fun series(
        count: Int,
        start: Double = 100.0,
        step: Double = 0.1,
    ): List<Candle> =
        (0 until count).map { i ->
            val p = start + step * i
            bar(i, p, p + 0.5, p - 0.5, p)
        }

    /** Плоская база + импульс на последнем баре (единственный способ получить пробой BB). */
    private fun flatThen(lastClose: Double): List<Candle> {
        val flat = (0 until 23).map { i -> bar(i, 100.0, 100.0, 100.0, 100.0) }
        return flat + bar(23, 100.0, maxOf(100.0, lastClose), minOf(100.0, lastClose), lastClose)
    }

    private fun closes(
        start: Double,
        step: Double,
        count: Int,
    ): List<BigDecimal> = (0 until count).map { i -> BigDecimal.valueOf(start + step * i) }

    // ------------------------------------------------------------- blocksEntry

    @Test
    fun `pullback with too few bars honours blockOnUnknown`() {
        val short = listOf(BigDecimal("100"), BigDecimal("101"))
        val blockOnUnknown = true

        val blocking =
            filter(
                EntryFilterOverrides(
                    pullbackFilterEnabled = true,
                    pullbackEmaPeriod = 20,
                    pullbackBlockOnUnknown = blockOnUnknown,
                ),
            )
        assertTrue(blocking.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), short))

        val passing =
            filter(
                EntryFilterOverrides(
                    pullbackFilterEnabled = true,
                    pullbackEmaPeriod = 20,
                    pullbackBlockOnUnknown = false,
                ),
            )
        assertFalse(passing.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), short))
    }

    @Test
    fun `non positive ema blocks the pullback entry`() {
        val negative = List(25) { BigDecimal.valueOf(-100.0) }
        val f =
            filter(
                EntryFilterOverrides(
                    pullbackFilterEnabled = true,
                    pullbackEmaPeriod = 20,
                    pullbackBlockOnUnknown = false,
                ),
            )

        assertTrue(f.blocksEntry(LocalTime.of(10, 0), BigDecimal("-100"), negative))
    }

    // ----------------------------------------------------------- orbDirection

    @Test
    fun `orb ignores the first bar and bars before the window start`() {
        val f = filter(EntryFilterOverrides(orbEnabled = true, orbWindowBars = 2, orbBlockOnUnknown = true))
        val candles = series(10)

        assertNull(f.orbDirection(candles, 0), "индекс 0 — истории дня нет")

        val late = filter(EntryFilterOverrides(orbEnabled = true, orbWindowBars = 2, orbWindowStartMinutes = 660))
        assertNull(late.orbDirection(candles, 5), "бар до начала окна не фильтруется")
    }

    @Test
    fun `orb with an unfinished range returns hold or null by block flag`() {
        val candles = series(3)

        val blocking = filter(EntryFilterOverrides(orbEnabled = true, orbWindowBars = 10, orbBlockOnUnknown = true))
        assertEquals(StrategyAction.HOLD, blocking.orbDirection(candles, 2))

        val passing = filter(EntryFilterOverrides(orbEnabled = true, orbWindowBars = 10, orbBlockOnUnknown = false))
        assertNull(passing.orbDirection(candles, 2))
    }

    @Test
    fun `orb with an empty range window returns hold or null`() {
        val candles = series(5)

        val blocking = filter(EntryFilterOverrides(orbEnabled = true, orbWindowBars = 0, orbBlockOnUnknown = true))
        assertEquals(StrategyAction.HOLD, blocking.orbDirection(candles, 4))

        val passing = filter(EntryFilterOverrides(orbEnabled = true, orbWindowBars = 0, orbBlockOnUnknown = false))
        assertNull(passing.orbDirection(candles, 4))
    }

    // ------------------------------------------------------ macroTrendDirection

    @Test
    fun `macro trend passes through without base closes`() {
        val f = filter(EntryFilterOverrides(macroTrendEnabled = true))

        assertNull(f.macroTrendDirection(closes(100.0, 1.0, 80), emptyList()))
    }

    @Test
    fun `macro trend undefined history honours block flag`() {
        val block = filter(EntryFilterOverrides(macroTrendEnabled = true, macroTrendBlockOnUnknown = true))
        assertEquals(
            StrategyAction.HOLD,
            block.macroTrendDirection(closes(100.0, 1.0, 30), closes(100.0, 0.1, 60)),
            "старшего ТФ не хватает",
        )

        val pass = filter(EntryFilterOverrides(macroTrendEnabled = true, macroTrendBlockOnUnknown = false))
        assertNull(pass.macroTrendDirection(closes(100.0, 1.0, 30), closes(100.0, 0.1, 60)))
    }

    @Test
    fun `macro trend blocks when the base history is shorter than the pullback ema`() {
        val f = filter(EntryFilterOverrides(macroTrendEnabled = true, macroTrendBlockOnUnknown = true))

        assertEquals(StrategyAction.HOLD, f.macroTrendDirection(closes(100.0, 1.0, 80), closes(100.0, 0.1, 5)))
    }

    @Test
    fun `macro trend blocks a downward higher timeframe`() {
        val f = filter(EntryFilterOverrides(macroTrendEnabled = true, macroTrendBlockOnUnknown = true))

        assertEquals(StrategyAction.HOLD, f.macroTrendDirection(closes(200.0, -1.0, 80), closes(100.0, 0.0, 60)))
    }

    @Test
    fun `macro trend treats a non positive base ema as unknown`() {
        val f = filter(EntryFilterOverrides(macroTrendEnabled = true, macroTrendBlockOnUnknown = true))

        assertEquals(
            StrategyAction.HOLD,
            f.macroTrendDirection(closes(100.0, 1.0, 80), closes(-100.0, 0.0, 60)),
            "EMA ≤ 0 — данные непригодны",
        )
    }

    @Test
    fun `macro trend blocks a pullback outside the tolerance`() {
        val f = filter(EntryFilterOverrides(macroTrendEnabled = true, macroTrendBlockOnUnknown = true))
        val base = closes(100.0, 0.0, 59) + BigDecimal("120")

        assertEquals(StrategyAction.HOLD, f.macroTrendDirection(closes(100.0, 1.0, 80), base))
    }

    // ------------------------------------------------------ rangeSqueezeDirection

    @Test
    fun `range squeeze undefined history honours block flag`() {
        val block =
            filter(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 5,
                    rangeSqueezeAtrPeriod = 5,
                ),
            )
        assertEquals(StrategyAction.HOLD, block.rangeSqueezeDirection(series(3)))

        val pass =
            filter(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 5,
                    rangeSqueezeAtrPeriod = 5,
                    rangeSqueezeBlockOnUnknown = false,
                ),
            )
        assertNull(pass.rangeSqueezeDirection(series(3)))
    }

    private fun EntryFilterOverrides.rangeSqueezeFrame(
        rangePeriod: Int,
        atrPeriod: Int,
    ): EntryFilterOverrides = copy(rangeSqueezeRangePeriod = rangePeriod, rangeSqueezeAtrPeriod = atrPeriod)

    @Test
    fun `range squeeze without a squeeze does not filter`() {
        val f =
            filter(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 15,
                    rangeSqueezeAtrPeriod = 2,
                ),
            )

        assertNull(f.rangeSqueezeDirection(series(20)), "сжатия не было — фильтр не вмешивается")
    }

    @Test
    fun `range squeeze ignores states outside the lookback`() {
        val f =
            filter(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 15,
                    rangeSqueezeAtrPeriod = 2,
                    rangeSqueezeLookbackBars = 1,
                ),
            )
        val states = BooleanArray(20).also { it[5] = true }

        assertNull(f.rangeSqueezeDirection(series(20), states), "сжатие было слишком давно")
    }

    @Test
    fun `range squeeze ignores a series shorter than the window`() {
        val f =
            filter(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 15,
                    rangeSqueezeAtrPeriod = 2,
                    rangeSqueezeLookbackBars = 0,
                    rangeSqueezeRequireVolume = false,
                ),
            )
        val shortSeries = BooleanArray(5).also { it[2] = true }

        // Ряд короче окна непригоден — состояние пересчитывается по свечам (сжатия нет).
        assertNull(f.rangeSqueezeDirection(series(20, step = 0.0), shortSeries))
    }

    @Test
    fun `range squeeze resolves the breakout side from the precomputed states`() {
        val f =
            filter(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 15,
                    rangeSqueezeAtrPeriod = 2,
                    rangeSqueezeLookbackBars = 0,
                    rangeSqueezeRequireVolume = false,
                ),
            )
        val flat = series(19, step = 0.0)
        val states = BooleanArray(20).also { it[18] = true }

        assertEquals(StrategyAction.BUY, f.rangeSqueezeDirection(flat + bar(19, 100.0, 105.0, 100.0, 105.0), states))
        assertEquals(StrategyAction.SELL, f.rangeSqueezeDirection(flat + bar(19, 100.0, 100.0, 95.0, 95.0), states))
        assertEquals(StrategyAction.HOLD, f.rangeSqueezeDirection(flat + bar(19, 100.0, 100.0, 100.0, 100.0), states))
    }

    @Test
    fun `range squeeze requires volume confirmation when enabled`() {
        val f =
            filter(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 15,
                    rangeSqueezeAtrPeriod = 2,
                    rangeSqueezeLookbackBars = 0,
                    rangeSqueezeRequireVolume = true,
                    volumeSpikePeriod = 2,
                    volumeSpikeMultiplier = 1.5,
                ),
            )
        val flat = series(19, step = 0.0)
        val states = BooleanArray(20).also { it[18] = true }
        val breakout = flat + bar(19, 100.0, 105.0, 100.0, 105.0, volume = 1_000)

        assertEquals(StrategyAction.HOLD, f.rangeSqueezeDirection(breakout, states), "объём не подтвердил пробой")
    }

    @Test
    fun `range squeeze without volume history is unknown`() {
        val f =
            filter(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 15,
                    rangeSqueezeAtrPeriod = 2,
                    rangeSqueezeLookbackBars = 0,
                    rangeSqueezeRequireVolume = true,
                    volumeSpikePeriod = 500,
                ),
            )
        val flat = series(19, step = 0.0)
        val states = BooleanArray(20).also { it[18] = true }

        assertEquals(
            StrategyAction.HOLD,
            f.rangeSqueezeDirection(flat + bar(19, 100.0, 105.0, 100.0, 105.0), states),
        )
    }

    @Test
    fun `range squeeze recomputes states when no series is supplied`() {
        // lookback больше окна: перебор доходит до индексов, где истории не хватает.
        val f =
            filter(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 2,
                    rangeSqueezeAtrPeriod = 1,
                    rangeSqueezeLookbackBars = 100,
                ),
            )

        assertNull(f.rangeSqueezeDirection(series(3)))
    }

    // ------------------------------------------------------- squeezeDirection

    @Test
    fun `squeeze breakout with zero lookback skips the history check`() {
        val f = filter(EntryFilterOverrides(squeezeEnabled = true, squeezeLookbackBars = 0))

        assertEquals(StrategyAction.BUY, f.squeezeDirection(flatThen(105.0)))
        assertEquals(StrategyAction.SELL, f.squeezeDirection(flatThen(95.0)))
    }

    @Test
    fun `squeeze series branch requires a squeeze inside the lookback`() {
        val f =
            filter(
                EntryFilterOverrides(
                    squeezeEnabled = true,
                    squeezeLookbackBars = 5,
                    squeezeBlockOnUnknown = true,
                ),
            )
        val window = flatThen(105.0)
        val last = window.size - 1

        val noSqueeze = BooleanArray(window.size)
        assertNull(f.squeezeDirection(window, noSqueeze, last), "сжатия перед пробоем не было")

        val squeezeBefore = BooleanArray(window.size).also { it[last - 2] = true }
        assertEquals(StrategyAction.BUY, f.squeezeDirection(window, squeezeBefore, last))
    }

    @Test
    fun `squeeze falls back to live evaluation when the series index is too early`() {
        val f = filter(EntryFilterOverrides(squeezeEnabled = true, squeezeLookbackBars = 5))
        // Достаточно истории и для isSqueeze, и для пересчёта «было ли сжатие» (без ряда).
        val window = (0 until 30).map { i -> bar(i, 100.0, 100.0, 100.0, 100.0) } + bar(30, 100.0, 105.0, 100.0, 105.0)

        val series = BooleanArray(window.size)
        assertEquals(
            f.squeezeDirection(window),
            f.squeezeDirection(window, series, 2),
            "ряд игнорируется: индекс меньше минимума — считается isSqueeze на окне",
        )
    }

    @Test
    fun `squeeze with too little history honours block flag`() {
        val blocking = filter(EntryFilterOverrides(squeezeEnabled = true, squeezeBlockOnUnknown = true))
        assertEquals(StrategyAction.HOLD, blocking.squeezeDirection(series(3)))

        val passing = filter(EntryFilterOverrides(squeezeEnabled = true, squeezeBlockOnUnknown = false))
        assertNull(passing.squeezeDirection(series(3)))
    }

    // -------------------------------------------------------- panicReversal

    @Test
    fun `panic reversal ignores an empty session`() {
        val f = filter(EntryFilterOverrides(panicReversalEnabled = true))

        assertNull(f.panicReversalDirection(emptyList(), closes(100.0, -1.0, 40)))
    }

    @Test
    fun `panic reversal treats a non positive open as unknown`() {
        val zeroOpen = List(25) { i -> bar(i, 0.0, 100.0, 99.0, 100.0) }

        val blocking = filter(EntryFilterOverrides(panicReversalEnabled = true, panicBlockOnUnknown = true))
        assertEquals(StrategyAction.HOLD, blocking.panicReversalDirection(zeroOpen, closes(100.0, -1.0, 40)))

        val passing = filter(EntryFilterOverrides(panicReversalEnabled = true, panicBlockOnUnknown = false))
        assertNull(passing.panicReversalDirection(zeroOpen, closes(100.0, -1.0, 40)))
    }

    @Test
    fun `panic reversal unknown session length and rsi history block the entry`() {
        val f = filter(EntryFilterOverrides(panicReversalEnabled = true, panicBlockOnUnknown = true))
        val session = series(5)

        assertEquals(StrategyAction.HOLD, f.panicReversalDirection(session, closes(100.0, -1.0, 40)))
        assertEquals(
            StrategyAction.HOLD,
            f.panicReversalDirection(series(25), closes(100.0, -1.0, 5)),
            "истории RSI не хватает",
        )
    }

    @Test
    fun `panic reversal without session drop gate buys on oversold higher timeframe`() {
        val f =
            filter(
                EntryFilterOverrides(
                    panicReversalEnabled = true,
                    panicUseSessionDrop = false,
                    panicRequireBullishBar = false,
                ),
            )
        val flatSession = (0 until 25).map { i -> bar(i, 100.0, 100.0, 100.0, 100.0) }

        assertEquals(StrategyAction.BUY, f.panicReversalDirection(flatSession, closes(200.0, -2.0, 40)))
    }

    // -------------------------------------------------------------- vwapMr

    @Test
    fun `vwap mean reversion ignores an empty window`() {
        val f = filter(EntryFilterOverrides(vwapMrEnabled = true))

        assertNull(f.vwapMrDirection(emptyList(), emptyList()))
    }

    @Test
    fun `vwap mean reversion unknown session length honours block flag`() {
        val session = series(3, step = 0.0)

        val blocking = filter(EntryFilterOverrides(vwapMrEnabled = true, vwapMrMinSessionBars = 10))
        assertEquals(StrategyAction.HOLD, blocking.vwapMrDirection(session, series(40)))

        val passing =
            filter(EntryFilterOverrides(vwapMrEnabled = true, vwapMrMinSessionBars = 10, vwapMrBlockOnUnknown = false))
        assertNull(passing.vwapMrDirection(session, series(40)))
    }

    @Test
    fun `vwap mean reversion atr mode treats a flat range as unknown`() {
        val flat = (0 until 10).map { i -> bar(i, 100.0, 100.0, 100.0, 100.0) }
        val f =
            filter(
                EntryFilterOverrides(
                    vwapMrEnabled = true,
                    vwapMrMinSessionBars = 2,
                    vwapMrDeviationAtr = 1.0,
                    vwapMrAtrPeriod = 5,
                ),
            )

        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(flat, series(40)))
    }

    @Test
    fun `vwap mean reversion with a zero sigma threshold is unknown`() {
        val f =
            filter(
                EntryFilterOverrides(
                    vwapMrEnabled = true,
                    vwapMrMinSessionBars = 2,
                    vwapMrDeviationSigma = 0.0,
                ),
            )

        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(series(10), series(40)))
    }

    // ------------------------------------------------------------- emaCross

    @Test
    fun `ema cross without enough bars honours block flag`() {
        val blocking = filter(EntryFilterOverrides(emaCrossEnabled = true, emaCrossBlockOnUnknown = true))
        assertEquals(StrategyAction.HOLD, blocking.emaCrossDirection(emptyList()))
        assertEquals(StrategyAction.HOLD, blocking.emaCrossDirection(series(1)))

        val passing = filter(EntryFilterOverrides(emaCrossEnabled = true, emaCrossBlockOnUnknown = false))
        assertNull(passing.emaCrossDirection(emptyList()))
    }

    @Test
    fun `ema cross with a degenerate period is unknown`() {
        val fastZero = filter(EntryFilterOverrides(emaCrossEnabled = true, emaCrossFastPeriod = 0))
        assertEquals(StrategyAction.HOLD, fastZero.emaCrossDirection(series(60)))

        val slowZero =
            filter(EntryFilterOverrides(emaCrossEnabled = true, emaCrossFastPeriod = 1, emaCrossSlowPeriod = 0))
        assertEquals(StrategyAction.HOLD, slowZero.emaCrossDirection(series(60)))
    }

    @Test
    fun `volume spike without volume history is unknown`() {
        val f = filter(EntryFilterOverrides(volumeSpikeEnabled = true, volumeSpikePeriod = 500))

        assertEquals(
            StrategyAction.HOLD,
            f.volumeSpikeAllows(series(10)),
            "SMA объёма не определена — fail-closed",
        )
    }

    // ---------------------------------------------------------- volumeSpike

    @Test
    fun `volume spike treats zero volume as unknown`() {
        val zeroVolume = (0 until 30).map { i -> bar(i, 100.0, 100.0, 100.0, 100.0, volume = 0) }
        val f = filter(EntryFilterOverrides(volumeSpikeEnabled = true))

        assertEquals(StrategyAction.HOLD, f.volumeSpikeAllows(zeroVolume))
    }
}
