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
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Направляющие ветви реального [EntryFilters] (не моки): fail-closed
 * «unknown» на нехватке истории, вырожденных σ/ATR/EMA, узкое окно ORB,
 * сессионный гейт, объёмное подтверждение, squeeze/range-squeeze,
 * panic-reversal, macro-trend и time×direction.
 *
 * Основной тест генератора мокает фильтры, поэтому эти ветви не исполнялись
 * (Kover: EntryFilters 35 missed, LiveStrategyBacktestSignalGenerator — «unknown»
 * ранние return'ы).
 */
class EntryFiltersDirectionTest {
    private val start = LocalDateTime.of(2026, 9, 1, 10, 0)

    private fun candle(
        close: Double,
        high: Double = close + 0.5,
        low: Double = close - 0.5,
        volume: Long = 1_000,
        minutes: Int = 0,
        open: Double = close,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(open),
            highPrice = BigDecimal.valueOf(high),
            lowPrice = BigDecimal.valueOf(low),
            closePrice = BigDecimal.valueOf(close),
            volume = volume,
            time = start.plusMinutes(minutes.toLong()),
        )

    private fun filters(overrides: EntryFilterOverrides): EntryFilters = EntryFilters.from(BacktestConfig(), overrides)

    private fun ramp(
        closes: List<Double>,
        volume: Long = 1_000,
    ): List<Candle> = closes.mapIndexed { i, close -> candle(close, volume = volume, minutes = 10 * i) }

    /** Завершённые бары старшего ТФ: тренд (или плоский ряд) для ADX-гейта VWAP-MR. */
    private fun higherCandles(
        count: Int = 30,
        flat: Boolean = false,
    ): List<Candle> =
        (0 until count).map { i ->
            val close = if (flat) 100.0 else 100.0 + i
            candle(close, high = close + 1.0, low = close - 1.0, minutes = 10 * i)
        }

    // ---------------------------------------------------------------- session / pullback

    @Test
    fun `session gate blocks bars outside the window`() {
        val f =
            filters(
                EntryFilterOverrides(
                    sessionFilterEnabled = true,
                    sessionFilterStartMinutes = 600,
                    sessionFilterEndMinutes = 1080,
                ),
            )

        assertTrue(f.blocksEntry(LocalTime.of(9, 0), BigDecimal("100"), emptyList()))
        assertTrue(f.blocksEntry(LocalTime.of(19, 0), BigDecimal("100"), emptyList()))
        assertFalse(f.blocksEntry(LocalTime.of(12, 0), BigDecimal("100"), emptyList()))
    }

    @Test
    fun `pullback gate fails closed on short history and zero ema`() {
        val failClosed =
            filters(
                EntryFilterOverrides(
                    pullbackFilterEnabled = true,
                    pullbackEmaPeriod = 3,
                    pullbackMaxDeviationPercent = 1.0,
                    pullbackBlockOnUnknown = true,
                ),
            )
        assertTrue(failClosed.blocksEntry(LocalTime.NOON, BigDecimal("100"), emptyList()))

        val loose =
            filters(
                EntryFilterOverrides(
                    pullbackFilterEnabled = true,
                    pullbackEmaPeriod = 3,
                    pullbackMaxDeviationPercent = 1.0,
                    pullbackBlockOnUnknown = false,
                ),
            )
        assertFalse(loose.blocksEntry(LocalTime.NOON, BigDecimal("100"), emptyList()))
        // EMA нулевого ряда = 0 → вырожденный расчёт, вход блокируется.
        assertTrue(loose.blocksEntry(LocalTime.NOON, BigDecimal("100"), List(3) { BigDecimal.ZERO }))
    }

    @Test
    fun `pullback gate blocks deviation beyond the band and passes inside`() {
        val f =
            filters(
                EntryFilterOverrides(
                    pullbackFilterEnabled = true,
                    pullbackEmaPeriod = 3,
                    pullbackMaxDeviationPercent = 1.0,
                    pullbackBlockOnUnknown = false,
                ),
            )
        val closes = List(3) { BigDecimal("100") }

        assertTrue(f.blocksEntry(LocalTime.NOON, BigDecimal("105"), closes))
        assertFalse(f.blocksEntry(LocalTime.NOON, BigDecimal("100.5"), closes))
    }

    // --------------------------------------------------------------------------- ORB

    @Test
    fun `orb range still forming blocks only in fail closed mode`() {
        val candles = ramp(listOf(100.0, 101.0))
        val strict =
            filters(
                EntryFilterOverrides(
                    orbEnabled = true,
                    orbWindowBars = 3,
                    orbStrictBreakout = true,
                    orbBlockOnUnknown = true,
                    orbWindowStartMinutes = 0,
                    orbWindowEndMinutes = 1440,
                ),
            )
        val loose =
            filters(
                EntryFilterOverrides(
                    orbEnabled = true,
                    orbWindowBars = 3,
                    orbStrictBreakout = true,
                    orbBlockOnUnknown = false,
                    orbWindowStartMinutes = 0,
                    orbWindowEndMinutes = 1440,
                ),
            )

        assertEquals(StrategyAction.HOLD, strict.orbDirection(candles, 1))
        assertNull(loose.orbDirection(candles, 1))
    }

    @Test
    fun `narrowed orb window blocks bars before the window closes`() {
        val f =
            filters(
                EntryFilterOverrides(
                    orbEnabled = true,
                    orbWindowBars = 1,
                    orbStrictBreakout = true,
                    orbBlockOnUnknown = true,
                    orbWindowStartMinutes = 0,
                    orbWindowEndMinutes = 620,
                ),
            )
        // 10:00 → 10:10 (610 мин < 620) — окно ещё не закрыто.
        val candles =
            listOf(
                candle(100.0, minutes = 0),
                candle(101.0, minutes = 10),
            )

        assertEquals(StrategyAction.HOLD, f.orbDirection(candles, 1))
    }

    @Test
    fun `orb breakout allows the broken side and blocks inside the range`() {
        val strict =
            filters(
                EntryFilterOverrides(
                    orbEnabled = true,
                    orbWindowBars = 1,
                    orbStrictBreakout = true,
                    orbBlockOnUnknown = true,
                    orbWindowStartMinutes = 0,
                    orbWindowEndMinutes = 1440,
                ),
            )
        val loose =
            filters(
                EntryFilterOverrides(
                    orbEnabled = true,
                    orbWindowBars = 1,
                    orbStrictBreakout = false,
                    orbBlockOnUnknown = true,
                    orbWindowStartMinutes = 0,
                    orbWindowEndMinutes = 1440,
                ),
            )

        val up = listOf(candle(100.0, high = 101.0, low = 99.0, minutes = 0), candle(102.0, minutes = 10))
        val down = listOf(candle(100.0, high = 101.0, low = 99.0, minutes = 0), candle(98.0, minutes = 10))
        val inside = listOf(candle(100.0, high = 101.0, low = 99.0, minutes = 0), candle(100.2, minutes = 10))

        assertEquals(StrategyAction.BUY, strict.orbDirection(up, 1))
        assertEquals(StrategyAction.SELL, strict.orbDirection(down, 1))
        assertEquals(StrategyAction.HOLD, strict.orbDirection(inside, 1))
        assertNull(loose.orbDirection(inside, 1))
        assertNull(strict.orbDirection(up, 0), "index 0 — диапазон не определён, фильтр не применяется")
    }

    // ------------------------------------------------------------------------- VWAP-MR

    @Test
    fun `vwap mr returns null when disabled or window is empty`() {
        assertNull(filters(EntryFilterOverrides()).vwapMrDirection(emptyList(), emptyList()))
        assertNull(
            filters(EntryFilterOverrides(vwapMrEnabled = false)).vwapMrDirection(ramp(listOf(100.0)), emptyList()),
        )
    }

    @Test
    fun `vwap mr fails closed while the session or higher timeframe is short`() {
        val f =
            filters(
                EntryFilterOverrides(
                    vwapMrEnabled = true,
                    vwapMrMinSessionBars = 5,
                    vwapMrBlockOnUnknown = true,
                ),
            )

        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(ramp(listOf(100.0, 101.0)), emptyList()))
    }

    @Test
    fun `vwap mr blocks a strong trend above the adx gate`() {
        val f =
            filters(
                EntryFilterOverrides(
                    vwapMrEnabled = true,
                    vwapMrMinSessionBars = 1,
                    vwapMrMaxAdx = -1.0,
                    vwapMrBlockOnUnknown = true,
                ),
            )
        val higher = higherCandles()

        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(ramp(listOf(100.0)), higher))
    }

    @Test
    fun `vwap mr atr mode fails closed when atr is zero`() {
        val f =
            filters(
                EntryFilterOverrides(
                    vwapMrEnabled = true,
                    vwapMrMinSessionBars = 1,
                    vwapMrMaxAdx = 100.0,
                    vwapMrDeviationAtr = 1.0,
                    vwapMrAtrPeriod = 2,
                    vwapMrBlockOnUnknown = true,
                ),
            )
        val flat = List(5) { candle(100.0, high = 100.0, low = 100.0, minutes = 10 * it) }
        val higher = higherCandles(flat = true)

        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(flat, higher))
    }

    @Test
    fun `vwap mr signals both sides in atr mode and holds near vwap`() {
        val f =
            filters(
                EntryFilterOverrides(
                    vwapMrEnabled = true,
                    vwapMrMinSessionBars = 1,
                    vwapMrMaxAdx = 100.0,
                    vwapMrDeviationAtr = 1.0,
                    vwapMrAtrPeriod = 2,
                    vwapMrBlockOnUnknown = true,
                ),
            )
        val higher = higherCandles(flat = true)
        val base = List(4) { candle(100.0, high = 101.0, low = 99.0, minutes = 10 * it) }

        assertEquals(
            StrategyAction.BUY,
            f.vwapMrDirection(base + candle(90.0, high = 91.0, low = 89.0, minutes = 40), higher),
        )
        assertEquals(
            StrategyAction.SELL,
            f.vwapMrDirection(base + candle(110.0, high = 111.0, low = 109.0, minutes = 40), higher),
        )
        assertEquals(
            StrategyAction.HOLD,
            f.vwapMrDirection(base + candle(100.1, high = 100.6, low = 99.6, minutes = 40), higher),
        )
    }

    @Test
    fun `vwap mr sigma mode fails closed on degenerate sigma and zero threshold`() {
        val higher = higherCandles(flat = true)
        val flatSigma =
            filters(
                EntryFilterOverrides(
                    vwapMrEnabled = true,
                    vwapMrMinSessionBars = 1,
                    vwapMrMaxAdx = 100.0,
                    vwapMrDeviationSigma = 2.0,
                    vwapMrBlockOnUnknown = true,
                ),
            )
        val flat = List(5) { candle(100.0, high = 100.0, low = 100.0, minutes = 10 * it) }
        assertEquals(StrategyAction.HOLD, flatSigma.vwapMrDirection(flat, higher))

        val zeroThreshold =
            filters(
                EntryFilterOverrides(
                    vwapMrEnabled = true,
                    vwapMrMinSessionBars = 1,
                    vwapMrMaxAdx = 100.0,
                    vwapMrDeviationSigma = 0.0,
                    vwapMrBlockOnUnknown = true,
                ),
            )
        val volatile = List(5) { candle(100.0 + it, high = 101.0 + it, low = 99.0 + it, minutes = 10 * it) }
        assertEquals(StrategyAction.HOLD, zeroThreshold.vwapMrDirection(volatile, higher))
    }

    // ----------------------------------------------------------------------- EMA cross

    @Test
    fun `ema cross requires history and fails closed`() {
        assertNull(filters(EntryFilterOverrides()).emaCrossDirection(ramp(listOf(100.0))))
        val f =
            filters(
                EntryFilterOverrides(
                    emaCrossEnabled = true,
                    emaCrossFastPeriod = 2,
                    emaCrossSlowPeriod = 3,
                    emaCrossBlockOnUnknown = true,
                ),
            )

        assertEquals(StrategyAction.HOLD, f.emaCrossDirection(ramp(listOf(100.0, 101.0))))
        val badPeriods =
            filters(
                EntryFilterOverrides(
                    emaCrossEnabled = true,
                    emaCrossFastPeriod = 0,
                    emaCrossSlowPeriod = 3,
                    emaCrossBlockOnUnknown = true,
                ),
            )
        assertEquals(StrategyAction.HOLD, badPeriods.emaCrossDirection(ramp(listOf(100.0, 101.0, 102.0))))
    }

    @Test
    fun `ema cross reports up down and no cross events`() {
        val f =
            filters(
                EntryFilterOverrides(
                    emaCrossEnabled = true,
                    emaCrossFastPeriod = 2,
                    emaCrossSlowPeriod = 4,
                    emaCrossBlockOnUnknown = true,
                ),
            )

        assertEquals(StrategyAction.BUY, f.emaCrossDirection(ramp(listOf(100.0, 99.0, 98.0, 97.0, 96.0, 130.0))))
        assertEquals(StrategyAction.SELL, f.emaCrossDirection(ramp(listOf(100.0, 101.0, 102.0, 103.0, 104.0, 70.0))))
        assertEquals(StrategyAction.HOLD, f.emaCrossDirection(ramp(listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0))))
    }

    // -------------------------------------------------------------------- volume spike

    @Test
    fun `volume spike fails closed without sma and on zero volume`() {
        assertNull(filters(EntryFilterOverrides()).volumeSpikeAllows(ramp(listOf(100.0))))
        val f =
            filters(
                EntryFilterOverrides(
                    volumeSpikeEnabled = true,
                    volumeSpikePeriod = 5,
                    volumeSpikeMultiplier = 2.0,
                    volumeSpikeBlockOnUnknown = true,
                ),
            )

        assertEquals(StrategyAction.HOLD, f.volumeSpikeAllows(ramp(listOf(100.0, 101.0))))
        // SMA > 0, но текущий объём нулевой → unknown.
        val zeroLast =
            listOf(
                candle(100.0, volume = 1_000, minutes = 0),
                candle(100.0, volume = 1_000, minutes = 10),
                candle(100.0, volume = 0, minutes = 20),
            )
        assertEquals(StrategyAction.HOLD, f.volumeSpikeAllows(zeroLast))
    }

    @Test
    fun `volume spike passes a real spike and blocks a normal bar`() {
        val f =
            filters(
                EntryFilterOverrides(
                    volumeSpikeEnabled = true,
                    volumeSpikePeriod = 5,
                    volumeSpikeMultiplier = 2.0,
                    volumeSpikeBlockOnUnknown = true,
                ),
            )
        val spike =
            listOf(
                candle(100.0, volume = 1_000, minutes = 0),
                candle(100.0, volume = 1_000, minutes = 10),
                candle(100.0, volume = 1_000, minutes = 20),
                candle(100.0, volume = 1_000, minutes = 30),
                candle(100.0, volume = 5_000, minutes = 40),
            )
        val normal =
            listOf(
                candle(100.0, volume = 1_000, minutes = 0),
                candle(100.0, volume = 1_000, minutes = 10),
                candle(100.0, volume = 1_000, minutes = 20),
                candle(100.0, volume = 1_000, minutes = 30),
                candle(100.0, volume = 1_500, minutes = 40),
            )

        assertNull(f.volumeSpikeAllows(spike))
        assertEquals(StrategyAction.HOLD, f.volumeSpikeAllows(normal))
    }

    // ------------------------------------------------------------------ range squeeze

    @Test
    fun `range squeeze fails closed without enough history`() {
        assertNull(filters(EntryFilterOverrides()).rangeSqueezeDirection(ramp(listOf(100.0))))
        val f =
            filters(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 2,
                    rangeSqueezeAtrPeriod = 2,
                    rangeSqueezeBlockOnUnknown = true,
                ),
            )

        assertEquals(StrategyAction.HOLD, f.rangeSqueezeDirection(ramp(listOf(100.0, 101.0, 102.0))))
    }

    @Test
    fun `range squeeze passes through when the market was not squeezed`() {
        val f =
            filters(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 2,
                    rangeSqueezeAtrPeriod = 2,
                    rangeSqueezeMultiplier = 0.1,
                    rangeSqueezeLookbackBars = 1,
                    rangeSqueezeRequireVolume = false,
                    rangeSqueezeBlockOnUnknown = true,
                ),
            )
        // Широкие бары: meanRange > 0.1 × ATR → сжатия не было.
        val wide =
            (0 until 6).map { i ->
                candle(100.0 + i, high = 105.0 + i, low = 95.0 + i, minutes = 10 * i)
            }

        assertNull(f.rangeSqueezeDirection(wide))
    }

    @Test
    fun `range squeeze signals a breakout after a squeeze using the cached series`() {
        val f =
            filters(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 2,
                    rangeSqueezeAtrPeriod = 2,
                    rangeSqueezeMultiplier = 5.0,
                    rangeSqueezeLookbackBars = 1,
                    rangeSqueezeRequireVolume = false,
                    rangeSqueezeBlockOnUnknown = true,
                ),
            )
        val candles =
            listOf(
                candle(100.0, high = 100.1, low = 99.9, minutes = 0),
                candle(100.0, high = 100.1, low = 99.9, minutes = 10),
                candle(100.0, high = 100.1, low = 99.9, minutes = 20),
                candle(101.0, high = 101.1, low = 100.9, minutes = 30),
            )
        // Состояние сжатия на предпоследнем баре (индекс 2) — кэш-ветка.
        val states = booleanArrayOf(false, false, true, false)

        assertEquals(StrategyAction.BUY, f.rangeSqueezeDirection(candles, states))
        val down = candles.dropLast(1) + candle(99.0, high = 99.1, low = 98.9, minutes = 30)
        assertEquals(StrategyAction.SELL, f.rangeSqueezeDirection(down, states))
        val inside = candles.dropLast(1) + candle(100.0, high = 100.3, low = 99.8, minutes = 30)
        assertEquals(StrategyAction.HOLD, f.rangeSqueezeDirection(inside, states))
    }

    @Test
    fun `range squeeze volume confirmation fails closed and blocks quiet breakouts`() {
        val f =
            filters(
                EntryFilterOverrides(
                    rangeSqueezeEnabled = true,
                    rangeSqueezeRangePeriod = 2,
                    rangeSqueezeAtrPeriod = 2,
                    rangeSqueezeMultiplier = 5.0,
                    rangeSqueezeLookbackBars = 1,
                    rangeSqueezeRequireVolume = true,
                    volumeSpikePeriod = 3,
                    volumeSpikeMultiplier = 2.0,
                    rangeSqueezeBlockOnUnknown = true,
                ),
            )
        val states = booleanArrayOf(false, false, true, false)
        val quiet =
            listOf(
                candle(100.0, high = 100.1, low = 99.9, volume = 1_000, minutes = 0),
                candle(100.0, high = 100.1, low = 99.9, volume = 1_000, minutes = 10),
                candle(100.0, high = 100.1, low = 99.9, volume = 1_000, minutes = 20),
                candle(101.0, high = 101.1, low = 100.9, volume = 1_100, minutes = 30),
            )
        assertEquals(StrategyAction.HOLD, f.rangeSqueezeDirection(quiet, states))

        // SMA объёма по окну до пробоя нулевая → unknown.
        val noVolume =
            listOf(
                candle(100.0, high = 100.1, low = 99.9, volume = 0, minutes = 0),
                candle(100.0, high = 100.1, low = 99.9, volume = 0, minutes = 10),
                candle(100.0, high = 100.1, low = 99.9, volume = 0, minutes = 20),
                candle(101.0, high = 101.1, low = 100.9, volume = 0, minutes = 30),
            )
        assertEquals(StrategyAction.HOLD, f.rangeSqueezeDirection(noVolume, states))
    }

    // ------------------------------------------------------------------- bollinger squeeze

    @Test
    fun `squeeze filter is inert when disabled and fails closed on short history`() {
        assertNull(filters(EntryFilterOverrides()).squeezeDirection(emptyList()))
        val strict =
            filters(EntryFilterOverrides(squeezeEnabled = true, squeezeBlockOnUnknown = true))
        assertEquals(StrategyAction.HOLD, strict.squeezeDirection(ramp(listOf(100.0, 101.0))))

        val loose = filters(EntryFilterOverrides(squeezeEnabled = true, squeezeBlockOnUnknown = false))
        assertNull(loose.squeezeDirection(ramp(listOf(100.0, 101.0))))
        assertNull(strict.squeezeDirection(emptyList()))
    }

    @Test
    fun `squeeze filter holds inside the squeeze and passes a breakout without prior squeeze`() {
        val strict =
            filters(EntryFilterOverrides(squeezeEnabled = true, squeezeBlockOnUnknown = true, squeezeLookbackBars = 1))
        // 25 плоских баров: полосы Боллинджера внутри Кельтнера → сжатие.
        val flat = (0 until 25).map { i -> candle(100.0, high = 100.05, low = 99.95, minutes = 10 * i) }
        assertEquals(StrategyAction.HOLD, strict.squeezeDirection(flat))

        // Резкий импульс без предшествующего сжатия → пробой без сетапа → null.
        val noSqueeze =
            (0 until 19).map { i -> candle(100.0 + i, high = 101.0 + i, low = 99.0 + i, minutes = 10 * i) } +
                candle(200.0, high = 201.0, low = 199.0, minutes = 190)
        assertNull(strict.squeezeDirection(noSqueeze))

        // Кэш-серия: сжатие было на текущем баре, затем пробой → BUY.
        val window =
            (0 until 21).map { i -> candle(100.0, high = 100.05, low = 99.95, minutes = 10 * i) } +
                candle(120.0, high = 121.0, low = 119.0, minutes = 210)
        val states = BooleanArray(window.size)
        states[window.size - 2] = true
        assertEquals(StrategyAction.BUY, strict.squeezeDirection(window, states, window.size - 1))
    }

    // ----------------------------------------------------------------- panic reversal

    @Test
    fun `panic reversal requires inputs and fails closed`() {
        assertNull(filters(EntryFilterOverrides()).panicReversalDirection(emptyList(), emptyList()))
        val f =
            filters(
                EntryFilterOverrides(
                    panicReversalEnabled = true,
                    panicMinBars = 3,
                    panicBlockOnUnknown = true,
                ),
            )

        assertEquals(StrategyAction.HOLD, f.panicReversalDirection(ramp(listOf(100.0, 101.0)), emptyList()))
        val zeroOpen = listOf(candle(100.0, open = 0.0, minutes = 0)) + ramp(listOf(100.0, 99.0))
        assertEquals(StrategyAction.HOLD, f.panicReversalDirection(zeroOpen, emptyList()))
        assertEquals(
            StrategyAction.HOLD,
            f.panicReversalDirection(ramp(listOf(100.0, 99.0, 98.0)), List(3) { BigDecimal(100) }),
        )
    }

    @Test
    fun `panic reversal blocks non bullish bars and insufficient drops`() {
        val f =
            filters(
                EntryFilterOverrides(
                    panicReversalEnabled = true,
                    panicMinBars = 2,
                    panicRsiPeriod = 2,
                    panicMaxRsi = 30.0,
                    panicRequireBullishBar = true,
                    panicUseSessionDrop = true,
                    panicMinSessionDropPercent = 3.0,
                    panicBlockOnUnknown = true,
                ),
            )
        val falling = (0 until 10).map { BigDecimal(100 - it) }

        // Медвежий бар: close <= open.
        val bearish =
            listOf(
                candle(100.0, high = 100.0, low = 96.0, open = 100.0, minutes = 0),
                candle(96.0, high = 96.0, low = 95.0, open = 97.0, minutes = 10),
            )
        assertEquals(StrategyAction.HOLD, f.panicReversalDirection(bearish, falling))

        // Отскок есть, но просадка всего 1% (< 3%).
        val shallow =
            listOf(
                candle(100.0, high = 100.0, low = 99.0, open = 100.0, minutes = 0),
                candle(99.0, high = 99.5, low = 98.5, open = 98.8, minutes = 10),
            )
        assertEquals(StrategyAction.HOLD, f.panicReversalDirection(shallow, falling))
    }

    @Test
    fun `panic reversal buys after a deep drop with oversold higher timeframe rsi`() {
        val f =
            filters(
                EntryFilterOverrides(
                    panicReversalEnabled = true,
                    panicMinBars = 2,
                    panicRsiPeriod = 2,
                    panicMaxRsi = 30.0,
                    panicRequireBullishBar = true,
                    panicUseSessionDrop = true,
                    panicMinSessionDropPercent = 3.0,
                    panicBlockOnUnknown = false,
                ),
            )
        val falling = (0 until 10).map { BigDecimal(100 - it * 2) }

        val panic =
            listOf(
                candle(100.0, high = 100.0, low = 89.0, open = 100.0, minutes = 0),
                candle(90.0, high = 90.5, low = 89.0, open = 89.5, minutes = 10),
            )

        assertEquals(StrategyAction.BUY, f.panicReversalDirection(panic, falling))
    }

    // -------------------------------------------------------------------- macro trend

    @Test
    fun `macro trend requires inputs and fails closed`() {
        assertNull(filters(EntryFilterOverrides()).macroTrendDirection(emptyList(), emptyList()))
        val f =
            filters(
                EntryFilterOverrides(
                    macroTrendEnabled = true,
                    macroTrendMinHigherBars = 5,
                    macroTrendPullbackEmaPeriod = 3,
                    macroTrendBlockOnUnknown = true,
                ),
            )

        assertNull(f.macroTrendDirection(List(5) { BigDecimal(100) }, emptyList()))
        assertEquals(
            StrategyAction.HOLD,
            f.macroTrendDirection(List(4) { BigDecimal(100) }, List(3) { BigDecimal(100) }),
        )
        assertEquals(
            StrategyAction.HOLD,
            f.macroTrendDirection(List(5) { BigDecimal(100) }, List(2) { BigDecimal(100) }),
        )
    }

    @Test
    fun `macro trend holds without an uptrend and on too deep a pullback`() {
        val f =
            filters(
                EntryFilterOverrides(
                    macroTrendEnabled = true,
                    macroTrendFastEma = 2,
                    macroTrendSlowEma = 3,
                    macroTrendMinHigherBars = 4,
                    macroTrendPullbackEmaPeriod = 3,
                    macroTrendMaxDeviationPercent = 1.0,
                    macroTrendBlockOnUnknown = true,
                ),
            )
        val flat = List(6) { BigDecimal(100) }
        val falling = (0 until 6).map { BigDecimal(100 - it) }

        assertEquals(StrategyAction.HOLD, f.macroTrendDirection(flat, flat))
        // Восходящий тренд есть, но цена далеко от EMA отката.
        val rising = (0 until 6).map { BigDecimal(100 + it * 5) }
        assertEquals(
            StrategyAction.HOLD,
            f.macroTrendDirection(rising, listOf(BigDecimal(100), BigDecimal(100), BigDecimal(100), BigDecimal(200))),
        )
    }

    @Test
    fun `macro trend buys on an uptrend with a shallow pullback and fails on zero ema`() {
        val f =
            filters(
                EntryFilterOverrides(
                    macroTrendEnabled = true,
                    macroTrendFastEma = 2,
                    macroTrendSlowEma = 3,
                    macroTrendMinHigherBars = 4,
                    macroTrendPullbackEmaPeriod = 3,
                    macroTrendMaxDeviationPercent = 5.0,
                    macroTrendBlockOnUnknown = true,
                ),
            )
        val rising = (0 until 6).map { BigDecimal(100 + it * 5) }

        assertEquals(StrategyAction.BUY, f.macroTrendDirection(rising, List(3) { BigDecimal(100) }))
        assertEquals(StrategyAction.HOLD, f.macroTrendDirection(rising, List(3) { BigDecimal.ZERO }))
    }

    @Test
    fun `macro trend fails closed on non finite ema`() {
        val f =
            filters(
                EntryFilterOverrides(
                    macroTrendEnabled = true,
                    macroTrendFastEma = 2,
                    macroTrendSlowEma = 3,
                    macroTrendMinHigherBars = 4,
                    macroTrendPullbackEmaPeriod = 3,
                    macroTrendBlockOnUnknown = true,
                ),
            )
        val huge = (0 until 6).map { BigDecimal("1e400") }

        assertEquals(StrategyAction.HOLD, f.macroTrendDirection(huge, List(3) { BigDecimal(100) }))
    }

    @Test
    fun `macro trend fails closed on a non finite higher timeframe ema`() {
        val f =
            filters(
                EntryFilterOverrides(
                    macroTrendEnabled = true,
                    macroTrendFastEma = 2,
                    macroTrendSlowEma = 3,
                    macroTrendMinHigherBars = 4,
                    macroTrendPullbackEmaPeriod = 3,
                    macroTrendBlockOnUnknown = true,
                ),
            )
        val huge = (0 until 6).map { BigDecimal("1e400") }

        assertEquals(StrategyAction.HOLD, f.macroTrendDirection(huge, List(4) { BigDecimal(100) }))
    }

    @Test
    fun `volume spike fails closed on a zero volume bar even with a positive sma`() {
        val f =
            filters(
                EntryFilterOverrides(
                    volumeSpikeEnabled = true,
                    volumeSpikePeriod = 3,
                    volumeSpikeMultiplier = 2.0,
                    volumeSpikeBlockOnUnknown = true,
                ),
            )
        val candles =
            listOf(
                candle(100.0, volume = 1_000, minutes = 0),
                candle(100.0, volume = 1_000, minutes = 10),
                candle(100.0, volume = 1_000, minutes = 20),
                candle(100.0, volume = 0, minutes = 30),
            )

        assertEquals(StrategyAction.HOLD, f.volumeSpikeAllows(candles))
    }

    @Test
    fun `panic reversal holds when the higher timeframe rsi is not oversold`() {
        val f =
            filters(
                EntryFilterOverrides(
                    panicReversalEnabled = true,
                    panicMinBars = 2,
                    panicRsiPeriod = 2,
                    panicMaxRsi = 30.0,
                    panicRequireBullishBar = true,
                    panicUseSessionDrop = true,
                    panicMinSessionDropPercent = 3.0,
                    panicBlockOnUnknown = true,
                ),
            )
        // Сильный рост на старшем ТФ → RSI 100 (не перепродан) при глубокой просадке сессии.
        val rising = (0 until 10).map { BigDecimal(100 + it * 5) }
        val panic =
            listOf(
                candle(100.0, high = 100.0, low = 89.0, open = 100.0, minutes = 0),
                candle(90.0, high = 90.5, low = 89.0, open = 89.5, minutes = 10),
            )

        assertEquals(StrategyAction.HOLD, f.panicReversalDirection(panic, rising))
    }

    // ----------------------------------------------------------------- time direction

    @Test
    fun `time direction blocks configured hours only`() {
        val off = filters(EntryFilterOverrides())
        assertFalse(off.blocksDirection(LocalTime.of(8, 0), StrategyAction.BUY))

        val f =
            filters(
                EntryFilterOverrides(
                    timeDirectionEnabled = true,
                    timeDirectionLongBlockUntilHour = 9,
                    timeDirectionShortBlockStartHour = 13,
                    timeDirectionShortBlockEndHour = 16,
                ),
            )

        assertTrue(f.blocksDirection(LocalTime.of(9, 0), StrategyAction.BUY))
        assertFalse(f.blocksDirection(LocalTime.of(10, 0), StrategyAction.BUY))
        assertTrue(f.blocksDirection(LocalTime.of(14, 0), StrategyAction.SELL))
        assertFalse(f.blocksDirection(LocalTime.of(17, 0), StrategyAction.SELL))
        assertFalse(f.blocksDirection(LocalTime.of(14, 0), StrategyAction.HOLD))
    }
}
