package com.trading.bot.integration

import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.Strategy
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.DistributedLockService
import com.trading.bot.service.EmergencyStopService
import com.trading.bot.service.EmergencyStopSource
import com.trading.bot.service.RedisCacheService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Chaos-тесты: отключение Redis → graceful degradation (roadmap 13.3.3).
 *
 * Проверяют, что при падении Redis бот не падает:
 *   - кэши свечей/стратегий/feedback fail-open (пустой список / null);
 *   - semantic cache fail-open (промах, продолжает работу с LLM);
 *   - emergency stop работает локально in-memory;
 *   - distributed lock: fail-open для фоновых планировщиков, fail-closed для входа;
 *   - после перезапуска Redis все сервисы восстанавливаются.
 *
 * Контейнеры на фиксированных портах — перезапуск Redis сохраняет адрес,
 * и Spring-контекст переживает аварию (см. [ChaosTestSupport]).
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChaosRedisIntegrationTest {
    companion object {
        @Container
        val postgres = chaosPostgres(15432)

        @Container
        val redis = chaosRedis(16379)

        @DynamicPropertySource
        @JvmStatic
        @Suppress("unused") // Вызывается рефлексивно Spring TestContext через @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            val host = "127.0.0.1"

            registry.add("spring.datasource.url") { "jdbc:postgresql://$host:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://$host:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)

            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }

            registry.add("distributed-lock.enabled") { "true" }
        }
    }

    @Autowired
    lateinit var candleCache: CandleCacheService

    @Autowired
    lateinit var redisCache: RedisCacheService

    @Autowired
    lateinit var semanticCache: SemanticCache

    @Autowired
    lateinit var emergencyStop: EmergencyStopService

    @Autowired
    lateinit var lockService: DistributedLockService

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    private fun candle(price: Long = 100): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(price),
            highPrice = BigDecimal(price).add(BigDecimal.ONE),
            lowPrice = BigDecimal(price).subtract(BigDecimal.ONE),
            closePrice = BigDecimal(price),
            volume = 10L,
            time = LocalDateTime.of(2026, 8, 12, 10, 0),
        )

    private fun strategy(): Strategy =
        Strategy(
            ticker = "SBER",
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("101"),
            quantity = 10,
            signalStrength = 0.8,
            reasoning = "chaos",
            cycleId = "chaos-1",
            validUntil = LocalDateTime.now().plusMinutes(5),
        )

    /** Перезапуск Redis теряет данные (контейнер пересоздаётся) — проба всегда пишет заново. */
    private fun restartRedis() {
        redis.start()
        awaitUntil("redis is back after restart") {
            redisTemplate.opsForValue().set("chaos.probe", "1")
            redisTemplate.opsForValue().get("chaos.probe") == "1"
        }
    }

    @Test
    fun `candle cache fails open when redis is down and recovers after restart`() {
        candleCache.addCandle(candle())
        assertEquals(1, candleCache.getRecentCandles("SBER", "MINUTE_10", 10).size)

        redis.stop()
        try {
            assertTrue(
                candleCache.getRecentCandles("SBER", "MINUTE_10", 10).isEmpty(),
                "fail-open: кэш свечей возвращает пустой список при недоступном Redis",
            )
            candleCache.addCandle(candle(200))
        } finally {
            restartRedis()
            awaitUntil("candle cache recovers after Redis restart") {
                candleCache.addCandle(candle(300))
                candleCache.getRecentCandles("SBER", "MINUTE_10", 10).isNotEmpty()
            }
        }
    }

    @Test
    fun `strategy and feedback cache fail open when redis is down`() {
        redisCache.saveStrategy(strategy())
        redisCache.saveFeedback("SBER", "{}", "hash-1")

        redis.stop()
        try {
            assertNull(redisCache.getStrategy("SBER"), "fail-open: стратегия = null при недоступном Redis")
            assertNull(redisCache.getFeedback("SBER", "hash-1"), "fail-open: feedback = null при недоступном Redis")
            redisCache.saveStrategy(strategy())
            redisCache.saveFeedback("SBER", "{}", "hash-2")
        } finally {
            restartRedis()
            awaitUntil("strategy cache recovers after Redis restart") {
                redisCache.saveStrategy(strategy())
                redisCache.getStrategy("SBER") != null
            }
        }
    }

    @Test
    fun `semantic cache fails open when redis is down`() {
        val fingerprint = semanticCache.fingerprint(BigDecimal("100"), 50.0, "UP", "LOW")

        redis.stop()
        try {
            assertNull(semanticCache.get("technical", "SBER", fingerprint), "fail-open: semantic cache промах при недоступном Redis")
            semanticCache.put("technical", "SBER", fingerprint, LlmResponse(content = "{}"))
            val errors = meterRegistry.counter("llm.cache.error", Tags.of("agent", "technical")).count()
            assertTrue(errors >= 1.0, "fail-open фиксируется метрикой llm.cache.error, было: $errors")
        } finally {
            restartRedis()
            awaitUntil("semantic cache recovers after Redis restart") {
                semanticCache.put("technical", "SBER", fingerprint, LlmResponse(content = "{}"))
                semanticCache.get("technical", "SBER", fingerprint) != null
            }
        }
    }

    @Test
    fun `emergency stop remains functional in memory when redis is down`() {
        redis.stop()
        try {
            val closed =
                runBlocking {
                    emergencyStop.stop("chaos: redis down", EmergencyStopSource.MANUAL, liquidate = false)
                }
            assertEquals(0, closed, "liquidate=false — позиции не закрываются")
            assertTrue(emergencyStop.isActive(), "аварийная остановка сработала локально при недоступном Redis")
            val stops = meterRegistry.counter("bot.emergency_stop", Tags.of("source", "MANUAL")).count()
            assertTrue(stops >= 1.0, "метрика bot.emergency_stop зафиксирована, было: $stops")
        } finally {
            runBlocking { emergencyStop.resume() }
            restartRedis()
        }
    }

    @Test
    fun `distributed lock fails open for schedulers and closed for entry when redis is down`() {
        redis.stop()
        try {
            var ran = false
            val open =
                runBlocking {
                    lockService.runExclusive("chaos", failOpenOnError = true) { ran = true }
                }
            assertTrue(open, "fail-open: планировщик выполняется без лока при недоступном Redis")
            assertTrue(ran, "блок планировщика реально выполнен")

            var skipped = true
            val closed =
                runBlocking {
                    lockService.runExclusive("chaos", failOpenOnError = false) { skipped = false }
                }
            assertFalse(closed, "fail-closed: вход не выполняется без лока при недоступном Redis")
            assertTrue(skipped, "блок входа не выполнен")

            val errors = meterRegistry.counter("distributed.lock.error", Tags.of("name", "chaos")).count()
            val skips = meterRegistry.counter("distributed.lock.skipped", Tags.of("name", "chaos")).count()
            assertTrue(errors >= 1.0, "метрика distributed.lock.error зафиксирована, было: $errors")
            assertTrue(skips >= 1.0, "метрика distributed.lock.skipped зафиксирована, было: $skips")
        } finally {
            restartRedis()
        }
    }
}
