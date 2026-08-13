package com.trading.bot.integration

import com.trading.bot.model.entity.BotSettings
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.SettingsRepository
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.SettingsService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Chaos-тесты: отключение PostgreSQL → graceful degradation (roadmap 13.3.3).
 *
 * Проверяют, что при падении Postgres бот не падает:
 *   - in-memory слой настроек (SettingsService.getSettings) продолжает работать;
 *   - Redis-кэш свечей (не-BD слой) продолжает работать;
 *   - после восстановления Postgres соединения (R2DBC) восстанавливаются
 *     и round-trip чтение/запись снова работает.
 *
 * Авария моделируется `docker pause` (см. [ChaosTestSupport]): данные и схема
 * сохраняются, контейнер не пересоздаётся — восстановление честное.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChaosPostgresIntegrationTest {
    companion object {
        @Container
        val postgres = chaosPostgres(15433)

        @Container
        val redis = chaosRedis(16380)

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
        }
    }

    @Autowired
    lateinit var settingsService: SettingsService

    @Autowired
    lateinit var settingsRepository: SettingsRepository

    @Autowired
    lateinit var candleCache: CandleCacheService

    @Test
    fun `in-memory settings survive postgres outage and db recovers after unpause`() {
        val before = settingsService.getSettings()
        assertNotNull(before)
        val loadedBefore = runBlocking { settingsRepository.loadSettings() }
        assertNotNull(loadedBefore, "pre-условие: настройки загружаются из БД")

        pauseContainer(postgres)
        try {
            val during = settingsService.getSettings()
            assertTrue(during.tradingEnabled, "in-memory hot path работает при недоступном Postgres")
            assertEquals(before, during, "значения настроек не изменились в памяти")

            candleCache.addCandle(candle())
            val candles = candleCache.getRecentCandles("SBER", "MINUTE_10", 10)
            assertEquals(1, candles.size, "не-DB слой (Redis-кэш свечей) продолжает работать")
        } finally {
            unpauseContainer(postgres)
            awaitUntil("database connection recovers after postgres unpause") {
                runBlocking { settingsRepository.loadSettings() } != null
            }
            val updated = before.copy(tradingEnabled = false)
            runBlocking { settingsRepository.saveSettings(updated) }
            val loaded = runBlocking { settingsRepository.loadSettings() }
            assertNotNull(loaded, "round-trip после восстановления")
            assertFalse(loaded!!.tradingEnabled, "запись после восстановления дошла до БД")
        }
    }

    private fun candle(): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal("100"),
            highPrice = BigDecimal("101"),
            lowPrice = BigDecimal("99"),
            closePrice = BigDecimal("100.5"),
            volume = 10L,
            time = LocalDateTime.of(2026, 8, 12, 10, 0),
        )
}
