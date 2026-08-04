package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.model.FeedbackCacheEntry
import com.trading.bot.model.Strategy
import mu.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Redis-кэш стратегий и результатов мета-анализa (feedback).
 *
 * - Стратегии: TTL 15 минут (ключ strategy:{ticker})
 * - Feedback: TTL 60 минут (ключ feedback:{ticker}), валидность по хешу статистики
 * - Все операции защищены try/catch: при недоступности Redis сервис не падает
 */
@Service
class RedisCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}
    private val prefix = "strategy:"
    private val feedbackPrefix = "feedback:"
    private val ttl = Duration.ofMinutes(15)
    private val feedbackTtl = Duration.ofMinutes(60)

    /**
     * Сохраняет стратегию в кэш с TTL 15 минут.
     *
     * @param s стратегия для сохранения
     */
    fun saveStrategy(s: Strategy) {
        try {
            redisTemplate.opsForValue().set("$prefix${s.ticker}", objectMapper.writeValueAsString(s), ttl)
        } catch (e: Exception) {
            logger.error(e) { "Redis save error" }
        }
    }

    /**
     * Достаёт стратегию из кэша по тикеру.
     *
     * @param ticker тикер инструмента
     * @return стратегия или null (нет в кэше / ошибка чтения)
     */
    fun getStrategy(ticker: String): Strategy? {
        return try {
            redisTemplate.opsForValue().get("$prefix$ticker")?.let {
                objectMapper.readValue(it, Strategy::class.java)
            }
        } catch (e: Exception) {
            logger.error(e) { "Redis get error" }
            null
        }
    }

    /**
     * Массовое получение стратегий по списку тикеров.
     *
     * @param tickers список тикеров
     * @return карта тикер -> стратегия (только найденные в кэше)
     */
    fun getAllStrategies(tickers: List<String>): Map<String, Strategy> =
        tickers.mapNotNull { ticker -> getStrategy(ticker)?.let { s -> ticker to s } }.toMap()

    /**
     * Сохраняет feedback-ответ в кэш с TTL 60 минут.
     *
     * @param ticker тикер инструмента
     * @param feedbackJson сериализованный JSON feedback
     * @param statsHash хеш статистики, по которому валидируется кэш
     */
    fun saveFeedback(ticker: String, feedbackJson: String, statsHash: String) {
        try {
            val entry = FeedbackCacheEntry(ticker, feedbackJson, statsHash)
            redisTemplate.opsForValue().set(
                "$feedbackPrefix$ticker",
                objectMapper.writeValueAsString(entry),
                feedbackTtl
            )
        } catch (e: Exception) {
            logger.error(e) { "Redis feedback save error" }
        }
    }

    /**
     * Достаёт закэшированный feedback, если хеш статистики совпадает.
     *
     * @param ticker тикер инструмента
     * @param currentStatsHash текущий хеш статистики
     * @return JSON feedback или null (нет в кэше / устарел / ошибка чтения)
     */
    fun getFeedback(ticker: String, currentStatsHash: String): String? {
        return try {
            redisTemplate.opsForValue().get("$feedbackPrefix$ticker")?.let {
                val entry = objectMapper.readValue(it, FeedbackCacheEntry::class.java)
                if (entry.statsHash == currentStatsHash) entry.feedbackJson else null
            }
        } catch (e: Exception) {
            logger.error(e) { "Redis feedback get error" }
            null
        }
    }
}
