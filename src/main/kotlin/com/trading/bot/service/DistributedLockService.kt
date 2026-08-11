package com.trading.bot.service

import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.infrastructure.db.BlockingDb
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/**
 * Распределённый лок поверх Redis (см. docs/02-architecture.md, раздел 2.6).
 *
 * Позволяет запускать несколько реплик бота без гонок: владелец ключа `distributed-lock:<name>`
 * фиксируется атомарным `SET key token NX PX ttl`, освобождение — Lua-скриптом
 * (удаляет ключ только если принадлежит текущему владельцу).
 *
 * Контракты:
 * - TTL обязателен — надёжное освобождение при падении реплики (флаг «лидер жив»);
 * - владелец уникален (UUID на попытку) — отменённый критический прогон не освобождает
 *   ключ чужого прогона;
 * - одиночная инсталляция работает без Redis: при `distributed-lock.enabled=false`
 *   [runExclusive] просто исполняет блок;
 * - [failOpenOnError] при сбое самого Redis (не конкуренция): `true` для фоновых
 *   планировщиков (не пропустить reconcile/close из-за недоступного Redis),
 *   `false` для входа в позицию (не открывать без лока).
 */
@Service
class DistributedLockService(
    private val config: DistributedLockConfig,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(DistributedLockService::class.java)

    private data class Lock(
        val name: String,
        val token: String,
    ) {
        val key: String get() = KEY_PREFIX + name
    }

    suspend fun runExclusive(
        name: String,
        ttlSeconds: Long = config.schedulerTtlSeconds,
        failOpenOnError: Boolean = true,
        block: suspend () -> Unit,
    ): Boolean {
        if (!config.enabled) {
            block()
            return true
        }
        val lock =
            try {
                acquire(name, ttlSeconds)
            } catch (e: Exception) {
                logger.error("Distributed lock acquire failed for [$name]", e)
                meterRegistry.counter(METRIC_ERROR, Tags.of(TAG_NAME, name)).increment()
                if (failOpenOnError) {
                    logger.warn("Fail-open: running [$name] without lock (Redis unavailable)")
                    block()
                    return true
                }
                meterRegistry.counter(METRIC_SKIPPED, Tags.of(TAG_NAME, name)).increment()
                return false
            }
        if (lock == null) {
            meterRegistry.counter(METRIC_CONTENDED, Tags.of(TAG_NAME, name)).increment()
            return false
        }
        return try {
            meterRegistry.counter(METRIC_ACQUIRED, Tags.of(TAG_NAME, name)).increment()
            block()
            true
        } finally {
            try {
                release(lock)
            } catch (e: Exception) {
                logger.error("Distributed lock release failed for [{}]", name, e)
                meterRegistry.counter(METRIC_RELEASE_ERROR, Tags.of(TAG_NAME, name)).increment()
            }
        }
    }

    private suspend fun acquire(
        name: String,
        ttlSeconds: Long,
    ): Lock? =
        BlockingDb.io {
            val token = UUID.randomUUID().toString()
            val acquired =
                redisTemplate
                    .opsForValue()
                    .setIfAbsent(KEY_PREFIX + name, token, Duration.ofSeconds(ttlSeconds))
            if (acquired == true) Lock(name, token) else null
        }

    private suspend fun release(lock: Lock): Boolean =
        BlockingDb.io {
            val removed = redisTemplate.execute(RELEASE_SCRIPT, listOf(lock.key), lock.token) as? Long
            removed == 1L
        }

    companion object {
        private const val KEY_PREFIX = "distributed-lock:"

        private const val METRIC_PREFIX = "distributed.lock."
        private const val METRIC_ACQUIRED = METRIC_PREFIX + "acquired"
        private const val METRIC_CONTENDED = METRIC_PREFIX + "contended"
        private const val METRIC_SKIPPED = METRIC_PREFIX + "skipped"
        private const val METRIC_ERROR = METRIC_PREFIX + "error"
        private const val METRIC_RELEASE_ERROR = METRIC_PREFIX + "release.error"
        private const val TAG_NAME = "name"

        private val RELEASE_SCRIPT =
            DefaultRedisScript<Long>(
                """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                else
                    return 0
                end
                """.trimIndent(),
                Long::class.javaObjectType,
            )
    }
}
