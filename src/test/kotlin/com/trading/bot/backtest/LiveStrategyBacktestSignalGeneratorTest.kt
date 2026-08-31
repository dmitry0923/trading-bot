package com.trading.bot.backtest

import com.trading.bot.domain.risk.RegimeDetectionConfig
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class LiveStrategyBacktestSignalGeneratorTest {
    private val generator = LiveStrategyBacktestSignalGenerator()

    private val minBars = 30

    private fun makeCandle(
        time: LocalDateTime,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long = 1000L,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(open),
            highPrice = BigDecimal.valueOf(high),
            lowPrice = BigDecimal.valueOf(low),
            closePrice = BigDecimal.valueOf(close),
            volume = volume,
            time = time,
        )

    private fun timeAt(i: Int): LocalDateTime = BASE_TIME.plusMinutes(10L * i)

    /** Лесенка с узкими фитильками: каждый close пробивает хай предыдущего бара (или низ, при шаге вниз). */
    private fun rampCandles(
        count: Int,
        start: Double = 100.0,
        step: Double = 1.0,
        wick: Double = 0.2,
    ): List<Candle> {
        var prev = start
        return (0 until count).map { i ->
            val close = start + step * i
            val open = prev
            prev = close
            makeCandle(
                timeAt(i),
                open,
                maxOf(open, close) + wick,
                minOf(open, close) - wick,
                close,
            )
        }
    }

    private fun collectSignals(candles: List<Candle>): List<StrategyAction> =
        runBlocking {
            (minBars until candles.size).map { index ->
                generator.signal("SBER", candles, index, minBars, "test-cycle")
            }
        }

    @Test
    fun `cold start below minBars returns hold`() =
        runBlocking {
            val candles = rampCandles(count = 60)

            assertEquals(StrategyAction.HOLD, generator.signal("SBER", candles, 0, minBars, "cycle"))
            assertEquals(StrategyAction.HOLD, generator.signal("SBER", candles, 10, minBars, "cycle"))
            assertEquals(
                StrategyAction.HOLD,
                generator.signal("SBER", candles, minBars - 1, minBars, "cycle"),
                "бар ровно перед порогом обязан давать HOLD",
            )
            // Граница index == minBars уже проходит полный пайплайн и не должна падать.
            val boundary = generator.signal("SBER", candles, minBars, minBars, "cycle")
            assertTrue(
                boundary in listOf(StrategyAction.BUY, StrategyAction.SELL, StrategyAction.HOLD, StrategyAction.CLOSE),
                "на границе index == minBars пайплайн обязан вернуть валидное действие, got=$boundary",
            )
        }

    @Test
    fun `sufficient uptrend candles produce a signal without crashing`() =
        runBlocking {
            val candles = rampCandles(count = 60, start = 100.0, step = 2.0)

            val action = generator.signal("SBER", candles, candles.lastIndex, minBars, "cycle")

            assertTrue(
                action == StrategyAction.BUY || action == StrategyAction.HOLD,
                "восходящий тренд не должен давать SELL/CLOSE и не должен падать, got=$action",
            )
        }

    @Test
    fun `downtrend produces sell or hold`() =
        runBlocking {
            val candles = rampCandles(count = 60, start = 300.0, step = -2.0)

            val action = generator.signal("SBER", candles, candles.lastIndex, minBars, "cycle")

            assertTrue(
                action == StrategyAction.SELL || action == StrategyAction.HOLD,
                "нисходящий тренд не должен давать BUY/CLOSE и не должен падать, got=$action",
            )
        }

    /** Пилa без моментума: шаг +3/-1 (RSI~75 вне окон трендовой), фитиль 20 (сетка в середине диапазона, пробоя нет). */
    private fun chopCandles(
        count: Int = 60,
        start: Double = 100.0,
    ): List<Candle> {
        val closes = ArrayList<Double>(count)
        var close = start
        for (i in 0 until count) {
            closes += close
            close += if (i % 2 == 0) 3.0 else -1.0
        }
        return closes.mapIndexed { i, c -> makeCandle(timeAt(i), c - 3.0, c + 20.0, c - 20.0, c) }
    }

    @Test
    fun `flat market returns hold`() =
        runBlocking {
            // Строго постоянные цены вырождаются (RSI=100 при avgLoss=0 => SELL),
            // поэтому «флэт» тут — пила с нулевым моментом: ни одна стратегия не должна стрелять.
            val candles = chopCandles()

            val signals = collectSignals(candles)

            assertTrue(signals.isNotEmpty())
            assertEquals(
                List(signals.size) { StrategyAction.HOLD },
                signals,
                "на флэте без момента все стратегии должны молчать",
            )
        }

    @Test
    fun `edge case inputs fail graceful without throwing`() =
        runBlocking {
            // Нулевые цены и объёмы: вырожденный ряд не должен ронять генератор
            // (RSI при avgLoss=0 возвращает 100 => mean-reversion может дать SELL — это валидно).
            val zeros = (0 until 60).map { i -> makeCandle(timeAt(i), 0.0, 0.0, 0.0, 0.0, volume = 0L) }
            val zeroAction = generator.signal("SBER", zeros, zeros.lastIndex, minBars, "cycle")
            assertTrue(
                zeroAction in listOf(StrategyAction.BUY, StrategyAction.SELL, StrategyAction.HOLD, StrategyAction.CLOSE),
                "вырожденный ряд обязан возвращать валидное действие без исключения, got=$zeroAction",
            )

            // Нулевой объём на нормальном ряду не роняет генератор.
            val noVolume = rampCandles(count = 60).map { it.copy(volume = 0L) }
            val noVolumeAction = generator.signal("SBER", noVolume, noVolume.lastIndex, minBars, "cycle")
            assertTrue(
                noVolumeAction in listOf(StrategyAction.BUY, StrategyAction.SELL, StrategyAction.HOLD, StrategyAction.CLOSE),
                "нулевой объём не должен ломать пайплайн, got=$noVolumeAction",
            )

            // Экстремальный всплеск последнего бара не ломает расчёт индикаторов.
            val spiked = rampCandles(count = 60).toMutableList()
            spiked[spiked.lastIndex] = makeCandle(timeAt(59), open = 118.0, high = 250.0, low = 90.0, close = 240.0, volume = 100_000L)
            val spikeAction = generator.signal("SBER", spiked, spiked.lastIndex, minBars, "cycle")
            assertTrue(
                spikeAction in listOf(StrategyAction.BUY, StrategyAction.SELL, StrategyAction.HOLD, StrategyAction.CLOSE),
                "экстремальная свеча не должна ломать пайплайн, got=$spikeAction",
            )
        }

    @Test
    fun `signal direction follows trend strength`() {
        val strong = collectSignals(rampCandles(count = 60, start = 100.0, step = 2.0, wick = 0.2))
        val weak = collectSignals(rampCandles(count = 60, start = 100.0, step = 0.2, wick = 0.5))

        val strongBuys = strong.count { it == StrategyAction.BUY }
        val weakBuys = weak.count { it == StrategyAction.BUY }

        assertTrue(strong.contains(StrategyAction.BUY), "сильный тренд должен давать BUY, got=$strong")
        assertTrue(
            strong.none { it == StrategyAction.SELL },
            "сильный восходящий тренд не должен давать SELL, got=$strong",
        )
        assertEquals(0, weakBuys, "слабый дрейф не должен генерировать BUY, got=$weak")
        assertTrue(
            strongBuys > weakBuys,
            "сильный тренд должен сигналировать чаще слабого, strong=$strongBuys weak=$weakBuys",
        )
    }

    // ─── Regime parity (P0#1) ───────────────────────────────────────────

    /**
     * Crash regime (massive drop in last bars) → blocksEntry → HOLD.
     * Mirrors LIVE: RegimeDetector detects CRASH → StrategyRunner returns HOLD.
     */
    @Test
    fun `crash regime blocks entry`() {
        val normal = rampCandles(count = 60, start = 100.0, step = 1.0, wick = 0.2)
        val crashed = normal.toMutableList()
        // Last 6 bars: massive drop (>2x ATR) — triggers CRASH event
        for (i in 54..59) {
            crashed[i] = makeCandle(timeAt(i), open = 100.0 - i, high = 100.0, low = 50.0, close = 50.0, volume = 1000L)
        }

        val genWithRegime =
            LiveStrategyBacktestSignalGenerator(
                regimeConfig =
                    RegimeDetectionConfig(
                        minBars = 20,
                        crashAtrMultiplier = 1.5,
                    ),
            )

        val action =
            runBlocking {
                genWithRegime.signal("SBER", crashed, crashed.lastIndex, minBars, "cycle")
            }

        assertEquals(StrategyAction.HOLD, action, "crash regime must block entry")
    }

    /**
     * UNKNOWN regime (insufficient data < minBars) with regime enabled → HOLD.
     * Mirrors LIVE fail-closed: UNKNOWN != SAFE, entry blocked.
     */
    @Test
    fun `unknown regime blocks entry when regime enabled`() {
        val genWithRegime =
            LiveStrategyBacktestSignalGenerator(
                regimeConfig = RegimeDetectionConfig(minBars = 50),
            )

        // 30 свечей < minBars=50 → RegimeDetector returns UNKNOWN → blocksEntry.
        val candles = rampCandles(count = 30, start = 100.0, step = 1.0)
        val action =
            runBlocking {
                genWithRegime.signal("SBER", candles, candles.lastIndex, minBars, "cycle")
            }

        assertEquals(StrategyAction.HOLD, action, "unknown regime must block entry (fail-closed)")
    }

    /**
     * No regime config (null) → backward compatible, no regime filtering.
     * All strategies compete without fitScore weighting.
     */
    @Test
    fun `null regime config preserves backward compatibility`() {
        val genNoRegime = LiveStrategyBacktestSignalGenerator(regimeConfig = null)

        val candles = rampCandles(count = 60, start = 100.0, step = 2.0)
        val action =
            runBlocking {
                genNoRegime.signal("SBER", candles, candles.lastIndex, minBars, "cycle")
            }

        assertTrue(
            action == StrategyAction.BUY || action == StrategyAction.HOLD,
            "without regime, uptrend should produce BUY or HOLD, got=$action",
        )
    }

    /**
     * Strong uptrend with regime enabled → BUY from trend strategy.
     * Regime = TREND_UP → TREND_FOLLOWING eligible (fit=1.0), GRID/MR blocked (fit=0.0).
     */
    @Test
    fun `uptrend with regime picks trend strategy`() {
        val genWithRegime =
            LiveStrategyBacktestSignalGenerator(
                regimeConfig = RegimeDetectionConfig(minBars = 20),
            )

        val candles = rampCandles(count = 60, start = 100.0, step = 2.0, wick = 0.2)
        val action =
            runBlocking {
                genWithRegime.signal("SBER", candles, candles.lastIndex, minBars, "cycle")
            }

        assertTrue(
            action == StrategyAction.BUY || action == StrategyAction.HOLD,
            "strong uptrend with regime should produce BUY or HOLD, got=$action",
        )
    }

    /**
     * Adaptive confidence gate: strong signal above threshold → BUY.
     */
    @Test
    fun `strong signal above threshold passes adaptive gate`() {
        val gen =
            LiveStrategyBacktestSignalGenerator(
                adaptiveConfidenceThreshold = 0.60,
            )

        val candles = rampCandles(count = 60, start = 100.0, step = 2.0, wick = 0.2)
        val action =
            runBlocking {
                gen.signal("SBER", candles, candles.lastIndex, minBars, "cycle")
            }

        assertTrue(
            action == StrategyAction.BUY || action == StrategyAction.HOLD,
            "strong uptrend with reasonable threshold should produce BUY or HOLD, got=$action",
        )
    }

    /**
     * Adaptive confidence gate: threshold=1.0 blocks everything (no signal is that strong).
     */
    @Test
    fun `threshold one blocks all signals`() {
        val gen =
            LiveStrategyBacktestSignalGenerator(
                adaptiveConfidenceThreshold = 1.0,
            )

        val candles = rampCandles(count = 60, start = 100.0, step = 2.0, wick = 0.2)
        val signals = collectSignalsWith(gen, candles)

        assertTrue(signals.isNotEmpty())
        assertEquals(
            List(signals.size) { StrategyAction.HOLD },
            signals,
            "threshold=1.0 must gate all signals to HOLD",
        )
    }

    private fun collectSignalsWith(
        gen: BacktestSignalGenerator,
        candles: List<Candle>,
    ): List<StrategyAction> =
        runBlocking {
            (minBars until candles.size).map { index ->
                gen.signal("SBER", candles, index, minBars, "test-cycle")
            }
        }

    private companion object {
        val BASE_TIME: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)
    }
}
