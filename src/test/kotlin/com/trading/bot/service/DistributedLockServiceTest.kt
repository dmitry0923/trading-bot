package com.trading.bot.service

import com.trading.bot.config.DistributedLockConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Unit-тесты распределённого лока [DistributedLockService].
 */
class DistributedLockServiceTest {
    private val config = DistributedLockConfig()
    private val redis = Mockito.mock(ReactiveStringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val valueOps = Mockito.mock(ReactiveValueOperations::class.java) as ReactiveValueOperations<String, String>
    private val meterRegistry = SimpleMeterRegistry()
    private val service = DistributedLockService(config, redis, meterRegistry)

    private var blockRuns = 0

    @BeforeEach
    fun setUp() {
        config.enabled = false
        blockRuns = 0
        Mockito.`when`(redis.opsForValue()).thenReturn(valueOps)
    }

    private fun acquireSucceeds() {
        Mockito
            .`when`(
                valueOps.setIfAbsent(
                    Mockito.any(String::class.java),
                    Mockito.any(String::class.java),
                    Mockito.any(Duration::class.java),
                ),
            ).thenReturn(Mono.just(true))
        Mockito
            .`when`(redis.execute(Mockito.any(RedisScript::class.java), Mockito.anyList(), Mockito.anyString()))
            .thenReturn(Flux.just(1L))
    }

    private fun acquireContended() {
        Mockito
            .`when`(
                valueOps.setIfAbsent(
                    Mockito.any(String::class.java),
                    Mockito.any(String::class.java),
                    Mockito.any(Duration::class.java),
                ),
            ).thenReturn(Mono.just(false))
    }

    private fun acquireThrows() {
        Mockito.`when`(redis.opsForValue()).thenThrow(RuntimeException("redis down"))
    }

    @Test
    fun `disabled lock runs block without touching redis`() =
        runBlocking {
            config.enabled = false
            val result = service.runExclusive("test-lock") { blockRuns++ }

            assertTrue(result)
            assertEquals(1, blockRuns)
        }

    @Test
    fun `successful acquire runs block and releases the key`() {
        runBlocking {
            config.enabled = true
            acquireSucceeds()

            val result = service.runExclusive("test-lock") { blockRuns++ }

            assertTrue(result)
            assertEquals(1, blockRuns)
            Mockito.verify(redis).execute(Mockito.any(RedisScript::class.java), Mockito.anyList(), Mockito.anyString())
        }
    }

    @Test
    fun `contended acquire skips block and returns false`() =
        runBlocking {
            config.enabled = true
            acquireContended()

            val result = service.runExclusive("test-lock") { blockRuns++ }

            assertFalse(result)
            assertEquals(0, blockRuns)
        }

    @Test
    fun `redis failure with fail-open still runs block`() =
        runBlocking {
            config.enabled = true
            acquireThrows()

            val result = service.runExclusive("test-lock") { blockRuns++ }

            assertTrue(result)
            assertEquals(1, blockRuns)
        }

    @Test
    fun `redis failure with fail-closed skips block`() =
        runBlocking {
            config.enabled = true
            acquireThrows()

            val result =
                service.runExclusive(name = "test-lock", failOpenOnError = false) { blockRuns++ }

            assertFalse(result)
            assertEquals(0, blockRuns)
        }

    @Test
    fun `renewal keeps lock alive beyond TTL and block completes`() =
        runBlocking {
            config.enabled = true
            acquireSucceeds()
            // renew вызывает execute(script, keys, token, ttlMs) — vararg `[String, Long]`;
            // any() матчит оба элемента независимо от типа (anyString() не подходит для Long).
            Mockito
                .`when`(redis.execute(Mockito.any(RedisScript::class.java), Mockito.anyList(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(1L))
            var completed = false

            // TTL=1s, критическая секция дольше TTL (1.5с). Watchdog продлевает
            // lease (renew возвращает 1), блок добегает до конца без потери лока.
            val result =
                service.runExclusive(
                    name = "long-lock",
                    ttlSeconds = 1,
                    block = {
                        delay(1500)
                        completed = true
                    },
                )

            assertTrue(result)
            assertTrue(completed)
        }

    @Test
    fun `lease loss cancels critical section and returns false`() =
        runBlocking {
            config.enabled = true
            acquireSucceeds()
            // renew (vararg [String, Long]) возвращает 0 → lease потерян; release (1 vararg) → 1.
            Mockito
                .`when`(redis.execute(Mockito.any(RedisScript::class.java), Mockito.anyList(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(0L))

            var cancelled = false
            val result =
                service.runExclusive(name = "lease-lost", ttlSeconds = 1) {
                    try {
                        delay(5000)
                    } catch (_: CancellationException) {
                        cancelled = true
                        throw CancellationException("cancelled by lease watchdog")
                    }
                }

            assertFalse(result)
            assertTrue(cancelled)
        }
}
