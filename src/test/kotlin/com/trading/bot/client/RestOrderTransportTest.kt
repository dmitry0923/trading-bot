package com.trading.bot.client

import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.service.LiveFrozenStrategyResolver
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * Unit-тесты REST-транспорта ([RestOrderTransport]) в не-LIVE режиме: имитация
 * исполнения (sim-*), отмена подтверждается. LIVE-interlock делегирован единому
 * [LiveFrozenStrategyResolver] (P1): резолвер возвращает активную frozen-стратегию —
 * ордер проходит, иначе (null) — ордер блокируется. Семантика самого резолвера
 * (build-identity/fingerprint/fail-closed) покрывается отдельным юнит-тестом
 * LiveFrozenStrategyResolverTest, а реальная доставка ордера — интеграционными тестами.
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
            resolver(),
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

    @Test
    fun `live placeLimit is blocked for unapproved ticker`() =
        runBlocking {
            val live = liveTransport(resolver(null))
            assertNull(live.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1"))
        }

    @Test
    fun `live placeConditional is blocked for unapproved ticker`() =
        runBlocking {
            val live = liveTransport(resolver(null))
            assertNull(live.placeConditional("stop", "SBER", "buy", 1, BigDecimal("250"), "idem-2", "P1"))
        }

    @Test
    fun `live placeLimit is blocked when approval service not ready`() =
        runBlocking {
            val live = liveTransport(resolver(null))
            assertNull(live.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1"))
        }

    private fun liveTransport(resolver: LiveFrozenStrategyResolver): RestOrderTransport {
        val liveConfig = TradingConfig().apply { mode = "LIVE" }
        return RestOrderTransport(
            liveConfig,
            alorConfig,
            jacksonObjectMapper(),
            SimpleMeterRegistry(),
            AlorTokenProvider(alorConfig, jacksonObjectMapper()),
            RetryRegistry.ofDefaults(),
            RateLimiterRegistry.ofDefaults(),
            CircuitBreakerRegistry.ofDefaults(),
            resolver,
        )
    }

    private fun resolver(frozen: FrozenStrategy? = null): LiveFrozenStrategyResolver {
        val r = mock<LiveFrozenStrategyResolver>()
        whenever(r.resolveActive(any())).thenReturn(frozen)
        return r
    }
}
