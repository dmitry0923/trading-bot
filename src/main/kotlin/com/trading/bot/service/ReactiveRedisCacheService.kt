package com.trading.bot.service

import com.trading.bot.model.dto.FeedbackCacheEntry
import com.trading.bot.model.entity.Strategy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * Реактивный Redis-кэш стратегий и feedback.
 *
 * Заменяет блокирующий RedisCacheService для немедленных операций
 * (getStrategy/saveStrategy/getAllStrategies), устраняя потребность
 * в BlockingDb.
 *
 * - Стратегии: TTL 15 минут (ключ strategy:{ticker})
 * - Feedback: TTL 60 минут (ключ feedback:{ticker}), валидность по хешу статистики
 * - Все операции защищены try/catch: при недоступности Redis сервис не падает
 */
@Service
class ReactiveRedisCacheService(
    private val reactiveRedisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}
    private val prefix = "strategy:"
    private val feedbackPrefix = "feedback:"
    private val ttl = Duration.ofMinutes(15)
    private val feedbackTtl = Duration.ofMinutes(60)

    suspend fun saveStrategy(s: Strategy) {
        try {
            reactiveRedisTemplate
                .opsForValue()
                .set("$prefix${s.ticker}", objectMapper.writeValueAsString(s), ttl)
                .awaitFirstOrNull()
        } catch (e: Exception) {
            logger.error(e) { "Reactive Redis save error" }
        }
    }

    suspend fun getStrategy(ticker: String): Strategy? =
        try {
            reactiveRedisTemplate
                .opsForValue()
                .get("$prefix$ticker")
                .awaitFirstOrNull()
                ?.let { objectMapper.readValue(it, Strategy::class.java) }
        } catch (e: Exception) {
            logger.error(e) { "Reactive Redis get error" }
            null
        }

    suspend fun getAllStrategies(tickers: List<String>): Map<String, Strategy> =
        tickers
            .mapNotNull { ticker ->
                getStrategy(ticker)?.let { s ->
                    ticker to s
                }
            }.toMap()

    suspend fun saveFeedback(
        ticker: String,
        feedbackJson: String,
        statsHash: String,
    ) {
        try {
            val entry = FeedbackCacheEntry(ticker, feedbackJson, statsHash)
            reactiveRedisTemplate
                .opsForValue()
                .set("$feedbackPrefix$ticker", objectMapper.writeValueAsString(entry), feedbackTtl)
                .awaitFirstOrNull()
        } catch (e: Exception) {
            logger.error(e) { "Reactive Redis feedback save error" }
        }
    }

    suspend fun getFeedback(
        ticker: String,
        currentStatsHash: String,
    ): String? =
        try {
            reactiveRedisTemplate
                .opsForValue()
                .get("$feedbackPrefix$ticker")
                .awaitFirstOrNull()
                ?.let {
                    val entry = objectMapper.readValue(it, FeedbackCacheEntry::class.java)
                    if (entry.statsHash == currentStatsHash) entry.feedbackJson else null
                }
        } catch (e: Exception) {
            logger.error(e) { "Reactive Redis feedback get error" }
            null
        }

    /**
     * Проверка доступности Redis через PING.
     * Используется TradingHealthIndicator для health-check.
     */
    suspend fun isAvailable(): Boolean =
        try {
            reactiveRedisTemplate.execute { connection ->
                connection.ping().map { it == "PONG" }
            }.awaitFirstOrNull() ?: false
        } catch (e: Exception) {
            logger.warn(e) { "Redis health check failed" }
            false
        }
}
