package com.trading.bot.infrastructure.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.LlmConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import kotlin.math.roundToInt

/**
 * Semantic Cache поверх Redis.
 *
 * Ключ: SHA-256("agent:ticker:marketFingerprint").
 * marketFingerprint = округлённая цена (1 знак) + округлённый RSI (целое) + trend + volatilityRegime.
 * TTL по умолчанию 10 минут (совпадает с таймфреймом агентов).
 * Хранит LlmResponse сериализованным в JSON.
 */
@Component
class SemanticCache(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val llmConfig: LlmConfig
) {
    private val logger = KotlinLogging.logger {}
    private val prefix = "llm:semantic:"

    private val ttl: Duration
        get() = Duration.ofMinutes(llmConfig.semanticCacheTtlMinutes)

    /**
     * Семантический отпечаток рынка для инвариантности ключа.
     * Одинаковая рыночная ситуация → одинаковый ключ → попадание в кэш.
     */
    fun fingerprint(price: BigDecimal, rsi: Double, trend: String, volatilityRegime: String): String {
        val pricePart = price.setScale(1, RoundingMode.HALF_UP).toPlainString()
        val rsiPart = rsi.roundToInt().coerceIn(0, 100)
        return "$pricePart:$rsiPart:$trend:$volatilityRegime"
    }

    fun key(agent: String, ticker: String, fingerprint: String): String {
        val raw = "$agent:$ticker:$fingerprint"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return prefix + digest.joinToString("") { "%02x".format(it) }
    }

    fun get(agent: String, ticker: String, fingerprint: String): LlmResponse? {
        if (!llmConfig.semanticCacheEnabled) {
            meterRegistry.counter("llm.cache.miss", Tags.of("agent", agent)).increment()
            return null
        }
        val key = key(agent, ticker, fingerprint)
        return try {
            redisTemplate.opsForValue().get(key)?.let { json ->
                objectMapper.readValue(json, LlmResponse::class.java).copy(fromCache = true).also {
                    meterRegistry.counter("llm.cache.hit", Tags.of("agent", agent)).increment()
                    logger.debug { "Semantic cache HIT $agent:$ticker (${it.latencyMs}ms)" }
                }
            } ?: run {
                meterRegistry.counter("llm.cache.miss", Tags.of("agent", agent)).increment()
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Semantic cache read error for $agent:$ticker" }
            meterRegistry.counter("llm.cache.error", Tags.of("agent", agent)).increment()
            null
        }
    }

    fun put(agent: String, ticker: String, fingerprint: String, response: LlmResponse) {
        if (!llmConfig.semanticCacheEnabled || response.isFallback) return
        val key = key(agent, ticker, fingerprint)
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response), ttl)
            logger.debug { "Semantic cache PUT $agent:$ticker ttl=${ttl.seconds}s" }
        } catch (e: Exception) {
            logger.warn(e) { "Semantic cache write error for $agent:$ticker" }
            meterRegistry.counter("llm.cache.error", Tags.of("agent", agent)).increment()
        }
    }
}
