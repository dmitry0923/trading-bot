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
import java.util.UUID

/**
 * Single-flight для LLM-вызовов (P0-аудит, research/llm-signal-source).
 *
 * Когда LLM становится источником сигнала, параллельные входные циклы могут
 * одновременно попасть на один и тот же семантический ключ и дублировать запросы
 * к провайдеру. При помощи Redis SETNX только один поток становится владельцем
 * лока и делает вызов; остальные получают от него запись из кэша либо (по таймауту
 * [LlmConfig.singleFlightWaitMs]) NEUTRAL fallback `SINGLE_FLIGHT_BUSY`.
 *
 * Ownership (review, P1): значением лока является UUID-токен владельца, а не «1».
 * [release] снимает лок ТОЛЬКО по своему токену (атомарный Lua compare-and-delete):
 * если владелец завис и его лок снялся по TTL, новый владелец не сможет случайно
 * освободить чужой лок.
 *
 * Fail-closed (review, P1): при недоступности Redis [acquire] возвращает
 * [AcquireResult.Unavailable] — вызывающий код отклоняет LLM-вызов (HOLD). Раньше
 * смешанная ошибка Redis означала «пропустить single-flight» (fail-open), теперь
 * LLM-путь обязан запретить вход, если не может гарантировать дедупликацию.
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

    /** Результат [acquire]: владелец (с токеном), вызов уже идёт, либо Redis недоступен. */
    sealed interface AcquireResult {
        /** Токен владельца — обязателен для [release], снимает только свой лок. */
        data class Owner(
            val token: String,
        ) : AcquireResult

        /** Другой поток уже выполняет вызов по этому семантическому ключу. */
        data object Busy : AcquireResult

        /** Redis недоступен — single-flight не гарантирован, fail-closed. */
        data object Unavailable : AcquireResult
    }

    /**
     * Попытка стать владельцем вызова по заданному семантическому ключу.
     *
     * @return null — single-flight выключен ([LlmConfig.singleFlightEnabled]=false):
     * вызывающий код идёт напрямую; иначе [AcquireResult.Owner] (обычно недопустимо,
     * лок получен), [AcquireResult.Busy] или [AcquireResult.Unavailable].
     */
    fun acquire(
        cacheKey: String,
        versionSeed: String? = null,
    ): AcquireResult? {
        if (!llmConfig.singleFlightEnabled) return null
        val token = UUID.randomUUID().toString()
        return try {
            val acquired =
                redisTemplate.opsForValue().setIfAbsent(
                    lockKey(cacheKey, versionSeed),
                    token,
                    Duration.ofSeconds(llmConfig.singleFlightLockTtlSeconds),
                )
            when (acquired) {
                true -> {
                    meterRegistry.counter("llm.single_flight.acquired").increment()
                    AcquireResult.Owner(token)
                }

                // null в Spring Data Redis означает «не удалось записать» (например
                // таймаут/ошибка) — трактуем как недоступность (fail-closed).
                null -> {
                    logger.warn { "Single-flight setIfAbsent returned null for $cacheKey -> unavailable" }
                    meterRegistry.counter("llm.single_flight.unavailable").increment()
                    AcquireResult.Unavailable
                }

                else -> {
                    AcquireResult.Busy
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Single-flight lock error for $cacheKey -> unavailable (fail-closed)" }
            meterRegistry.counter("llm.single_flight.unavailable").increment()
            AcquireResult.Unavailable
        }
    }

    /**
     * Освобождение лока ТОЛЬКО владельцем (compare-and-delete по токену):
     * если лок перехватил другой поток после TTL, чужой лок не снимается.
     */
    fun release(
        cacheKey: String,
        versionSeed: String? = null,
        ownerToken: String? = null,
    ) {
        if (ownerToken == null) return
        try {
            redisTemplate.execute(RELEASE_SCRIPT, listOf(lockKey(cacheKey, versionSeed)), ownerToken)
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
