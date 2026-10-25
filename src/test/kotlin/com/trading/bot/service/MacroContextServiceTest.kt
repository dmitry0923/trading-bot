package com.trading.bot.service

import com.trading.bot.config.MacroConfig
import com.trading.bot.model.entity.CbrRateRecord
import com.trading.bot.repository.CbrRatesHistoryRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Провайдер макро-контекста: источник ключевой ставки (P2 c1).
 *
 * При `macro.live-rate-enabled=true` ставка берётся из ряда ЦБ
 * ([CbrRatesHistoryRepository]); при выключенном флаге, пустом ряде или ошибке
 * БД — статичная `macro.cbr-rate` (fail-soft, live-контур не падает).
 * Устаревшая ставка **не** подменяется конфигом (ступенчатая функция; конфиг не
 * свежее) — фиксируется только метрикой `macro.cbr_rate.live{status=STALE}`.
 * Сетевой путь USD/RUB здесь не трогается: проверяется [MacroContextService.latestKeyRateOrConfig].
 */
class MacroContextServiceTest {
    // 2026-10-18 15:00 МСК
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("Europe/Moscow"))

    @Test
    fun `live rate comes from history when enabled`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val repo = repositoryReturning(record("2026-10-16", "14.0"))
            val service = service(config = enabledConfig(), repository = repo, meterRegistry = registry)

            assertEquals(0, BigDecimal("14.0").compareTo(service.latestKeyRateOrConfig()))
            assertEquals(1.0, statusCount(registry, "OK"))
        }

    @Test
    fun `falls back to config when history is empty`(): Unit =
        runBlocking {
            val repo = repositoryReturning(null)
            val service = service(config = enabledConfig(), repository = repo)

            assertEquals(0, BigDecimal("16.0").compareTo(service.latestKeyRateOrConfig()))
        }

    @Test
    fun `falls back to config when latest row has no key rate`(): Unit =
        runBlocking {
            val repo = repositoryReturning(record("2026-10-16", keyRate = null))
            val service = service(config = enabledConfig(), repository = repo)

            assertEquals(0, BigDecimal("16.0").compareTo(service.latestKeyRateOrConfig()))
        }

    @Test
    fun `falls back to config when repository throws`(): Unit =
        runBlocking {
            val repo = Mockito.mock(CbrRatesHistoryRepository::class.java)
            Mockito.`when`(repo.findLatestKeyRateRecord()).thenThrow(RuntimeException("db down"))
            val service = service(config = enabledConfig(), repository = repo)

            assertEquals(0, BigDecimal("16.0").compareTo(service.latestKeyRateOrConfig()))
        }

    @Test
    fun `stale rate is still used and marked stale instead of config substitution`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            // 47 дней назад: старше live-rate-stale-days=14
            val repo = repositoryReturning(record("2026-09-01", "14.0"))
            val service = service(config = enabledConfig(), repository = repo, meterRegistry = registry)

            assertEquals(0, BigDecimal("14.0").compareTo(service.latestKeyRateOrConfig()))
            assertEquals(1.0, statusCount(registry, "STALE"))
            assertEquals(null, statusCounter(registry, "OK"))
        }

    @Test
    fun `fresh rate inside stale window is not marked stale`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val repo = repositoryReturning(record("2026-10-18", "14.0"))
            val service = service(config = enabledConfig(), repository = repo, meterRegistry = registry)

            service.latestKeyRateOrConfig()

            assertEquals(1.0, statusCount(registry, "OK"))
            assertEquals(null, statusCounter(registry, "STALE"))
        }

    @Test
    fun `uses config and never reads repository when disabled`(): Unit =
        runBlocking {
            val repo = Mockito.mock(CbrRatesHistoryRepository::class.java)
            val config = MacroConfig().apply { liveRateEnabled = false }
            val service = service(config = config, repository = repo)

            assertEquals(0, BigDecimal("16.0").compareTo(service.latestKeyRateOrConfig()))
            Mockito.verify(repo, Mockito.never()).findLatestKeyRateRecord()
        }

    @Test
    fun `uses config when repository bean is absent`(): Unit =
        runBlocking {
            val service = service(config = enabledConfig(), repository = null)

            assertEquals(0, BigDecimal("16.0").compareTo(service.latestKeyRateOrConfig()))
        }

    private fun enabledConfig(): MacroConfig = MacroConfig().apply { liveRateEnabled = true }

    private fun statusCount(
        registry: MeterRegistry,
        status: String,
    ): Double {
        val counter = statusCounter(registry, status)
        return counter?.count() ?: 0.0
    }

    private fun statusCounter(
        registry: MeterRegistry,
        status: String,
    ): Counter? {
        val found = registry.find("macro.cbr_rate.live")
        val tagged = found.tag("status", status)
        return tagged.counter()
    }

    private suspend fun repositoryReturning(record: CbrRateRecord?): CbrRatesHistoryRepository {
        val repo = Mockito.mock(CbrRatesHistoryRepository::class.java)
        Mockito.`when`(repo.findLatestKeyRateRecord()).thenReturn(record)
        return repo
    }

    private fun record(
        date: String,
        keyRate: String?,
    ): CbrRateRecord =
        CbrRateRecord(
            rateDate = LocalDate.parse(date),
            keyRate = keyRate?.toBigDecimal(),
        )

    private fun service(
        config: MacroConfig,
        repository: CbrRatesHistoryRepository?,
        meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    ): MacroContextService = MacroContextService(config, ObjectMapper(), meterRegistry, repository, clock)
}
