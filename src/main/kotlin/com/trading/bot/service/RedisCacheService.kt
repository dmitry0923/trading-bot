package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.model.FeedbackCacheEntry
import com.trading.bot.model.Strategy
import mu.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

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

    fun saveStrategy(s: Strategy) {
        try {
            redisTemplate.opsForValue().set("$prefix${s.ticker}", objectMapper.writeValueAsString(s), ttl)
        } catch (e: Exception) {
            logger.error(e) { "Redis save error" }
        }
    }

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

    fun getAllStrategies(tickers: List<String>): Map<String, Strategy> =
        tickers.mapNotNull { ticker -> getStrategy(ticker)?.let { s -> ticker to s } }.toMap()

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
