package com.trading.bot.domain.technical

import com.trading.bot.model.entity.Candle
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Кэш расчёта индикаторов по fingerprint (ticker + timeframe + last candle time).
 * Устраняет повторный расчёт RSI/ATR/MACD в рамках одного цикла, когда одни и те же
 * свечи передаются в несколько стратегий/агентов.
 *
 * TTL-эвикция: записи старше [MAX_AGE] удаляются при каждом обращении (lazy cleanup).
 * В торговом боте количество тикеров ограничено (20-50), поэтому полный scan на
 * каждом get() не является bottleneck.
 */
@Suppress("unused")
object IndicatorCache {
    private data class Key(
        val ticker: String,
        val timeframe: String,
        val lastCandleTime: LocalDateTime?,
    )

    private data class Entry(
        val indicators: IndicatorCalculator.Indicators,
        val cachedAt: Long = System.nanoTime(),
    )

    private val cache = ConcurrentHashMap<Key, Entry>()

    private val MAX_AGE: Duration = Duration.ofMinutes(5)

    /**
     * Возвращает кэшированные индикаторы или вычисляет новые через [calculator].
     *
     * @param ticker тикер инструмента
     * @param timeframe таймфрейм свечей (e.g. "1H", "15m")
     * @param candles свечи для расчёта
     * @param calculator функция расчёта индикаторов (обычно IndicatorCalculator::calculate)
     * @return индикаторы или null, если свечей недостаточно
     */
    fun getOrCalculate(
        ticker: String,
        timeframe: String,
        candles: List<Candle>,
        calculator: (List<Candle>) -> IndicatorCalculator.Indicators? = IndicatorCalculator::calculate,
    ): IndicatorCalculator.Indicators? {
        if (candles.isEmpty()) return null
        val key = Key(ticker, timeframe, candles.lastOrNull()?.time)

        evictStale()

        cache[key]?.let { entry ->
            return entry.indicators
        }

        val result = calculator(candles) ?: return null
        cache[key] = Entry(result)
        return result
    }

    /**
     * Инвалидация всех записей для тикера (например, при обновлении стратегии).
     */
    fun invalidate(ticker: String) {
        cache.keys.removeIf { it.ticker == ticker }
    }

    /**
     * Полная очистка кэша.
     */
    fun clear() {
        cache.clear()
    }

    private fun evictStale() {
        val now = System.nanoTime()
        val maxAgeNanos = MAX_AGE.toNanos()
        cache.entries.removeIf { (_, entry) -> now - entry.cachedAt > maxAgeNanos }
    }
}
