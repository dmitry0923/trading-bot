package com.trading.bot.service

import com.trading.bot.model.Candle
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
     *
     * @return ATR в единицах цены или null, если недостаточно данных
     */
    fun calculateAtr(
        ticker: String,
        timeframe: String,
        period: Int = 14,
    ): BigDecimal? {
        val candles = getRecentCandles(ticker, timeframe, period + 1)
        if (candles.size < period + 1) return null
        var sum = BigDecimal.ZERO
        for (i in 1 until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].closePrice
            val tr =
                listOf(
                    c.highPrice.subtract(c.lowPrice),
                    c.highPrice.subtract(prevClose).abs(),
                    c.lowPrice.subtract(prevClose).abs(),
                ).maxByOrNull { it } ?: BigDecimal.ZERO
            sum = sum.add(tr)
        }
        return sum.divide(BigDecimal(period), 4, RoundingMode.HALF_UP)
    }

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
}
