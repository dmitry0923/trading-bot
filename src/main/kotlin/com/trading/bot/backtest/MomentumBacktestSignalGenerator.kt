package com.trading.bot.backtest

import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Research-генератор time-series momentum + low-vol (docs/41–docs/44).
 *
 * Сигнал на закрытии бара `index`:
 * - `mom = close[index] / close[index − momentumBars] − 1`;
 * - `ATR(atrBars)%` и его перцентиль за `volLookbackBars`;
 * - вход только при `volPct <= maxVolPercentile` (low-vol);
 * - направление — знак momentum, иначе HOLD.
 *
 * Генератор использует только `candles[0..index]` (без look-ahead); исполнение
 * входа/выхода делает [BacktestEngine] (вход на открытии `index+1`, выход по
 * `maxHoldBars`/SL/TP).
 */
class MomentumBacktestSignalGenerator(
    private val momentumBars: Int = 20,
    private val atrBars: Int = 20,
    private val volLookbackBars: Int = 252,
    private val maxVolPercentile: Double = 0.5,
) : BacktestSignalGenerator {
    /**
     * Паритет с pre-screen (`docs/42`, §2): одна позиция на инструмент,
     * сигналы во время удержания игнорируются. Генератор не знает состояние
     * движка, поэтому ведёт виртуальное удержание: после сигнала на баре `i`
     * (вход на `i+1`, выход через 4 бара, т.е. на `i+5`) сигналы на барах
     * `i+1..i+5` не выдаются. Иначе `BacktestEngine` закрывает позицию по
     * REVERSAL, и A/B с pre-screen некорректен.
     */
    private val holdUntil: MutableMap<String, Int> = mutableMapOf()

    private val firstCandleTime: MutableMap<String, LocalDateTime?> = mutableMapOf()

    init {
        require(momentumBars > 0) { "momentumBars must be positive" }
        require(atrBars > 0) { "atrBars must be positive" }
        require(volLookbackBars > atrBars) { "volLookbackBars must exceed atrBars" }
        require(maxVolPercentile in 0.0..1.0) { "maxVolPercentile must be in 0..1" }
    }

    override suspend fun signal(
        ticker: String,
        candles: List<Candle>,
        index: Int,
        minBars: Int,
        cycleId: String,
    ): StrategyAction {
        val firstTime = candles.firstOrNull()?.time
        if (index == 0 || firstCandleTime[ticker] != firstTime) {
            holdUntil[ticker] = -1
            firstCandleTime[ticker] = firstTime
        }
        if (index <= (holdUntil[ticker] ?: -1)) return StrategyAction.HOLD

        val minIndex = volLookbackBars + atrBars - 2
        if (index < minIndex || index >= candles.size) return StrategyAction.HOLD

        val current = candles[index]
        val past = candles[index - momentumBars]
        val pastClose = past.closePrice.toDouble()
        if (pastClose <= 0.0 || !pastClose.isFinite()) return StrategyAction.HOLD
        val momentum = current.closePrice.toDouble() / pastClose - 1.0
        if (momentum == 0.0 || !momentum.isFinite()) return StrategyAction.HOLD

        val start = index - minIndex
        val trueRanges = DoubleArray(minIndex + 1)
        for (j in start..index) {
            val candle = candles[j]
            val high = candle.highPrice.toDouble()
            val low = candle.lowPrice.toDouble()
            val previousClose =
                if (j > 0) {
                    candles[j - 1].closePrice.toDouble()
                } else {
                    candle.closePrice.toDouble()
                }
            trueRanges[j - start] = maxOf(high - low, abs(high - previousClose), abs(low - previousClose))
        }

        val atrPercents = DoubleArray(volLookbackBars)
        for (k in 0 until volLookbackBars) {
            val j = index - volLookbackBars + 1 + k
            var sum = 0.0
            for (t in (j - atrBars + 1)..j) {
                sum += trueRanges[t - start]
            }
            val close = candles[j].closePrice.toDouble()
            atrPercents[k] = if (close > 0.0) (sum / atrBars) / close else 0.0
        }

        val currentVol = atrPercents[volLookbackBars - 1]
        val below = atrPercents.count { it <= currentVol }
        val volPercentile = below.toDouble() / volLookbackBars
        if (volPercentile > maxVolPercentile) return StrategyAction.HOLD

        val action = if (momentum > 0.0) StrategyAction.BUY else StrategyAction.SELL
        holdUntil[ticker] = index + 5
        return action
    }
}
