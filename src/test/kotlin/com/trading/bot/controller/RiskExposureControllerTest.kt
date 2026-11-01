package com.trading.bot.controller

import com.trading.bot.config.TradingConfig
import com.trading.bot.model.dto.RiskExposureReport
import com.trading.bot.service.RiskExposureService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [RiskExposureController] — снимок экспозиции и выбор watchlist для корреляций
 * (явный список, пустой список, дефолт из конфига).
 */
class RiskExposureControllerTest {
    private val service = mock<RiskExposureService>()
    private val tradingConfig = TradingConfig().apply { tickers = listOf("SBER", "GAZP") }
    private val registry = SimpleMeterRegistry()
    private val controller = RiskExposureController(service, tradingConfig, registry)

    @Test
    fun `exposure delegates to the service and meters the call`(): Unit =
        runBlocking {
            val report = mock<RiskExposureReport>()
            whenever(service.buildSnapshot()).thenReturn(report)

            assertEquals(report, controller.exposure())
            assertEquals(1.0, registry.counter("api.risk.exposure").count())
        }

    @Test
    fun `correlation falls back to the config for blank tickers and keeps an explicit list`() {
        val matrix = mapOf("SBER" to mapOf("SBER" to 1.0))
        whenever(
            service.correlationMatrix(
                Mockito.anyList<String>(),
                Mockito.anyString(),
                Mockito.anyInt(),
            ),
        ).thenReturn(matrix)

        val defaults = controller.correlation(null, "MINUTE_10", 50)
        val empty = controller.correlation(emptyList(), "HOUR_1", 30)
        val explicit = controller.correlation(listOf("GAZP"), "DAY_1", 10)

        assertEquals(matrix, defaults)
        assertEquals(matrix, empty)
        assertEquals(matrix, explicit)
        assertEquals(2.0, registry.counter("api.risk.correlation", "tickers", "2").count())
        assertEquals(1.0, registry.counter("api.risk.correlation", "tickers", "1").count())
    }
}
