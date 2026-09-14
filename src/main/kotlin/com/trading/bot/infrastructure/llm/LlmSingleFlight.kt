package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration

/**
 * Single-flight для LLM-вызовов (P0-аудит, research/llm-signal-source).
 *
 * Когда LLM становится источником сигнала, параллельные входные циклы могут
 * одновременно попасть на один и тот же семантический ключ и дублировать запросы
 * к провайдеру. При помощи Redis SETNX только один поток становится владельцем
 * лока и делает вызов; остальные получают от него запись из кэша либо (по таймауту
 * [LlmConfig.singleFlightWaitMs]) NEUTRAL fallback `SINGLE_FLIGHT_BUSY`.
 *
 * Лок живёт [LlmConfig.singleFlightLockTtlSeconds] — сбой владельца (например
 * зависание вызова) не блокирует остальных навсегда: после TTL они смогут
 * захватить лок сами.
 */
@Component
class LlmSingleFlight(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    private val llmConfig: LlmConfig,
) {
    private val logger = KotlinLogging.logger {}

    private fun lockKey(
        cacheKey: String,
        versionSeed: String?,
    ): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$cacheKey:${versionSeed ?: ""}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "llm:sf:$digest"
    }

    /**
     * Попытка стать владельцем вызова по заданному семантическому ключу.
     *
     * @return true — лок получен (вызов провайдера разрешён); false — вызов уже
     * выполняется другим потоком.
     */
    fun acquire(
        cacheKey: String,
        versionSeed: String? = null,
    ): Boolean {
        if (!llmConfig.singleFlightEnabled) return true
        return try {
            val acquired =
                redisTemplate.opsForValue().setIfAbsent(
                    lockKey(cacheKey, versionSeed),
                    "1",
                    Duration.ofSeconds(llmConfig.singleFlightLockTtlSeconds),
                )
            if (acquired == true) {
                meterRegistry.counter("llm.single_flight.acquired").increment()
            }
            acquired == true
        } catch (e: Exception) {
            logger.warn(e) { "Single-flight lock error for $cacheKey" }
            // Redis недоступен — не блокируем критический путь (один вызов всё равно
            // надёжнее, чем ждать кэш, которого не будет).
            true
        }
    }

    /** Освобождение лока владельцем (compare-and-delete — снимает только свой лок). */
    fun release(
        cacheKey: String,
        versionSeed: String? = null,
    ) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, listOf(lockKey(cacheKey, versionSeed)), "1")
            meterRegistry.counter("llm.single_flight.released").increment()
        } catch (e: Exception) {
            logger.warn(e) { "Single-flight release error for $cacheKey" }
        }
    }

    companion object {
        private val RELEASE_SCRIPT: RedisScript<Long> =
            DefaultRedisScript(
                // language=lua
                """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0L
                """.trimIndent(),
                Long::class.javaObjectType,
            )
    }
}
