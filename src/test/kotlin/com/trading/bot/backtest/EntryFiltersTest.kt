package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.domain.technical.IndicatorCalculator
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
        orbWindowStartMinutes: Int = 0,
        orbWindowEndMinutes: Int = 1440,
        timeDirectionEnabled: Boolean = false,
        timeDirectionLongBlockUntilHour: Int = 11,
        timeDirectionShortBlockStartHour: Int = 13,
        timeDirectionShortBlockEndHour: Int = 16,
        vwapMrEnabled: Boolean = false,
        vwapMrDeviationSigma: Double = 1.5,
        vwapMrMaxAdx: Double = 25.0,
        vwapMrTimeframe: String = "HOUR_1",
        vwapMrMinSessionBars: Int = 6,
        vwapMrBlockOnUnknown: Boolean = true,
        squeezeEnabled: Boolean = false,
        squeezeBlockOnUnknown: Boolean = true,
        squeezeLookbackBars: Int = 5,
        panicReversalEnabled: Boolean = false,
        panicMinSessionDropPercent: Double = 3.0,
        panicRsiPeriod: Int = 14,
        panicMaxRsi: Double = 25.0,
        panicRequireBullishBar: Boolean = true,
        panicMinBars: Int = 20,
        panicBlockOnUnknown: Boolean = true,
        macroTrendEnabled: Boolean = false,
        macroTrendFastEma: Int = 20,
        macroTrendSlowEma: Int = 50,
        macroTrendMinHigherBars: Int = 60,
        macroTrendPullbackEmaPeriod: Int = 20,
        macroTrendMaxDeviationPercent: Double = 0.5,
        macroTrendBlockOnUnknown: Boolean = true,
        emaCrossEnabled: Boolean = false,
        emaCrossFastPeriod: Int = 20,
        emaCrossSlowPeriod: Int = 50,
        emaCrossBlockOnUnknown: Boolean = true,
        volumeSpikeEnabled: Boolean = false,
        volumeSpikePeriod: Int = 20,
        volumeSpikeMultiplier: Double = 1.5,
        volumeSpikeBlockOnUnknown: Boolean = true,
        vwapMrDeviationAtr: Double = 0.0,
        vwapMrAtrPeriod: Int = 14,
        panicUseSessionDrop: Boolean = true,
        rangeSqueezeEnabled: Boolean = false,
        rangeSqueezeRangePeriod: Int = 20,
        rangeSqueezeAtrPeriod: Int = 50,
        rangeSqueezeMultiplier: Double = 0.5,
        rangeSqueezeLookbackBars: Int = 5,
        rangeSqueezeRequireVolume: Boolean = true,
        rangeSqueezeBlockOnUnknown: Boolean = true,
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
            orbWindowStartMinutes = orbWindowStartMinutes,
            orbWindowEndMinutes = orbWindowEndMinutes,
            timeDirectionEnabled = timeDirectionEnabled,
            timeDirectionLongBlockUntilHour = timeDirectionLongBlockUntilHour,
            timeDirectionShortBlockStartHour = timeDirectionShortBlockStartHour,
            timeDirectionShortBlockEndHour = timeDirectionShortBlockEndHour,
            vwapMrEnabled = vwapMrEnabled,
            vwapMrDeviationSigma = vwapMrDeviationSigma,
            vwapMrMaxAdx = vwapMrMaxAdx,
            vwapMrTimeframe = vwapMrTimeframe,
            vwapMrMinSessionBars = vwapMrMinSessionBars,
            vwapMrBlockOnUnknown = vwapMrBlockOnUnknown,
            squeezeEnabled = squeezeEnabled,
            squeezeBlockOnUnknown = squeezeBlockOnUnknown,
            squeezeLookbackBars = squeezeLookbackBars,
            panicReversalEnabled = panicReversalEnabled,
            panicMinSessionDropPercent = panicMinSessionDropPercent,
            panicRsiPeriod = panicRsiPeriod,
            panicMaxRsi = panicMaxRsi,
            panicTimeframe = "HOUR_1",
            panicRequireBullishBar = panicRequireBullishBar,
            panicMinBars = panicMinBars,
            panicBlockOnUnknown = panicBlockOnUnknown,
            macroTrendEnabled = macroTrendEnabled,
            macroTrendTimeframe = "HOUR_1",
            macroTrendFastEma = macroTrendFastEma,
            macroTrendSlowEma = macroTrendSlowEma,
            macroTrendMinHigherBars = macroTrendMinHigherBars,
            macroTrendPullbackEmaPeriod = macroTrendPullbackEmaPeriod,
            macroTrendMaxDeviationPercent = macroTrendMaxDeviationPercent,
            macroTrendBlockOnUnknown = macroTrendBlockOnUnknown,
            emaCrossEnabled = emaCrossEnabled,
            emaCrossFastPeriod = emaCrossFastPeriod,
            emaCrossSlowPeriod = emaCrossSlowPeriod,
            emaCrossBlockOnUnknown = emaCrossBlockOnUnknown,
            volumeSpikeEnabled = volumeSpikeEnabled,
            volumeSpikePeriod = volumeSpikePeriod,
            volumeSpikeMultiplier = volumeSpikeMultiplier,
            volumeSpikeBlockOnUnknown = volumeSpikeBlockOnUnknown,
            vwapMrDeviationAtr = vwapMrDeviationAtr,
            vwapMrAtrPeriod = vwapMrAtrPeriod,
            panicUseSessionDrop = panicUseSessionDrop,
            rangeSqueezeEnabled = rangeSqueezeEnabled,
            rangeSqueezeRangePeriod = rangeSqueezeRangePeriod,
            rangeSqueezeAtrPeriod = rangeSqueezeAtrPeriod,
            rangeSqueezeMultiplier = rangeSqueezeMultiplier,
            rangeSqueezeLookbackBars = rangeSqueezeLookbackBars,
            rangeSqueezeRequireVolume = rangeSqueezeRequireVolume,
            rangeSqueezeBlockOnUnknown = rangeSqueezeBlockOnUnknown,
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
    fun `orb window skips bars before window and tests breakout after it`() {
        // Окно диапазона 15:30-16:00 МСК = минуты 930..960.
        // Бары тестового дня начинаются в 06:00 с шагом 10 мин, поэтому
        // idx 57 = 15:30, idx 60 = 16:00, idx 62 = 16:20.
        val start = 15 * 60 + 30
        val end = 16 * 60
        val f =
            filter(
                orbEnabled = true,
                orbWindowBars = 3,
                orbStrictBreakout = true,
                orbWindowStartMinutes = start,
                orbWindowEndMinutes = end,
            )
        val bars = mutableListOf<Candle>()
        // Бары до окна: плоские 100 (High=101, Low=99) — в High/Low диапазона
        // не попадают.
        repeat(70) { bars += candle(0, it, 100.0, 101.0, 99.0, 100.0) }
        // Окно (idx 57..59): узкий диапазон 200±1 → High=201, Low=199.
        repeat(3) { i ->
            bars[57 + i] = candle(0, 57 + i, 200.0, 201.0, 199.0, 200.0)
        }

        // До окна (утро) — ORB не применяется.
        assertNull(f.orbDirection(bars, 10))
        assertNull(f.orbDirection(bars, 56))
        // Внутри окна (диапазон не закрыт, blockOnUnknown=false → пропуск).
        assertNull(f.orbDirection(bars, 57))
        assertNull(f.orbDirection(bars, 58))
        assertNull(f.orbDirection(bars, 59))
        // После окна: close 100 < Low 199 → SELL (пробой вниз).
        assertEquals(StrategyAction.SELL, f.orbDirection(bars, 60))
        assertEquals(StrategyAction.SELL, f.orbDirection(bars, 62))

        // Пробой вверх после окна (close 250 > High 201) → BUY.
        val up = bars.toMutableList()
        up[62] = candle(0, 62, 250.0, 251.0, 249.0, 250.0)
        assertEquals(StrategyAction.BUY, f.orbDirection(up, 62))

        // Внутри диапазона после окна при strict → HOLD, при loose → пропуск.
        val inside = bars.toMutableList()
        inside[62] = candle(0, 62, 200.0, 201.0, 199.0, 200.0)
        assertEquals(StrategyAction.HOLD, f.orbDirection(inside, 62))
        val loose =
            filter(
                orbEnabled = true,
                orbWindowBars = 3,
                orbStrictBreakout = false,
                orbWindowStartMinutes = start,
                orbWindowEndMinutes = end,
            )
        assertNull(loose.orbDirection(inside, 62))
    }

    @Test
    fun `orb window formation is fail-closed with blockOnUnknown`() {
        val start = 15 * 60 + 30
        val end = 16 * 60
        val f =
            filter(
                orbEnabled = true,
                orbWindowBars = 3,
                orbBlockOnUnknown = true,
                orbWindowStartMinutes = start,
                orbWindowEndMinutes = end,
            )
        val bars = mutableListOf<Candle>()
        repeat(70) { bars += candle(0, it, 200.0, 201.0, 199.0, 200.0) }
        // Внутри окна диапазон ещё не набрал 3 бара → HOLD (fail-closed).
        assertEquals(StrategyAction.HOLD, f.orbDirection(bars, 58))
        // Утро до окна — пропуск даже при blockOnUnknown.
        assertNull(f.orbDirection(bars, 30))
        // После окна и после полного диапазона — проверка пробоя: close 200 внутри
        // 199..201 при strict → HOLD.
        assertEquals(StrategyAction.HOLD, f.orbDirection(bars, 60))
    }

    @Test
    fun `orb day starting after window start is undefined`() {
        // Данные дня начинаются в 14:00 (idx 0 = 14:00), первый бар окна (15:30) =
        // idx 9. Диапазон не успевает закрыться в первый бар окна → undefined.
        val start = 15 * 60 + 30
        val end = 16 * 60
        val late =
            filter(
                orbEnabled = true,
                orbWindowBars = 3,
                orbWindowStartMinutes = start,
                orbWindowEndMinutes = end,
            )
        val strict =
            filter(
                orbEnabled = true,
                orbWindowBars = 3,
                orbBlockOnUnknown = true,
                orbWindowStartMinutes = start,
                orbWindowEndMinutes = end,
            )
        val bars = mutableListOf<Candle>()
        repeat(30) { i ->
            val time = dayBase.atTime(14, 0).plusMinutes(10L * i)
            bars +=
                Candle(
                    ticker = "TEST",
                    timeframe = "MINUTE_10",
                    openPrice = BigDecimal.valueOf(200.0),
                    highPrice = BigDecimal.valueOf(201.0),
                    lowPrice = BigDecimal.valueOf(199.0),
                    closePrice = BigDecimal.valueOf(200.0),
                    volume = 100,
                    time = time,
                )
        }
        // idx 9 = 15:30 — первый бар окна, диапазон не закрыт.
        assertNull(late.orbDirection(bars, 9))
        assertEquals(StrategyAction.HOLD, strict.orbDirection(bars, 9))
    }

    @Test
    fun `orb range longer than window extends past window end`() {
        // orbWindowBars=6 (60 мин) при окне 15:30-16:00: диапазон не помещается в
        // окно и продлевается за его конец; вход разрешён только после orbWindowEnd.
        val start = 15 * 60 + 30
        val end = 16 * 60
        val f =
            filter(
                orbEnabled = true,
                orbWindowBars = 6,
                orbWindowStartMinutes = start,
                orbWindowEndMinutes = end,
            )
        val bars = mutableListOf<Candle>()
        repeat(70) { bars += candle(0, it, 200.0, 201.0, 199.0, 200.0) }
        // Диапазон = idx 57..62 (последний бар 16:20) → на нём самом пробой не
        // проверяется.
        assertNull(f.orbDirection(bars, 62))
        // Следующий бар (16:30) — диапазон закрыт, close внутри → strict HOLD.
        assertEquals(StrategyAction.HOLD, f.orbDirection(bars, 63))
    }

    @Test
    fun `orb default window keeps full day behaviour`() {
        // Дефолт 0..1440: диапазон = первые бары дня, пробой — в любом баре
        // после окна (как в исходной реализации).
        val f = filter(orbEnabled = true, orbWindowBars = 6, orbStrictBreakout = true)
        val day = dayCandles(100.0, windowBars = 6, postBars = 4)
        assertEquals(StrategyAction.HOLD, f.orbDirection(day, day.lastIndex))
        val broken =
            day.dropLast(1) +
                candle(0, 9, 111.0, 117.0, 110.0, 116.0)
        assertEquals(StrategyAction.BUY, f.orbDirection(broken, broken.lastIndex))
    }

    /** Свеча сессии для VWAP-MR: значения low/open/close совпадают, high = low + 2. */
    private fun vwapCandle(
        idx: Int,
        price: Double,
    ): Candle = candle(0, idx, price, price + 2.0, price, price)

    @Test
    fun `vwapMr disabled never blocks`() {
        val f = filter()
        assertNull(f.vwapMrDirection(emptyList(), emptyList()))
    }

    @Test
    fun `vwapMr blocks when session too short and fail-closed`() {
        val f = filter(vwapMrEnabled = true, vwapMrMinSessionBars = 6, vwapMrBlockOnUnknown = true)
        val short = (0 until 3).map { i -> vwapCandle(i, 100.0) }
        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(short, emptyList()))
        val open = filter(vwapMrEnabled = true, vwapMrMinSessionBars = 6, vwapMrBlockOnUnknown = false)
        assertNull(open.vwapMrDirection(short, emptyList()))
    }

    @Test
    fun `vwapMr blocks flat session with zero sigma when fail-closed`() {
        val f = filter(vwapMrEnabled = true, vwapMrBlockOnUnknown = true)
        val flat = (0 until 10).map { i -> vwapCandle(i, 100.0) }
        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(flat, emptyList()))
    }

    @Test
    fun `vwapMr holds when price near vwap`() {
        val f = filter(vwapMrEnabled = true, vwapMrDeviationSigma = 1.5, vwapMrMinSessionBars = 4)
        // Сессия колеблется вокруг 100, последний бар = VWAP → отклонение ~0.
        val session =
            listOf(
                vwapCandle(0, 100.0),
                vwapCandle(1, 100.0),
                vwapCandle(2, 100.0),
                vwapCandle(3, 100.0),
            )
        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(session, emptyList()))
    }

    @Test
    fun `vwapMr allows buy below vwap and sell above`() {
        val f = filter(vwapMrEnabled = true, vwapMrDeviationSigma = 1.0, vwapMrMinSessionBars = 4)
        // Балансирующие бары дают VWAP ≈ 100 и заметную σ, затем резкое отклонение.
        val base =
            listOf(
                vwapCandle(0, 100.0),
                vwapCandle(1, 100.0),
                vwapCandle(2, 100.0),
            )
        // Сильное просадение: close 90 при VWAP ~100 → ожидаем отскок вверх (BUY).
        val down = base + vwapCandle(3, 90.0)
        assertEquals(StrategyAction.BUY, f.vwapMrDirection(down, emptyList()))
        // Сильный рост: close 110 → ожидаем возврат вниз (SELL).
        val up = base + vwapCandle(3, 110.0)
        assertEquals(StrategyAction.SELL, f.vwapMrDirection(up, emptyList()))
    }

    @Test
    fun `vwapMr blocks on high adx trend`() {
        val f = filter(vwapMrEnabled = true, vwapMrDeviationSigma = 0.1, vwapMrMinSessionBars = 2, vwapMrMaxAdx = 20.0)
        // Сильный тренд на старшем ТФ: ADX > 20 → mean reversion запрещён.
        val trend =
            (0 until 40).map { i ->
                val p = 100.0 + i
                Candle(
                    ticker = "TEST",
                    timeframe = "HOUR_1",
                    openPrice = BigDecimal.valueOf(p),
                    highPrice = BigDecimal.valueOf(p + 1.0),
                    lowPrice = BigDecimal.valueOf(p - 1.0),
                    closePrice = BigDecimal.valueOf(p),
                    volume = 100,
                    time = dayBase.atTime(0, 0).plusHours(i.toLong()),
                )
            }
        val session =
            listOf(
                vwapCandle(0, 100.0),
                vwapCandle(1, 120.0),
            )
        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(session, trend))
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

    @Test
    fun `squeeze blocks inside squeeze and allows breakout`() {
        val f = filter(squeezeEnabled = true)
        // Сжатие: крошечная осцилляция → Боллинджер внутри Кельтнера → входа нет.
        val squeezed =
            (0 until 30).map { i ->
                candle(0, i, 100.0, 100.11, 99.99, if (i % 2 == 0) 100.1 else 100.0)
            }
        assertEquals(true, IndicatorCalculator.isSqueeze(squeezed))
        assertEquals(StrategyAction.HOLD, f.squeezeDirection(squeezed))

        // Пробой вверх: 19 баров без движения + резкий вынос в последнем баре.
        // Линейный рост здесь НЕ подходит: дисперсия растёт вместе с ценой
        // (z последней точки ≈ 1.65 < 2σ), т.е. полоса не пробивается.
        val breakoutUp = flatThen(130.0)
        assertEquals(false, IndicatorCalculator.isSqueeze(breakoutUp))
        assertEquals(StrategyAction.BUY, f.squeezeDirection(breakoutUp))

        // Зеркально: резкий провал → SELL.
        val breakoutDown = flatThen(70.0)
        assertEquals(false, IndicatorCalculator.isSqueeze(breakoutDown))
        assertEquals(StrategyAction.SELL, f.squeezeDirection(breakoutDown))
    }

    @Test
    fun `squeeze fail-closed on missing data`() {
        val block = filter(squeezeEnabled = true, squeezeBlockOnUnknown = true)
        assertEquals(StrategyAction.HOLD, block.squeezeDirection(candles(1, 2.0)))
        val pass = filter(squeezeEnabled = true, squeezeBlockOnUnknown = false)
        assertNull(pass.squeezeDirection(candles(1, 2.0)))
        // Выключенный фильтр не влияет ни на что.
        assertNull(filter().squeezeDirection(candles(30, 100.0)))
    }

    @Test
    fun `squeeze breakout without a preceding squeeze passes through`() {
        // Линейный разгон: полоса Боллинджера расходится с Кельтнером (сжатия нет и до
        // пробоя тоже), последний бар выносит цену за BB - squeeze перед пробоем не было,
        // поэтому фильтр обязан промолчать, а не заблокировать вход.
        val ramp = (0 until 24).map { i -> candle(0, i, 100.0 + i, 101.0 + i, 99.0 + i, 100.0 + i) }
        val breakout = ramp + candle(0, 24, 124.0, 201.0, 122.0, 200.0)
        assertEquals(false, IndicatorCalculator.isSqueeze(breakout))
        // Сжатия не было и в окне, закончившемся за 5 баров до пробоя.
        assertEquals(false, IndicatorCalculator.isSqueeze(breakout.subList(0, breakout.size - 5)))
        assertNull(filter(squeezeEnabled = true, squeezeLookbackBars = 5).squeezeDirection(breakout))
        // При lookback=0 требование «сжатие было» снято - любой пробой BB разрешён.
        assertEquals(StrategyAction.BUY, filter(squeezeEnabled = true, squeezeLookbackBars = 0).squeezeDirection(breakout))
    }

    @Test
    fun `squeeze series matches per bar isSqueeze`() {
        val bars = walk(80)
        val series = IndicatorCalculator.squeezeSeries(bars) ?: error("squeezeSeries must be computable")
        val minBars = IndicatorCalculator.squeezeMinBars()
        var compared = 0
        var squeezeBars = 0
        for (i in 0 until bars.size) {
            val direct = IndicatorCalculator.isSqueeze(bars.subList(0, i + 1)) ?: continue
            assertTrue(i >= minBars - 1, "isSqueeze must be defined from bar ${minBars - 1}")
            assertEquals(direct, series[i], "squeeze state mismatch at bar $i")
            compared++
            if (series[i]) squeezeBars++
        }
        assertTrue(compared > 0, "nothing compared")
        assertTrue(squeezeBars > 0, "walk must contain squeeze bars for the test to mean anything")
    }

    @Test
    fun `squeeze filter with precomputed series agrees with per bar path`() {
        val up = flatThen(130.0)
        val down = flatThen(70.0)
        val f = filter(squeezeEnabled = true)
        for (bars in listOf(up, down)) {
            val series = IndicatorCalculator.squeezeSeries(bars) ?: error("squeezeSeries must be computable")
            val idx = bars.size - 1
            assertEquals(IndicatorCalculator.isSqueeze(bars), series[idx])
            assertEquals(f.squeezeDirection(bars), f.squeezeDirection(bars, series, idx))
        }
    }

    @Test
    fun `panic reversal allows long after session drop`() {
        val f = filter(panicReversalEnabled = true, panicMinBars = 20)
        // Сессия падает на 3.6% (100 → 96.4), последний бар — бычий (отскок).
        val session =
            (0 until 20).map { i ->
                val open = 100.0 - 0.2 * i
                val close = if (i == 19) open + 0.2 else open - 0.2
                candle(0, i, open, open + 0.2, open - 0.4, close)
            }
        val oversold = closes(100.0, -1.0, 20)
        assertEquals(StrategyAction.BUY, f.panicReversalDirection(session, oversold))

        // Медвежий последний бар — подтверждения отскока нет.
        val bearish = session.dropLast(1) + candle(0, 19, 96.2, 96.3, 95.8, 95.9)
        assertEquals(StrategyAction.HOLD, f.panicReversalDirection(bearish, oversold))

        // Падение всего 1% — не паника.
        val mild =
            (0 until 20).map { i ->
                val open = 100.0 - 0.05 * i
                val close = if (i == 19) open + 0.02 else open - 0.05
                candle(0, i, open, open + 0.05, open - 0.1, close)
            }
        assertEquals(StrategyAction.HOLD, f.panicReversalDirection(mild, oversold))
    }

    @Test
    fun `panic reversal fail-closed on unknown rsi data`() {
        val block = filter(panicReversalEnabled = true, panicMinBars = 5, panicBlockOnUnknown = true)
        val session =
            (0 until 20).map { i ->
                val open = 100.0 - 0.2 * i
                val close = if (i == 19) open + 0.2 else open - 0.2
                candle(0, i, open, open + 0.2, open - 0.4, close)
            }
        // RSI старшего ТФ недоступен (мало баров) → fail-closed блок.
        assertEquals(StrategyAction.HOLD, block.panicReversalDirection(session, closes(100.0, -1.0, 5)))
        val pass = filter(panicReversalEnabled = true, panicMinBars = 5, panicBlockOnUnknown = false)
        assertNull(pass.panicReversalDirection(session, closes(100.0, -1.0, 5)))
    }

    @Test
    fun `macro trend allows long on pullback in uptrend only`() {
        val f = filter(macroTrendEnabled = true, macroTrendMinHigherBars = 60)
        val uptrend = closes(100.0, 1.0, 60)
        val downtrend = closes(200.0, -1.0, 60)

        // Откат: цена на базовой EMA20 (ряд постоянный) → BUY.
        val flatBase = closes(100.0, 0.0, 25)
        assertEquals(StrategyAction.BUY, f.macroTrendDirection(uptrend, flatBase))

        // Слишком далеко от EMA (отклонение 2% > 0.5%) → HOLD.
        val extended = closes(100.0, 0.0, 24) + listOf(BigDecimal("102"))
        assertEquals(StrategyAction.HOLD, f.macroTrendDirection(uptrend, extended))

        // Нисходящий макро-тренд → вход заблокирован (только BUY не разрешён).
        assertEquals(StrategyAction.HOLD, f.macroTrendDirection(downtrend, flatBase))
    }

    @Test
    fun `macro trend fail-closed on insufficient history`() {
        val block = filter(macroTrendEnabled = true, macroTrendMinHigherBars = 60)
        val short = closes(100.0, 1.0, 10)
        assertEquals(StrategyAction.HOLD, block.macroTrendDirection(short, closes(100.0, 0.0, 25)))
        val pass = filter(macroTrendEnabled = true, macroTrendMinHigherBars = 60, macroTrendBlockOnUnknown = false)
        assertNull(pass.macroTrendDirection(short, closes(100.0, 0.0, 25)))
    }

    /** Свеча с явным объёмом — для объёмного фильтра и объёмного подтверждения пробоя. */
    private fun volCandle(
        idx: Int,
        close: Double,
        range: Double = 0.2,
        volume: Long = 100,
    ): Candle =
        Candle(
            ticker = "TEST",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(close),
            highPrice = BigDecimal.valueOf(close + range),
            lowPrice = BigDecimal.valueOf(close - range),
            closePrice = BigDecimal.valueOf(close),
            volume = volume,
            time = dayBase.atTime(6, 0).plusMinutes(10L * idx),
        )

    private fun series(
        closes: List<Double>,
        volume: Long = 100,
    ): List<Candle> = closes.mapIndexed { i, c -> volCandle(i, c, volume = volume) }

    @Test
    fun `ema cross is null when filter is off`() {
        assertNull(filter().emaCrossDirection(series(listOf(10.0, 9.0, 8.0))))
    }

    @Test
    fun `ema cross up allows buy and cross down allows sell`() {
        val f = filter(emaCrossEnabled = true, emaCrossFastPeriod = 3, emaCrossSlowPeriod = 5)
        val declining = (0 until 40).map { 10.0 - 0.05 * it } + 15.0
        assertEquals(StrategyAction.BUY, f.emaCrossDirection(series(declining)))
        val rising = (0 until 40).map { 10.0 + 0.05 * it } + 1.0
        assertEquals(StrategyAction.SELL, f.emaCrossDirection(series(rising)))
    }

    @Test
    fun `ema cross blocks entry between crossings`() {
        val f = filter(emaCrossEnabled = true, emaCrossFastPeriod = 3, emaCrossSlowPeriod = 5)
        val steadyUp = (0 until 60).map { 10.0 + 0.1 * it }
        assertEquals(StrategyAction.HOLD, f.emaCrossDirection(series(steadyUp)))
        val steadyDown = (0 until 60).map { 30.0 - 0.1 * it }
        assertEquals(StrategyAction.HOLD, f.emaCrossDirection(series(steadyDown)))
    }

    @Test
    fun `ema cross without enough bars is fail closed or skipped`() {
        val bars = series(listOf(10.0))
        assertEquals(StrategyAction.HOLD, filter(emaCrossEnabled = true).emaCrossDirection(bars))
        val pass = filter(emaCrossEnabled = true, emaCrossBlockOnUnknown = false)
        assertNull(pass.emaCrossDirection(bars))
    }

    @Test
    fun `volume spike passes the entry and flat volume blocks it`() {
        val quiet = series(List(25) { 10.0 }, volume = 100)
        assertEquals(
            StrategyAction.HOLD,
            filter(volumeSpikeEnabled = true).volumeSpikeAllows(quiet),
        )
        val spike = quiet.dropLast(1) + volCandle(24, 10.0, volume = 400)
        assertNull(filter(volumeSpikeEnabled = true).volumeSpikeAllows(spike))
    }

    @Test
    fun `volume spike is null when filter is off and fail closed on unknown`() {
        val bars = series(List(25) { 10.0 })
        assertNull(filter().volumeSpikeAllows(bars))
        assertEquals(StrategyAction.HOLD, filter(volumeSpikeEnabled = true).volumeSpikeAllows(bars.take(5)))
        val pass = filter(volumeSpikeEnabled = true, volumeSpikeBlockOnUnknown = false)
        assertNull(pass.volumeSpikeAllows(bars.take(5)))
    }

    @Test
    fun `vwap mr in atr mode sells above the band and buys below it`() {
        val f =
            filter(
                vwapMrEnabled = true,
                vwapMrDeviationAtr = 1.5,
                vwapMrAtrPeriod = 14,
                vwapMrMaxAdx = 1000.0,
                vwapMrMinSessionBars = 6,
            )
        val flat = (0 until 20).map { volCandle(it, 100.0, range = 1.0) }
        val above = flat.dropLast(1) + volCandle(19, 130.0, range = 1.0)
        assertEquals(StrategyAction.SELL, f.vwapMrDirection(above, emptyList()))
        val below = flat.dropLast(1) + volCandle(19, 70.0, range = 1.0)
        assertEquals(StrategyAction.BUY, f.vwapMrDirection(below, emptyList()))
    }

    @Test
    fun `vwap mr in atr mode holds inside the band`() {
        val f =
            filter(
                vwapMrEnabled = true,
                vwapMrDeviationAtr = 1.5,
                vwapMrAtrPeriod = 14,
                vwapMrMaxAdx = 1000.0,
                vwapMrMinSessionBars = 6,
            )
        val bars = (0 until 20).map { volCandle(it, 100.0, range = 1.0) }
        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(bars, emptyList()))
    }

    @Test
    fun `vwap mr keeps sigma behaviour when atr multiplier is zero`() {
        val f =
            filter(
                vwapMrEnabled = true,
                vwapMrDeviationAtr = 0.0,
                vwapMrDeviationSigma = 2.0,
                vwapMrMaxAdx = 1000.0,
                vwapMrMinSessionBars = 6,
            )
        val flat = (0 until 20).map { volCandle(it, 100.0, range = 1.0) }
        val above = flat.dropLast(1) + volCandle(19, 130.0, range = 1.0)
        assertEquals(StrategyAction.SELL, f.vwapMrDirection(above, emptyList()))
        // Волатильная сессия: 1.0 отклонения внутри 2σ, поэтому вход заблокирован.
        val wiggly = (0 until 20).map { volCandle(it, if (it % 2 == 0) 98.0 else 102.0, range = 2.0) }
        val near = wiggly.dropLast(1) + volCandle(19, 101.0, range = 2.0)
        assertEquals(StrategyAction.HOLD, f.vwapMrDirection(near, emptyList()))
    }

    /** Бычья свеча (close > open) — требуется panic-фильтру с bullish-подтверждением. */
    private fun bullCandle(
        idx: Int,
        open: Double,
        close: Double,
    ): Candle =
        Candle(
            ticker = "TEST",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(open),
            highPrice = BigDecimal.valueOf(maxOf(open, close) + 0.5),
            lowPrice = BigDecimal.valueOf(minOf(open, close) - 0.5),
            closePrice = BigDecimal.valueOf(close),
            volume = 100,
            time = dayBase.atTime(6, 0).plusMinutes(10L * idx),
        )

    @Test
    fun `panic reversal without session drop allows a buy on a rising session`() {
        val f =
            filter(
                panicReversalEnabled = true,
                panicUseSessionDrop = false,
                panicRsiPeriod = 5,
                panicMaxRsi = 99.0,
                panicRequireBullishBar = true,
                panicMinBars = 3,
            )
        val rising = (0 until 6).map { bullCandle(it, 100.0 + it, 100.5 + it) }
        // RSI нужен на непостоянном ряду: на константе gain/loss = 0 и RSI не определён.
        val higher = (0 until 20).map { BigDecimal.valueOf(if (it % 2 == 0) 98.0 else 102.0) }
        assertEquals(StrategyAction.BUY, f.panicReversalDirection(rising, higher))
    }

    @Test
    fun `panic reversal keeps the session drop requirement by default`() {
        val f =
            filter(
                panicReversalEnabled = true,
                panicUseSessionDrop = true,
                panicMinSessionDropPercent = 3.0,
                panicRsiPeriod = 5,
                panicMaxRsi = 99.0,
                panicMinBars = 3,
            )
        val rising = (0 until 6).map { volCandle(it, 100.0 + it) }
        assertEquals(StrategyAction.HOLD, f.panicReversalDirection(rising, List(20) { BigDecimal.valueOf(100.0) }))
    }

    @Test
    fun `range squeeze blocks the entry while the range is compressed`() {
        val f =
            filter(
                rangeSqueezeEnabled = true,
                rangeSqueezeRangePeriod = 20,
                rangeSqueezeAtrPeriod = 50,
                rangeSqueezeMultiplier = 0.5,
            )
        val bars = compressedSeries() + volCandle(59, 100.0, range = 0.05)
        assertEquals(StrategyAction.HOLD, f.rangeSqueezeDirection(bars))
    }

    @Test
    fun `range squeeze buys the confirmed breakout of a compressed range`() {
        val f =
            filter(
                rangeSqueezeEnabled = true,
                rangeSqueezeRangePeriod = 20,
                rangeSqueezeAtrPeriod = 50,
                rangeSqueezeMultiplier = 0.5,
                rangeSqueezeLookbackBars = 2,
                rangeSqueezeRequireVolume = true,
                volumeSpikePeriod = 20,
                volumeSpikeMultiplier = 1.5,
            )
        val breakout = compressedSeries().dropLast(1) + volCandle(59, 120.0, range = 0.05, volume = 400)
        assertEquals(StrategyAction.BUY, f.rangeSqueezeDirection(breakout))
    }

    @Test
    fun `range squeeze sells the breakdown of a compressed range`() {
        val f =
            filter(
                rangeSqueezeEnabled = true,
                rangeSqueezeRangePeriod = 20,
                rangeSqueezeAtrPeriod = 50,
                rangeSqueezeMultiplier = 0.5,
                rangeSqueezeLookbackBars = 2,
                rangeSqueezeRequireVolume = true,
            )
        val breakdown = compressedSeries().dropLast(1) + volCandle(59, 80.0, range = 0.05, volume = 400)
        assertEquals(StrategyAction.SELL, f.rangeSqueezeDirection(breakdown))
    }

    @Test
    fun `range squeeze rejects a breakout without volume confirmation`() {
        val f =
            filter(
                rangeSqueezeEnabled = true,
                rangeSqueezeRangePeriod = 20,
                rangeSqueezeAtrPeriod = 50,
                rangeSqueezeMultiplier = 0.5,
                rangeSqueezeLookbackBars = 2,
                rangeSqueezeRequireVolume = true,
            )
        val breakout = compressedSeries().dropLast(1) + volCandle(59, 120.0, range = 0.05, volume = 100)
        assertEquals(StrategyAction.HOLD, f.rangeSqueezeDirection(breakout))
    }

    @Test
    fun `range squeeze passes through when the range was never compressed`() {
        val f =
            filter(
                rangeSqueezeEnabled = true,
                rangeSqueezeRangePeriod = 20,
                rangeSqueezeAtrPeriod = 50,
                rangeSqueezeMultiplier = 0.5,
                rangeSqueezeLookbackBars = 2,
            )
        val wide = (0 until 60).map { volCandle(it, 100.0 + it, range = 5.0) }
        assertNull(f.rangeSqueezeDirection(wide))
    }

    @Test
    fun `range squeeze is null when the filter is off`() {
        assertNull(filter().rangeSqueezeDirection(compressedSeries() + volCandle(59, 100.0)))
    }

    /** 40 широких баров на одной цене (ATR раздут) + 19 узких: сжатие относительно ATR. */
    private fun compressedSeries(): List<Candle> {
        val wide = (0 until 40).map { volCandle(it, 100.0, range = 5.0) }
        val tight = (40 until 59).map { volCandle(it, 100.0, range = 0.05) }
        return wide + tight
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

    /**
     * 24 плоских бара (100±1) и один импульсный бар с закрытием [lastClose] — окно для
     * проверки пробоя из сжатия. Плоских баров должно хватать, чтобы сжатие стало
     * вычислимым ДО импульса (нужно 20 баров для Боллинджера/Кельтнера), иначе «сжатие
     * перед пробоем» недоказуемо.
     */
    private fun flatThen(lastClose: Double): List<Candle> {
        val bars = (0 until 24).map { i -> candle(0, i, 100.0, 101.0, 99.0, 100.0) }
        return bars + candle(0, 24, 100.0, maxOf(101.0, lastClose + 1.0), minOf(99.0, lastClose - 1.0), lastClose)
    }

    /**
     * Seeded pseudo-random walk - keeps the squeeze-series regression test deterministic
     * while producing both squeeze and non-squeeze stretches.
     */
    private fun walk(
        count: Int,
        seed: Int = 42,
    ): List<Candle> {
        val rnd = kotlin.random.Random(seed)
        var p = 100.0
        return (0 until count).map { i ->
            p += (rnd.nextDouble() - 0.5) * 4.0
            candle(0, i, p, p + 0.6 + rnd.nextDouble(), p - 0.6 - rnd.nextDouble(), p)
        }
    }

    /**
     * Ряд свечей: линейный рост на [step] за бар от [start], диапазон бара ±1.
     */
    private fun candles(
        count: Int,
        start: Double,
        step: Double = 1.0,
    ): List<Candle> =
        (0 until count).map { i ->
            val p = start + step * i
            candle(0, i, p, p + 1.0, p - 1.0, p)
        }
}
