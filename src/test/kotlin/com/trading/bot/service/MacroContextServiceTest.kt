package com.trading.bot.service

import com.trading.bot.config.MacroConfig
import com.trading.bot.repository.CbrRatesHistoryRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * Провайдер макро-контекста: источник ключевой ставки (P2 c1).
 *
 * При `macro.live-rate-enabled=true` ставка берётся из ряда ЦБ
 * ([CbrRatesHistoryRepository]); при выключенном флаге, пустом ряде или ошибке
 * БД — статичная `macro.cbr-rate` (fail-soft, live-контур не падает).
 * Сетевой путь USD/RUB здесь не трогается: проверяется [MacroContextService.latestKeyRateOrConfig].
 */
class MacroContextServiceTest {
    @Test
    fun `live rate comes from history when enabled`() =
        runBlocking {
            val repo = Mockito.mock(CbrRatesHistoryRepository::class.java)
            Mockito.`when`(repo.findLatestKeyRate()).thenReturn(BigDecimal("14.0"))
            val service = service(config = MacroConfig().apply { liveRateEnabled = true }, repository = repo)

            assertEquals(0, BigDecimal("14.0").compareTo(service.latestKeyRateOrConfig()))
        }

    @Test
    fun `falls back to config when history is empty`() =
        runBlocking {
            val repo = Mockito.mock(CbrRatesHistoryRepository::class.java)
            val service = service(config = MacroConfig().apply { liveRateEnabled = true }, repository = repo)

            assertEquals(0, BigDecimal("16.0").compareTo(service.latestKeyRateOrConfig()))
        }

    @Test
    fun `falls back to config when repository throws`() =
        runBlocking {
            val repo = Mockito.mock(CbrRatesHistoryRepository::class.java)
            Mockito.`when`(repo.findLatestKeyRate()).thenThrow(RuntimeException("db down"))
            val service = service(config = MacroConfig().apply { liveRateEnabled = true }, repository = repo)

            assertEquals(0, BigDecimal("16.0").compareTo(service.latestKeyRateOrConfig()))
        }

    @Test
    fun `uses config and never reads repository when disabled`() =
        runBlocking {
            val repo = Mockito.mock(CbrRatesHistoryRepository::class.java)
            val service = service(config = MacroConfig(), repository = repo)

            assertEquals(0, BigDecimal("16.0").compareTo(service.latestKeyRateOrConfig()))
            Mockito.verify(repo, Mockito.never()).findLatestKeyRate()
        }

    @Test
    fun `uses config when repository bean is absent`() =
        runBlocking {
            val service = service(config = MacroConfig().apply { liveRateEnabled = true }, repository = null)

            assertEquals(0, BigDecimal("16.0").compareTo(service.latestKeyRateOrConfig()))
        }

    private fun service(
        config: MacroConfig,
        repository: CbrRatesHistoryRepository?,
    ): MacroContextService = MacroContextService(config, ObjectMapper(), SimpleMeterRegistry(), repository)
}
