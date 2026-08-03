package com.trading.bot.service
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.model.Strategy
import mu.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class RedisCacheService(private val redisTemplate: StringRedisTemplate, private val objectMapper: ObjectMapper) {
    private val logger = KotlinLogging.logger {}
    private val prefix = "strategy:"
    private val ttl = Duration.ofMinutes(15)

    fun saveStrategy(s: Strategy) {
        try { redisTemplate.opsForValue().set("$prefix${s.ticker}", objectMapper.writeValueAsString(s), ttl) } 
        catch (e: Exception) { logger.error(e) { "Redis save error" } }
    }
    fun getStrategy(ticker: String): Strategy? {
        return try { redisTemplate.opsForValue().get("$prefix$ticker")?.let { objectMapper.readValue(it, Strategy::class.java) } } 
        catch (e: Exception) { logger.error(e) { "Redis get error" }; null }
    }
    fun getAllStrategies(tickers: List<String>): Map<String, Strategy> = tickers.mapNotNull { getStrategy(it)?.let { s -> it to s } }.toMap()
}
