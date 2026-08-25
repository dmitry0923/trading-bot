package com.trading.bot.service

import com.trading.bot.domain.risk.Atr
import com.trading.bot.model.entity.Candle
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.ZoneOffset

/**
 * Кэш свечей в Redis (Sorted Set по timestamp).
 *
 * Ключ: `candles:{ticker}:{timeframe}`, member — JSON свечи, score — epoch millis времени свечи.
 * - [addCandle]/[addCandles]: ZADD + обрезка до [maxCandlesPerKey] + TTL 24 часа
 * - [getRecentCandles]: ZREVRANGE (O(log N) + K) без обращения к PostgreSQL
 * - [calculateAtr]/[calculateSma]: агрегаты, предвычисляемые из кэша
 *
 * Все операции защищены try/catch: при недоступности Redis сервис не падает.
 */
@Service
class CandleCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}
    private val prefix = "candles:"
    private val ttl = Duration.ofHours(24)
    private val maxCandlesPerKey = 500

    private fun key(
        ticker: String,
        timeframe: String,
    ): String = "$prefix$ticker:$timeframe"

    private fun scoreOf(candle: Candle): Double = candle.time.toEpochSecond(ZoneOffset.UTC) * 1000.0

    /**
     * Сохраняет свечу в кэш (ZADD + trim + TTL).
     */
    fun addCandle(candle: Candle) {
        try {
            val k = key(candle.ticker, candle.timeframe)
            redisTemplate.opsForZSet().add(k, objectMapper.writeValueAsString(candle), scoreOf(candle))
            redisTemplate.opsForZSet().removeRange(k, 0, -(maxCandlesPerKey + 1).toLong())
            redisTemplate.expire(k, ttl)
        } catch (e: Exception) {
            logger.error(e) { "Candle cache save error" }
        }
    }

    /**
     * Массовое сохранение свечей (например, после загрузки из MOEX).
     */
    fun addCandles(candles: List<Candle>) {
        candles.forEach { addCandle(it) }
    }

    /**
     * Удаляет все свечи из кэша (для тестов: гарантирует изоляцию между тестами,
     * т.к. [addCandles] — additive и свечи с разными timestamp'ами накапливаются).
     */
    fun clear() {
        try {
            val keys = redisTemplate.keys("$prefix*") ?: return
            if (keys.isNotEmpty()) {
                redisTemplate.delete(keys)
            }
        } catch (e: Exception) {
            logger.error(e) { "Candle cache clear error" }
        }
    }

    /**
     * Последние [limit] свечей в порядке возрастания времени.
     *
     * @return пустой список, если свечей нет или Redis недоступен
     */
    fun getRecentCandles(
        ticker: String,
        timeframe: String,
        limit: Int,
    ): List<Candle> {
        return try {
            val members =
                redisTemplate.opsForZSet().reverseRange(key(ticker, timeframe), 0, (limit - 1).toLong())
                    ?: return emptyList()
            members
                .mapNotNull { raw ->
                    try {
                        objectMapper.readValue(raw, Candle::class.java)
                    } catch (_: Exception) {
                        null
                    }
                }.sortedBy { it.time }
        } catch (e: Exception) {
            logger.error(e) { "Candle cache read error" }
            emptyList()
        }
    }

    /**
     * ATR(period) по закрытым свечам из кэша.
     *
     * TR(i) = max(high-low, |high-prevClose|, |low-prevClose|); ATR = среднее TR.
     * Математика — в чистом [com.trading.bot.domain.risk.Atr] (единый источник
     * с backtest).
     *
     * @return ATR в единицах цены или null, если недостаточно данных
     */
    fun calculateAtr(
        ticker: String,
        timeframe: String,
        period: Int = 14,
    ): BigDecimal? = Atr.calculate(getRecentCandles(ticker, timeframe, period + 1), period)

    /**
     * SMA(period) по ценам закрытия из кэша.
     *
     * @return средняя цена закрытия или null, если недостаточно данных
     */
    fun calculateSma(
        ticker: String,
        timeframe: String,
        period: Int,
    ): BigDecimal? {
        val candles = getRecentCandles(ticker, timeframe, period)
        if (candles.size < period) return null
        val sum = candles.sumOf { it.closePrice }
        return sum.divide(BigDecimal(period), 4, RoundingMode.HALF_UP)
    }

    /**
     * Realized volatility по лог-доходностям цен закрытия, в % за период свечи.
     *
     * Для [lookback] последних свечей считаются лог-доходности
     * r(i) = ln(close(i) / close(i-1)), stddev по ним и переводится в %:
     *   volPercent = stddev(r) * 100.
     *
     * Горизонт задаётся таймфреймом: DAY_1 → дневная волатильность,
     * MINUTE_10 → волатильность за 10 минут (для дневного эквивалента
     * масштабируется sqrt(свечей в сессии)).
     *
     * @param ticker тикер инструмента
     * @param timeframe таймфрейм свечей
     * @param lookback глубина расчёта (минимум 3 свечи для stddev)
     * @return волатильность в % за период или null, если данных недостаточно
     */
    fun calculateRealizedVolatility(
        ticker: String,
        timeframe: String,
        lookback: Int,
    ): Double? {
        if (lookback < 3) return null
        val candles = getRecentCandles(ticker, timeframe, lookback + 1)
        if (candles.size < lookback + 1) return null
        val returns = ArrayList<Double>(lookback)
        for (i in 1 until candles.size) {
            val prev = candles[i - 1].closePrice
            val curr = candles[i].closePrice
            if (prev <= BigDecimal.ZERO || curr <= BigDecimal.ZERO) continue
            returns.add(kotlin.math.ln(curr.divide(prev, 8, RoundingMode.HALF_UP).toDouble()))
        }
        if (returns.size < 2) return null
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
        return kotlin.math.sqrt(variance) * 100.0
    }
}
