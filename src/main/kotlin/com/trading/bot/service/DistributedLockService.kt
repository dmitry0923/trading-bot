package com.trading.bot.service

import com.trading.bot.config.DistributedLockConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
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
 * - failOpenOnError при сбое самого Redis (не конкуренция): `true` для фоновых
 *   планировщиков (не пропустить reconcile/close из-за недоступного Redis),
 *   `false` для входа в позицию (не открывать без лока).
 */
@Service
class DistributedLockService(
    private val config: DistributedLockConfig,
    private val reactiveRedisTemplate: ReactiveStringRedisTemplate,
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
        return coroutineScope {
            val blockJob = async { block() }
            val watchdog =
                launch {
                    val intervalMs = (ttlSeconds * 1000L) / 3
                    while (isActive) {
                        delay(intervalMs)
                        val renewed =
                            try {
                                renew(lock, ttlSeconds)
                            } catch (e: Exception) {
                                logger.error("Distributed lock renew failed for [${lock.name}]", e)
                                false
                            }
                        if (!renewed) {
                            logger.warn(
                                "Distributed lock lease lost for [${lock.name}] — cancelling critical section",
                            )
                            meterRegistry.counter(METRIC_LEASE_LOST, Tags.of(TAG_NAME, lock.name)).increment()
                            blockJob.cancel()
                            return@launch
                        }
                    }
                }
            try {
                meterRegistry.counter(METRIC_ACQUIRED, Tags.of(TAG_NAME, lock.name)).increment()
                blockJob.await()
                watchdog.cancel()
                true
            } catch (e: CancellationException) {
                watchdog.cancel()
                false
            } finally {
                try {
                    release(lock)
                } catch (e: Exception) {
                    logger.error("Distributed lock release failed for [{}]", lock.name, e)
                    meterRegistry.counter(METRIC_RELEASE_ERROR, Tags.of(TAG_NAME, lock.name)).increment()
                }
            }
        }
    }

    private suspend fun acquire(
        name: String,
        ttlSeconds: Long,
    ): Lock? {
        val token = UUID.randomUUID().toString()
        val acquired =
            reactiveRedisTemplate
                .opsForValue()
                .setIfAbsent(KEY_PREFIX + name, token, Duration.ofSeconds(ttlSeconds))
                .awaitFirstOrNull()
        return if (acquired == true) Lock(name, token) else null
    }

    private suspend fun release(lock: Lock): Boolean {
        val removed =
            reactiveRedisTemplate
                .execute(RELEASE_SCRIPT, listOf(lock.key), lock.token)
                .awaitFirstOrNull()
        return removed == 1L
    }

    private suspend fun renew(
        lock: Lock,
        ttlSeconds: Long,
    ): Boolean {
        val renewed =
            reactiveRedisTemplate
                .execute(RENEW_SCRIPT, listOf(lock.key), lock.token, ttlSeconds * 1000L)
                .awaitFirstOrNull()
        return renewed == 1L
    }

    companion object {
        private const val KEY_PREFIX = "distributed-lock:"

        private const val METRIC_PREFIX = "distributed.lock."
        private const val METRIC_ACQUIRED = METRIC_PREFIX + "acquired"
        private const val METRIC_CONTENDED = METRIC_PREFIX + "contended"
        private const val METRIC_SKIPPED = METRIC_PREFIX + "skipped"
        private const val METRIC_ERROR = METRIC_PREFIX + "error"
        private const val METRIC_RELEASE_ERROR = METRIC_PREFIX + "release.error"
        private const val METRIC_LEASE_LOST = METRIC_PREFIX + "lease.lost"
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

        private val RENEW_SCRIPT =
            DefaultRedisScript<Long>(
                """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('pexpire', KEYS[1], ARGV[2])
                else
                    return 0
                end
                """.trimIndent(),
                Long::class.javaObjectType,
            )
    }
}
