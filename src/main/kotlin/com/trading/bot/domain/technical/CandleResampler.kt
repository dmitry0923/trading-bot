package com.trading.bot.domain.technical

import com.trading.bot.model.entity.Candle
import java.time.LocalDateTime

/**
 * Ресемплер свечей в старший таймфрейм (roadmap v2.5, multi-timeframe).
 *
 * Агрегирует свечи младшего ТФ (по умолчанию 10-мин) в часовые/дневные OHLCV-бары:
 * open — первая, high/low — экстремумы, close — последняя, volume — сумма, время —
 * начало бакета. Поддерживаемые цели: `HOUR_1`/`H1` и `DAY_1`/`D1`.
 *
 * `completedBefore` — point-in-time защита от lookahead: бакет включается, только
 * если он полностью завершён к этому моменту (`start + duration <= completedBefore`).
 * Live передаёт текущий момент (дроп неполного текущего часа), бэктест — время бара
 * (используются только завершённые к этому бару свечи старшего ТФ).
 */
object CandleResampler {
    private val DURATION_MINUTES: Map<String, Long> =
        mapOf(
            "HOUR_1" to 60L,
            "H1" to 60L,
            "DAY_1" to 1440L,
            "D1" to 1440L,
        )

    /**
     * Длительность бакета целевого таймфрейма в минутах.
     *
     * @throws IllegalArgumentException для неподдерживаемого таймфрейма
     */
    fun durationMinutes(targetTimeframe: String): Long =
        DURATION_MINUTES[targetTimeframe]
            ?: throw IllegalArgumentException(
                "Unsupported higher timeframe '$targetTimeframe'; supported: ${DURATION_MINUTES.keys}",
            )

    /**
     * Агрегирует свечи в старший таймфрейм.
     *
     * @param candles исходные свечи (любого таймфрейма, сортируются по времени)
     * @param targetTimeframe целевой таймфрейм (HOUR_1/H1, DAY_1/D1)
     * @param completedBefore если задан — включаются только бакеты, завершённые
     *   к этому моменту (нет lookahead)
     * @return свечи старшего таймфрейма, отсортированные по времени (могут быть пустыми)
     */
    fun resample(
        candles: List<Candle>,
        targetTimeframe: String,
        completedBefore: LocalDateTime? = null,
    ): List<Candle> {
        val duration = durationMinutes(targetTimeframe)
        val buckets = LinkedHashMap<LocalDateTime, MutableList<Candle>>()
        candles.sortedBy { it.time }.forEach { candle ->
            val bucketStart = bucketStart(candle.time, duration)
            buckets.computeIfAbsent(bucketStart) { mutableListOf() }.add(candle)
        }
        val resampled = ArrayList<Candle>(buckets.size)
        for ((start, group) in buckets) {
            if (completedBefore != null && start.plusMinutes(duration) > completedBefore) continue
            resampled +=
                Candle(
                    id = null,
                    ticker = group.first().ticker,
                    timeframe = targetTimeframe,
                    openPrice = group.first().openPrice,
                    highPrice = group.maxOf { it.highPrice },
                    lowPrice = group.minOf { it.lowPrice },
                    closePrice = group.last().closePrice,
                    volume = group.sumOf { it.volume },
                    time = start,
                )
        }
        resampled.sortBy { it.time }
        return resampled
    }

    private fun bucketStart(
        time: LocalDateTime,
        durationMinutes: Long,
    ): LocalDateTime =
        if (durationMinutes >= 1440) {
            time.toLocalDate().atStartOfDay()
        } else {
            time.withMinute(0).withSecond(0).withNano(0)
        }
}
