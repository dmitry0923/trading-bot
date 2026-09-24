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
 * Тесты research-фильтров входа (docs/17 этап 4c, pt.2/3): session (вход только в
 * определённые фазы сессии), pullback к EMA (не гнаться за ценой) и
 * Opening Range Breakout (вход только в направлении пробоя дневного диапазона).
 */
class EntryFiltersTest {
    private fun filter(
        sessionEnabled: Boolean = false,
        startMinutes: Int = 600,
        endMinutes: Int = 1080,
        pullbackEnabled: Boolean = false,
        emaPeriod: Int = 20,
        maxDeviationPercent: Double = 1.0,
        blockOnUnknown: Boolean = false,
        orbEnabled: Boolean = false,
        orbWindowBars: Int = 6,
        orbStrictBreakout: Boolean = true,
        orbBlockOnUnknown: Boolean = false,
        timeDirectionEnabled: Boolean = false,
        timeDirectionLongBlockUntilHour: Int = 11,
        timeDirectionShortBlockStartHour: Int = 13,
        timeDirectionShortBlockEndHour: Int = 16,
    ): EntryFilters =
        EntryFilters(
            sessionEnabled = sessionEnabled,
            sessionStartMinutes = startMinutes,
            sessionEndMinutes = endMinutes,
            pullbackEnabled = pullbackEnabled,
            pullbackEmaPeriod = emaPeriod,
            pullbackMaxDeviationPercent = maxDeviationPercent,
            pullbackBlockOnUnknown = blockOnUnknown,
            orbEnabled = orbEnabled,
            orbWindowBars = orbWindowBars,
            orbStrictBreakout = orbStrictBreakout,
            orbBlockOnUnknown = orbBlockOnUnknown,
            timeDirectionEnabled = timeDirectionEnabled,
            timeDirectionLongBlockUntilHour = timeDirectionLongBlockUntilHour,
            timeDirectionShortBlockStartHour = timeDirectionShortBlockStartHour,
            timeDirectionShortBlockEndHour = timeDirectionShortBlockEndHour,
        )

    @Test
    fun `disabled filter never blocks`() {
        val f = filter()
        assertFalse(f.blocksEntry(LocalTime.of(3, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertFalse(f.blocksEntry(LocalTime.of(23, 59), BigDecimal("100"), closes(100.0, 0.0, 30)))
    }

    @Test
    fun `session filter blocks outside window`() {
        val f = filter(sessionEnabled = true, startMinutes = 600, endMinutes = 1080)
        assertFalse(f.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertFalse(f.blocksEntry(LocalTime.of(18, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertTrue(f.blocksEntry(LocalTime.of(9, 59), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertTrue(f.blocksEntry(LocalTime.of(18, 1), BigDecimal("100"), closes(100.0, 0.0, 30)))
    }

    @Test
    fun `pullback filter blocks far deviation and allows near`() {
        // Ряд 100, 101, ..., 149: EMA (period 5) отстаёт от close на ~2 пункта,
        // отклонение последнего бара ~1.36% от EMA.
        val closes = closes(100.0, 1.0, 50)

        // Полоса 2.0% шире фактического отклонения 1.36% → пропуск.
        val allow =
            filter(pullbackEnabled = true, emaPeriod = 5, maxDeviationPercent = 2.0, blockOnUnknown = false)
        assertFalse(allow.blocksEntry(LocalTime.of(10, 0), BigDecimal("149"), closes))

        // Узкая полоса 0.5% — отклонение 1.36% больше → блок.
        val block = filter(pullbackEnabled = true, emaPeriod = 5, maxDeviationPercent = 0.5, blockOnUnknown = false)
        assertTrue(block.blocksEntry(LocalTime.of(10, 0), BigDecimal("149"), closes))
    }

    @Test
    fun `pullback blockOnUnknown fail-closed when too few bars`() {
        val block = filter(pullbackEnabled = true, emaPeriod = 20, blockOnUnknown = true)
        assertTrue(block.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), closes(100.0, 1.0, 5)))

        val pass = filter(pullbackEnabled = true, emaPeriod = 20, blockOnUnknown = false)
        assertFalse(pass.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), closes(100.0, 1.0, 5)))
    }

    @Test
    fun `overrides override config and from applies bt config`() {
        val config = BacktestConfig()
        config.sessionFilterEnabled = true
        config.sessionFilterStartMinutes = 100
        config.sessionFilterEndMinutes = 200
        config.pullbackFilterEnabled = false

        // Без оверрайдов — из конфига.
        val fromConfig = EntryFilters.from(config)
        assertTrue(fromConfig.blocksEntry(LocalTime.of(1, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertFalse(fromConfig.blocksEntry(LocalTime.of(2, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))

        // С оверрайдами — query приоритетнее.
        val fromOverrides =
            EntryFilters.from(
                config,
                EntryFilterOverrides(
                    sessionFilterEnabled = true,
                    sessionFilterStartMinutes = 700,
                    sessionFilterEndMinutes = 800,
                ),
            )
        assertTrue(fromOverrides.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertFalse(fromOverrides.blocksEntry(LocalTime.of(12, 30), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertEquals(700, fromOverrides.sessionStartMinutes)
    }

    @Test
    fun `orb disabled returns null`() {
        val f = filter(orbEnabled = false)
        val candles = dayCandles(100.0, windowBars = 6, postBars = 10)
        assertNull(f.orbDirection(candles, 15))
    }

    @Test
    fun `orb breakout up allows long`() {
        // Opening range = первые 6 баров дня (High=105). После окна close пробивает
        // 108 (вверх) → BUY (только LONG).
        val f = filter(orbEnabled = true, orbWindowBars = 6)
        val candles = dayCandles(100.0, windowBars = 6, postBars = 10, postStep = 1.0)
        candles[9] = candle(0, 9, open = 106.0, high = 109.0, low = 105.0, close = 108.0)
        assertEquals(StrategyAction.BUY, f.orbDirection(candles, 9))
    }

    @Test
    fun `orb breakout down allows short`() {
        val f = filter(orbEnabled = true, orbWindowBars = 6)
        val candles = dayCandles(100.0, windowBars = 6, postBars = 10, postStep = -1.0)
        candles[9] = candle(0, 9, open = 92.0, high = 95.0, low = 90.0, close = 92.0)
        assertEquals(StrategyAction.SELL, f.orbDirection(candles, 9))
    }

    @Test
    fun `orb inside range strict blocks while non-strict passes`() {
        // После окна close внутри [Low, High] диапазона открытия.
        val fStrict = filter(orbEnabled = true, orbWindowBars = 6, orbStrictBreakout = true)
        val fLoose = filter(orbEnabled = true, orbWindowBars = 6, orbStrictBreakout = false)
        val candles = dayCandles(100.0, windowBars = 6, postBars = 10)
        assertEquals(StrategyAction.HOLD, fStrict.orbDirection(candles, 9))
        assertNull(fLoose.orbDirection(candles, 9))
    }

    @Test
    fun `orb too few bars of the day returns null by default`() {
        // Данные начинаются в середине дня: на индексах < windowBars начала дня нет.
        val f = filter(orbEnabled = true, orbWindowBars = 6)
        val candles = dayCandles(100.0, windowBars = 4, postBars = 5)
        assertNull(f.orbDirection(candles, 5))
    }

    @Test
    fun `orb blockOnUnknown fail-closed when range undefined`() {
        val f = filter(orbEnabled = true, orbWindowBars = 6, orbBlockOnUnknown = true)
        val candles = dayCandles(100.0, windowBars = 3, postBars = 5)
        assertEquals(StrategyAction.HOLD, f.orbDirection(candles, 5))
    }

    @Test
    fun `orb resets range on new trading day`() {
        // Первый день: окно 100..105 (High=105), бары после окна внутри диапазона
        // (close=100) → HOLD (strict).
        val f = filter(orbEnabled = true, orbWindowBars = 6, orbStrictBreakout = true)
        val firstDay = dayCandles(100.0, windowBars = 6, postBars = 4)
        // Второй день: открытие 110 (High окна = 111). Bar 16 (idx 7 второго дня)
        // пробивает СВОЙ High 111 close=116 → BUY на баре собственного (нового) дня.
        val secondDay = dayCandles(110.0, windowBars = 6, postBars = 2, dayOffset = 1)
        val candles = firstDay + secondDay
        val last = candles.lastIndex

        // close=110 в диапазоне 2-го дня → HOLD.
        assertEquals(StrategyAction.HOLD, f.orbDirection(candles, last))

        // С последним баром, пробивающим High 2-го дня (111) → BUY.
        val broken =
            candles.dropLast(1) +
                candle(dayOffset = 1, idx = 7, open = 111.0, high = 117.0, low = 110.0, close = 116.0)
        assertEquals(StrategyAction.BUY, f.orbDirection(broken, broken.lastIndex))

        // Первый день: внутри диапазона → HOLD (strict), пробой 2-го дня не «затекает».
        assertEquals(StrategyAction.HOLD, f.orbDirection(firstDay, firstDay.lastIndex))
    }

    @Test
    fun `timeDirection blocks morning long and midday short`() {
        val f =
            filter(
                timeDirectionEnabled = true,
                timeDirectionLongBlockUntilHour = 11,
                timeDirectionShortBlockStartHour = 13,
                timeDirectionShortBlockEndHour = 16,
            )
        assertTrue(f.blocksDirection(LocalTime.of(10, 0), StrategyAction.BUY))
        assertTrue(f.blocksDirection(LocalTime.of(11, 0), StrategyAction.BUY))
        assertFalse(f.blocksDirection(LocalTime.of(12, 0), StrategyAction.BUY))
        assertFalse(f.blocksDirection(LocalTime.of(13, 0), StrategyAction.BUY))
        assertTrue(f.blocksDirection(LocalTime.of(13, 0), StrategyAction.SELL))
        assertTrue(f.blocksDirection(LocalTime.of(16, 0), StrategyAction.SELL))
        assertFalse(f.blocksDirection(LocalTime.of(12, 0), StrategyAction.SELL))
        assertFalse(f.blocksDirection(LocalTime.of(17, 0), StrategyAction.SELL))
    }

    @Test
    fun `timeDirection disabled never blocks`() {
        val f = filter()
        assertFalse(f.blocksDirection(LocalTime.of(9, 0), StrategyAction.BUY))
        assertFalse(f.blocksDirection(LocalTime.of(14, 0), StrategyAction.SELL))
        assertFalse(f.blocksDirection(LocalTime.of(10, 0), StrategyAction.HOLD))
    }

    private val dayBase = LocalDate.of(2026, 1, 5)

    private fun candle(
        dayOffset: Int,
        idx: Int,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
    ): Candle =
        Candle(
            ticker = "TEST",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(open),
            highPrice = BigDecimal.valueOf(high),
            lowPrice = BigDecimal.valueOf(low),
            closePrice = BigDecimal.valueOf(close),
            volume = 100,
            time = dayBase.plusDays(dayOffset.toLong()).atTime(6, 0).plusMinutes(10L * idx),
        )

    /**
     * День из [windowBars] баров открытия (open=high=base+..) и [postBars] баров
     * после окна, идущих на [postStep] за бар от цены [base].
     */
    private fun dayCandles(
        base: Double,
        windowBars: Int,
        postBars: Int,
        postStep: Double = 0.0,
        dayOffset: Int = 0,
    ): MutableList<Candle> {
        val bars = mutableListOf<Candle>()
        repeat(windowBars) { i ->
            val p = base + i * postStep
            bars += candle(dayOffset, i, p, p + 1.0, p - 1.0, p)
        }
        repeat(postBars) { i ->
            val p = base + (windowBars + i) * postStep
            bars += candle(dayOffset, windowBars + i, p, p + 1.0, p - 1.0, p)
        }
        return bars
    }

    private fun closes(
        start: Double,
        step: Double,
        count: Int,
    ): List<BigDecimal> = (0 until count).map { i -> BigDecimal.valueOf(start + step * i) }
}
