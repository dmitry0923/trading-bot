package com.trading.bot.backtest

import com.trading.bot.application.strategy.OnlineMlDirectionStrategy
import com.trading.bot.config.BacktestConfig
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Ветви [LiveStrategyBacktestSignalGenerator.signal], не покрытые основным тестом
 * (скан-19: 50 из 160): fail-closed поведение при сбое каждого фильтра
 * (ema-cross/volume-spike/squeeze/range-squeeze), «фильтр разрешил
 * противоположную сторону», ML-фильтр (сбой → как будто выключен, veto против
 * победителя, HOLD при обоих режимах blockOnUnknown), а также кэш предрассчитанных
 * рядов сжатия между барами.
 */
class LiveStrategyBacktestSignalGeneratorBranchesTest {
    private val baseTime = LocalDateTime.of(2026, 9, 1, 10, 0)
    private val minBars = 30

    private fun candle(
        index: Int,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long = 1_000L,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(open),
            highPrice = BigDecimal.valueOf(high),
            lowPrice = BigDecimal.valueOf(low),
            closePrice = BigDecimal.valueOf(close),
            volume = volume,
            time = baseTime.plusMinutes(10L * index),
        )

    /** Ровный восходящий тренд без откатов: детерминированный BUY на большинстве баров. */
    private fun ramp(count: Int = 120): List<Candle> {
        var prev = 100.0
        return (0 until count).map { i ->
            val close = 100.0 + i
            val bar = candle(i, prev, maxOf(prev, close) + 0.2, minOf(prev, close) - 0.2, close)
            prev = close
            bar
        }
    }

    private fun collect(
        generator: LiveStrategyBacktestSignalGenerator,
        candles: List<Candle> = ramp(),
    ): List<StrategyAction> =
        runBlocking {
            (minBars until candles.size).map { index ->
                generator.signal("SBER", candles, index, minBars, "test-cycle")
            }
        }

    /** Helper-паттерн Mockito: матчер для non-null LocalTime + реальный dummy. */
    private fun anyTime(): java.time.LocalTime {
        Mockito.any(java.time.LocalTime::class.java)
        return java.time.LocalTime.of(10, 0)
    }

    /** Helper-паттерн Mockito: матчер для non-null BigDecimal + реальный dummy. */
    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal("100")
    }

    /** Helper-паттерн Mockito: матчер для non-null enum + реальный dummy. */
    private fun anyAction(): StrategyAction {
        Mockito.any(StrategyAction::class.java)
        return StrategyAction.BUY
    }

    private fun anyContext(): StrategyContext {
        Mockito.any(StrategyContext::class.java)
        return StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(currentPrice = BigDecimal("100")),
            candles = emptyList(),
            indicators = null,
            cycleId = "matcher",
        )
    }

    private fun ml(decision: StrategyDecision): OnlineMlDirectionStrategy {
        val strategy = Mockito.mock(OnlineMlDirectionStrategy::class.java)
        runBlocking {
            Mockito.`when`(strategy.evaluate(anyContext())).thenReturn(decision)
        }
        return strategy
    }

    private fun throwingMl(): OnlineMlDirectionStrategy {
        val strategy = Mockito.mock(OnlineMlDirectionStrategy::class.java)
        runBlocking {
            Mockito.`when`(strategy.evaluate(anyContext())).thenThrow(IllegalStateException("ml down"))
        }
        return strategy
    }

    // ------------------------------------------------------------------- ML

    @Test
    fun `ml filter failure behaves as if it were absent`() {
        val baseline = collect(LiveStrategyBacktestSignalGenerator())
        val withBrokenMl = collect(LiveStrategyBacktestSignalGenerator(mlDirection = throwingMl()))

        assertTrue(baseline.any { it != StrategyAction.HOLD }, "стенд должен давать входы: $baseline")
        assertEquals(baseline, withBrokenMl, "сбой ML-фильтра не должен ни блокировать, ни добавлять входы")
    }

    @Test
    fun `ml filter failure blocks in fail-closed mode`() {
        // Регрессия скана-19: сбой evaluate() при blockOnUnknown=true раньше означал
        // «фильтра нет» (fail-open) и пропускал входы, которые фильтр обязан блокировать.
        val signals =
            collect(
                LiveStrategyBacktestSignalGenerator(
                    mlDirection = throwingMl(),
                    mlDirectionBlockOnUnknown = true,
                ),
            )

        assertTrue(signals.all { it == StrategyAction.HOLD }, "сбой ML при fail-closed — HOLD: $signals")
    }

    @Test
    fun `ml veto against the winning side blocks entries`() {
        val veto =
            ml(
                StrategyDecision(
                    action = StrategyAction.SELL,
                    targetPrice = BigDecimal("100"),
                    signalStrength = 0.9,
                    reasoning = "ml disagrees",
                ),
            )

        val signals = collect(LiveStrategyBacktestSignalGenerator(mlDirection = veto))

        assertTrue(signals.all { it == StrategyAction.HOLD }, "ML против направления победителя — HOLD: $signals")
    }

    @Test
    fun `ml hold blocks only in fail-closed mode`() {
        val undecided = ml(StrategyDecision.hold(BigDecimal("100"), "no verdict"))
        val baseline = collect(LiveStrategyBacktestSignalGenerator())

        val blocking =
            collect(
                LiveStrategyBacktestSignalGenerator(
                    mlDirection = undecided,
                    mlDirectionBlockOnUnknown = true,
                ),
            )
        assertTrue(blocking.all { it == StrategyAction.HOLD }, "blockOnUnknown=true — вход запрещён: $blocking")

        val passing =
            collect(
                LiveStrategyBacktestSignalGenerator(
                    mlDirection = ml(StrategyDecision.hold(BigDecimal("100"), "no verdict")),
                    mlDirectionBlockOnUnknown = false,
                ),
            )
        assertEquals(baseline, passing, "blockOnUnknown=false — фильтр не влияет")
    }

    // ------------------------------------------------------- fail-closed фильтры

    @Test
    fun `ema cross failure blocks in fail-closed mode and passes otherwise`() {
        val baseline = collect(LiveStrategyBacktestSignalGenerator())

        val blockingFilters =
            Mockito.mock(EntryFilters::class.java).apply {
                Mockito.`when`(emaCrossEnabled).thenReturn(true)
                Mockito.`when`(emaCrossBlockOnUnknown).thenReturn(true)
                Mockito.`when`(emaCrossDirection(Mockito.anyList())).thenThrow(IllegalStateException("ema down"))
            }
        val blocking = collect(LiveStrategyBacktestSignalGenerator(entryFilters = blockingFilters))
        assertTrue(blocking.all { it == StrategyAction.HOLD }, "fail-closed: сбой расчёта = запрет входа: $blocking")

        val passingFilters =
            Mockito.mock(EntryFilters::class.java).apply {
                Mockito.`when`(emaCrossEnabled).thenReturn(true)
                Mockito.`when`(emaCrossBlockOnUnknown).thenReturn(false)
                Mockito.`when`(emaCrossDirection(Mockito.anyList())).thenThrow(IllegalStateException("ema down"))
            }
        assertEquals(
            baseline,
            collect(LiveStrategyBacktestSignalGenerator(entryFilters = passingFilters)),
            "blockOnUnknown=false — сбой не блокирует",
        )
    }

    @Test
    fun `volume spike failure blocks in fail-closed mode`() {
        val filters =
            Mockito.mock(EntryFilters::class.java).apply {
                Mockito.`when`(volumeSpikeEnabled).thenReturn(true)
                Mockito.`when`(volumeSpikeBlockOnUnknown).thenReturn(true)
                Mockito.`when`(volumeSpikeAllows(Mockito.anyList())).thenThrow(IllegalStateException("volume down"))
            }

        val signals = collect(LiveStrategyBacktestSignalGenerator(entryFilters = filters))

        assertTrue(signals.all { it == StrategyAction.HOLD }, "объёмный фильтр fail-closed: $signals")
    }

    @Test
    fun `squeeze failure blocks in fail-closed mode`() {
        val filters =
            Mockito.mock(EntryFilters::class.java).apply {
                Mockito.`when`(squeezeEnabled).thenReturn(true)
                Mockito.`when`(squeezeBlockOnUnknown).thenReturn(true)
                Mockito
                    .`when`(
                        squeezeDirection(
                            Mockito.anyList(),
                            Mockito.nullable(BooleanArray::class.java),
                            Mockito.anyInt(),
                        ),
                    ).thenThrow(IllegalStateException("squeeze down"))
            }

        val signals = collect(LiveStrategyBacktestSignalGenerator(entryFilters = filters))

        assertTrue(signals.all { it == StrategyAction.HOLD }, "Bollinger-squeeze fail-closed: $signals")
    }

    @Test
    fun `range squeeze failure blocks in fail-closed mode`() {
        val filters =
            Mockito.mock(EntryFilters::class.java).apply {
                Mockito.`when`(rangeSqueezeEnabled).thenReturn(true)
                Mockito.`when`(rangeSqueezeBlockOnUnknown).thenReturn(true)
                Mockito
                    .`when`(
                        rangeSqueezeDirection(Mockito.anyList(), Mockito.nullable(BooleanArray::class.java)),
                    ).thenThrow(IllegalStateException("range down"))
            }

        val signals = collect(LiveStrategyBacktestSignalGenerator(entryFilters = filters))

        assertTrue(signals.all { it == StrategyAction.HOLD }, "range-squeeze fail-closed: $signals")
    }

    // ------------------------------------------ разрешённая сторона и сбои расчёта

    @Test
    fun `orb side opposite to the winner blocks the entry`() {
        val filters =
            Mockito.mock(EntryFilters::class.java).apply {
                Mockito.`when`(orbDirection(Mockito.anyList(), Mockito.anyInt())).thenReturn(StrategyAction.SELL)
            }

        val signals = collect(LiveStrategyBacktestSignalGenerator(entryFilters = filters))

        assertTrue(signals.all { it == StrategyAction.HOLD }, "ORB разрешил только SELL, вход BUY запрещён: $signals")
    }

    @Test
    fun `entry gate failure is treated as not blocking`() {
        val baseline = collect(LiveStrategyBacktestSignalGenerator())
        val filters =
            Mockito.mock(EntryFilters::class.java).apply {
                Mockito
                    .`when`(blocksEntry(anyTime(), anyBigDecimal(), Mockito.anyList()))
                    .thenThrow(IllegalStateException("session down"))
            }

        assertEquals(
            baseline,
            collect(LiveStrategyBacktestSignalGenerator(entryFilters = filters)),
            "сбой session/pullback-гейта не должен блокировать вход",
        )
    }

    @Test
    fun `time direction failure is treated as not blocking`() {
        val baseline = collect(LiveStrategyBacktestSignalGenerator())
        val filters =
            Mockito.mock(EntryFilters::class.java).apply {
                Mockito
                    .`when`(blocksDirection(anyTime(), anyAction()))
                    .thenThrow(IllegalStateException("time down"))
            }

        assertEquals(
            baseline,
            collect(LiveStrategyBacktestSignalGenerator(entryFilters = filters)),
            "сбой time×direction не должен блокировать вход",
        )
    }

    @Test
    fun `higher timeframe filter failures pass the entry through`() {
        val baseline = collect(LiveStrategyBacktestSignalGenerator())

        val filters =
            Mockito.mock(EntryFilters::class.java).apply {
                Mockito.`when`(panicReversalEnabled).thenReturn(true)
                Mockito.`when`(macroTrendEnabled).thenReturn(true)
                Mockito.`when`(vwapMrEnabled).thenReturn(true)
                Mockito
                    .`when`(panicReversalDirection(Mockito.anyList(), Mockito.anyList()))
                    .thenThrow(IllegalStateException("panic down"))
                Mockito
                    .`when`(macroTrendDirection(Mockito.anyList(), Mockito.anyList()))
                    .thenThrow(IllegalStateException("macro down"))
                Mockito
                    .`when`(vwapMrDirection(Mockito.anyList(), Mockito.anyList()))
                    .thenThrow(IllegalStateException("vwap down"))
            }

        assertEquals(
            baseline,
            collect(LiveStrategyBacktestSignalGenerator(entryFilters = filters)),
            "сбой старших ТФ-фильтров = фильтр не вмешивается",
        )
    }

    // ---------------------------------------------------------- кэш рядов сжатия

    @Test
    fun `squeeze and range squeeze series are cached per candle list identity`() {
        val overrides =
            EntryFilterOverrides(
                squeezeEnabled = true,
                squeezeBlockOnUnknown = false,
                rangeSqueezeEnabled = true,
                rangeSqueezeBlockOnUnknown = false,
            )
        val config = BacktestConfig()
        val generator =
            LiveStrategyBacktestSignalGenerator(
                entryFilters = EntryFilters.from(config, overrides),
            )
        val candles = ramp()

        val first = collect(generator, candles)
        val second = collect(generator, candles)
        val third = collect(generator, candles.toList())

        assertEquals(first, second, "кэш по идентичности списка — результат воспроизводим")
        assertEquals(first, third, "копия списка даёт тот же результат (пересчёт ряда)")
    }
}
