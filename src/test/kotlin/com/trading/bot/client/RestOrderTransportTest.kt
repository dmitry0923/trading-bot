package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.service.LiveFrozenStrategyResolver
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * Unit-тесты REST-транспорта ([RestOrderTransport]) в не-LIVE режиме: имитация
 * исполнения (sim-*), отмена подтверждается. LIVE-interlock делегирован единому
 * [LiveFrozenStrategyResolver] (P1): резолвер проверяет назначение ордера
 * (P1-a): entry требует approved, close/SL/TP — открытую позицию. Семантика
 * самого резолвера покрывается отдельным юнит-тестом [LiveFrozenStrategyResolverTest],
 * а реальная доставка ордера — интеграционными тестами.
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
            val live = liveTransport(resolver())
            assertFailsWith<OrderInterlockDeniedException> {
                live.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1")
            }
        }

    @Test
    fun `live placeConditional is blocked for unapproved ticker`() =
        runBlocking {
            val live = liveTransport(resolver())
            assertFailsWith<OrderInterlockDeniedException> {
                live.placeConditional("stop", "SBER", "buy", 1, BigDecimal("250"), "idem-2", "P1")
            }
        }

    @Test
    fun `live placeLimit is blocked when approval service not ready`() =
        runBlocking {
            val live = liveTransport(resolver())
            assertFailsWith<OrderInterlockDeniedException> {
                live.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1")
            }
        }

    @Test
    fun `live placeLimit close purpose is blocked after revoke without open position`() =
        runBlocking {
            val live = liveTransport(resolver(reducingAllowed = false))
            assertFailsWith<OrderInterlockDeniedException> {
                live.placeLimit("SBER", "sell", 1, BigDecimal("250"), "idem-c1", "P1", OrderPurpose.CLOSE)
            }
        }

    @Test
    fun `live placeConditional sl purpose is blocked after revoke without open position`() =
        runBlocking {
            val live = liveTransport(resolver(reducingAllowed = false))
            assertFailsWith<OrderInterlockDeniedException> {
                live.placeConditional("stop", "SBER", "sell", 1, BigDecimal("240"), "idem-sl1", "P1", OrderPurpose.SL)
            }
        }

    @Test
    fun `live placeLimit entry still blocked even with reducing allowed (revoke scenario)`() =
        runBlocking {
            // P1-a: entry requires full approval even if reducing is allowed via open position
            val live = liveTransport(resolver(entryAllowed = false, reducingAllowed = true))
            assertFailsWith<OrderInterlockDeniedException> {
                live.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-e1", "P1", OrderPurpose.ENTRY)
            }
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

    private fun resolver(
        entryAllowed: Boolean = false,
        reducingAllowed: Boolean = false,
    ): LiveFrozenStrategyResolver {
        val r = mock<LiveFrozenStrategyResolver>()
        runBlocking {
            whenever(r.isOrderAllowed(any(), any())).thenAnswer { invocation ->
                val purpose = invocation.getArgument<OrderPurpose>(1)
                when (purpose) {
                    OrderPurpose.ENTRY -> entryAllowed
                    else -> reducingAllowed
                }
            }
        }
        // resolveActive kept for callers that use it directly
        whenever(r.resolveActive(any())).thenReturn(null)
        return r
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail<T>("Expected ${T::class.simpleName} but no exception was thrown")
        error("unreachable")
    }
}
