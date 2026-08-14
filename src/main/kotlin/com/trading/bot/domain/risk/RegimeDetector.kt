package com.trading.bot.domain.risk

import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.entity.Candle

/**
 * Конфигурация классификации per-ticker режима. Значения по умолчанию
 * используются тестами; продакшен-значения приходят из [com.trading.bot.config.RiskConfig].
 */
data class RegimeDetectionConfig(
    /** Окно (свечей) для определения направления по выравниванию EMA12/EMA26. */
    val directionWindowBars: Int = 10,
    /** Окно (свечей) для определения Crash/Pump по движению цены. */
    val moveWindowBars: Int = 6,
    /** Падение за [moveWindowBars] свечей в единицах ATR(14), при котором режим = CRASH. */
    val crashAtrMultiplier: Double = 2.0,
    /** Рост за [moveWindowBars] свечей в единицах ATR(14), при котором режим = PUMP. */
    val pumpAtrMultiplier: Double = 2.0,
    /** Перцентиль объёма, ниже которого ликвидность = THIN. */
    val lowVolumePercentile: Double = 10.0,
    /** Перцентиль ATR%, ниже которого волатильность = LOW. */
    val lowVolatilityPercentile: Double = 40.0,
    /** Перцентиль ATR%, ниже которого волатильность = NORMAL. */
    val normalVolatilityPercentile: Double = 70.0,
    /** Перцентиль ATR%, начиная с которого волатильность = EXTREME. */
    val highVolatilityPercentile: Double = 90.0,
    /** Глубина скользящего ATR% для оценки перцентиля волатильности. */
    val volatilityHistoryBars: Int = 50,
    /** Минимум свечей для классификации (иначе fail-safe [PerTickerRegime.UNKNOWN]). */
    val minBars: Int = 20,
)

/**
 * Детерминированный классификатор per-ticker рыночного режима.
 *
 * Чисто функциональный (без состояния и I/O) — единая математика для стратегического
 * этапа (Strategy Selector) и риск-слоя (размер позиции).
 *
 *   - направление: выравнивание EMA12/EMA26 на последних [RegimeDetectionConfig.directionWindowBars]
 *     барах — >= (N-2) баров вверх → TREND_UP, <= 2 → TREND_DOWN, иначе RANGE;
 *   - волатильность: перцентильный ранг текущего ATR% относительно скользящего
 *     распределения ATR% (аналог логики [MarketRegimeClassifier], но per-ticker);
 *   - событие Crash/Pump: движение цены за [RegimeDetectionConfig.moveWindowBars] баров,
 *     нормализованное по ATR(14) (единая шкала для разных волатильностей и
 *     таймфреймов): падение ниже -crashAtrMultiplier·ATR / рост выше
 *     pumpAtrMultiplier·ATR;
 *   - ликвидность: перцентильный ранг последнего объёма в распределении окна.
 */
object RegimeDetector {
    /**
     * Классифицирует режим инструмента по свечам.
     *
     * @param candles свечи тикера (минимум [RegimeDetectionConfig.minBars])
     * @param config параметры классификации
     * @return [PerTickerRegime] или fail-safe [PerTickerRegime.UNKNOWN] при недостатке данных
     */
    fun detect(
        candles: List<Candle>,
        config: RegimeDetectionConfig = RegimeDetectionConfig(),
    ): PerTickerRegime {
        if (candles.size < config.minBars) return PerTickerRegime.UNKNOWN

        val direction = detectDirection(candles, config)
        val volatility = detectVolatility(candles, config)
        val liquidity = detectLiquidity(candles, config)
        val event = detectEvent(candles, config)
        return PerTickerRegime(direction, volatility, liquidity, event)
    }

    private fun detectDirection(
        candles: List<Candle>,
        config: RegimeDetectionConfig,
    ): RegimeDirection {
        if (candles.size < 26) return RegimeDirection.RANGE
        val closes = candles.map { it.closePrice }
        val emaFast = IndicatorCalculator.ema(closes, 12)
        val emaSlow = IndicatorCalculator.ema(closes, 26)

        val window = config.directionWindowBars.coerceAtMost(candles.size)
        val offset = candles.size - window
        val alignedUp =
            (0 until window).count { i ->
                emaFast[offset + i] > emaSlow[offset + i]
            }
        return when {
            alignedUp >= window - 2 -> RegimeDirection.TREND_UP
            alignedUp <= 2 -> RegimeDirection.TREND_DOWN
            else -> RegimeDirection.RANGE
        }
    }

    private fun detectVolatility(
        candles: List<Candle>,
        config: RegimeDetectionConfig,
    ): RegimeVolatility {
        if (candles.size < 15) return RegimeVolatility.NORMAL

        val historyBars = config.volatilityHistoryBars.coerceAtMost(candles.size - 14)
        val start = candles.size - historyBars
        val atrPctSeries =
            (start until candles.size).map { i ->
                val window = candles.subList(i - 14, i + 1)
                val atr = IndicatorCalculator.atr(window, 14)
                val price = candles[i].closePrice.toDouble()
                if (price > 0.0) atr / price * 100.0 else 0.0
            }
        if (atrPctSeries.size < 2) return RegimeVolatility.NORMAL

        val current = atrPctSeries.last()
        val rank = MarketRegimeClassifier.percentileRank(atrPctSeries.dropLast(1), current)
        return when {
            rank < config.lowVolatilityPercentile -> RegimeVolatility.LOW
            rank < config.normalVolatilityPercentile -> RegimeVolatility.NORMAL
            rank < config.highVolatilityPercentile -> RegimeVolatility.HIGH
            else -> RegimeVolatility.EXTREME
        }
    }

    private fun detectLiquidity(
        candles: List<Candle>,
        config: RegimeDetectionConfig,
    ): RegimeLiquidity {
        if (candles.size < 2) return RegimeLiquidity.NORMAL
        val volumes = candles.map { it.volume.toDouble() }
        val current = volumes.last()
        val rank = MarketRegimeClassifier.percentileRank(volumes.dropLast(1), current)
        return if (rank < config.lowVolumePercentile) RegimeLiquidity.THIN else RegimeLiquidity.NORMAL
    }

    private fun detectEvent(
        candles: List<Candle>,
        config: RegimeDetectionConfig,
    ): MarketEvent {
        val window = candles.takeLast(config.moveWindowBars)
        if (window.size < config.moveWindowBars) return MarketEvent.NONE
        val atr = IndicatorCalculator.atr(candles.takeLast(15), 14)
        if (atr <= 0.0) return MarketEvent.NONE
        val start = window.first().openPrice.toDouble()
        val end = window.last().closePrice.toDouble()
        if (start <= 0.0) return MarketEvent.NONE

        val moveAtr = (end - start) / atr
        return when {
            moveAtr <= -config.crashAtrMultiplier -> MarketEvent.CRASH
            moveAtr >= config.pumpAtrMultiplier -> MarketEvent.PUMP
            else -> MarketEvent.NONE
        }
    }
}
