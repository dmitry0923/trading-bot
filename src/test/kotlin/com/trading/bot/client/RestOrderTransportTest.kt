package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * Unit-тесты REST-транспорта ([RestOrderTransport]) в не-LIVE режиме:
 * имитация исполнения (sim-*), отмена подтверждается. HTTP-ветки (4xx/reject,
 * UNCERTAIN) семантически идентичны прежним телам [AlorClient] и покрываются
 * интеграционными тестами исполнения.
 */
class RestOrderTransportTest {
    private val tradingConfig = TradingConfig().apply { mode = "SIMULATION" }
    private val alorConfig = AlorConfig().apply { portfolio = "P1" }
    private val transport =
        RestOrderTransport(
            tradingConfig,
            alorConfig,
            jacksonObjectMapper(),
            SimpleMeterRegistry(),
            AlorTokenProvider(alorConfig, jacksonObjectMapper()),
            RetryRegistry.ofDefaults(),
            RateLimiterRegistry.ofDefaults(),
            CircuitBreakerRegistry.ofDefaults(),
        )

    @Test
    fun `placeLimit simulates order in non-live`() =
        runBlocking {
            val result = transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1")
            assertEquals("sim-SBER-idem-1", result)
        }

    @Test
    fun `placeConditional simulates order in non-live`() =
        runBlocking {
            val result = transport.placeConditional("stop", "SBER", "buy", 1, BigDecimal("250"), "idem-2", "P1")
            assertEquals("sim-stop-SBER-idem-2", result)
        }

    @Test
    fun `cancel is confirmed in non-live`() =
        runBlocking {
            val result = transport.cancel("ord-1", "idem-3", "P1")
            assertEquals(CancelResult.CONFIRMED, result)
        }
}
