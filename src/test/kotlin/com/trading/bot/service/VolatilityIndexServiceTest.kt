package com.trading.bot.service

import com.trading.bot.client.MoexClient
import com.trading.bot.config.RiskConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Фильтр волатильности по индексу MOEX (RVI): аномальный скачок ставит входы на паузу,
 * недоступность индекса не блокирует (fail-open).
 */
class VolatilityIndexServiceTest {
    private val moexClient = Mockito.mock(MoexClient::class.java)

    private fun service(config: RiskConfig = RiskConfig()): VolatilityIndexService =
        VolatilityIndexService(config, moexClient, SimpleMeterRegistry())

    @Test
    fun `anomalous when value above threshold`() =
        runBlocking {
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(BigDecimal("55"))

            val s = service()
            s.refresh()

            assertTrue(s.isVolatilityAnomalous())
        }

    @Test
    fun `not anomalous when value below threshold`() =
        runBlocking {
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(BigDecimal("45"))

            val s = service()
            s.refresh()

            assertFalse(s.isVolatilityAnomalous())
        }

    @Test
    fun `disabled when filter turned off`() =
        runBlocking {
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(BigDecimal("80"))
            val config = RiskConfig().apply { volatilityIndexEnabled = false }

            val s = service(config)
            s.refresh()

            assertFalse(s.isVolatilityAnomalous())
        }

    @Test
    fun `fail-open when index unavailable`() =
        runBlocking {
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(null)

            val s = service()
            s.refresh()

            assertFalse(s.isVolatilityAnomalous())
        }
}
