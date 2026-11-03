package com.trading.bot.infrastructure.health

import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsStream
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.service.ReactiveRedisCacheService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Unit-тесты [TradingHealthIndicator]: бизнес-готовность (WS, Redis, часы, режим)
 * и семантика SIM/LIVE. Docker healthcheck использует отдельный liveness-пробник,
 * поэтому здесь проверяется именно бизнес-статус.
 */
class TradingHealthIndicatorTest {
    private val webSocketManager = Mockito.mock(WebSocketManager::class.java)
    private val redisCache = Mockito.mock(ReactiveRedisCacheService::class.java)
    private val moscow: ZoneId = ZoneId.of("Europe/Moscow")

    private fun indicator(
        mode: String = "SIMULATION",
        start: String = "10:00",
        end: String = "18:30",
        hour: Int = 12,
        minute: Int = 0,
    ): TradingHealthIndicator {
        val riskConfig =
            RiskConfig().apply {
                tradingHoursStart = start
                tradingHoursEnd = end
            }
        val tradingConfig = TradingConfig().apply { this.mode = mode }
        val clock =
            Clock.fixed(
                LocalDateTime.of(2026, 11, 3, hour, minute).atZone(moscow).toInstant(),
                moscow,
            )
        return TradingHealthIndicator(webSocketManager, riskConfig, redisCache, tradingConfig, clock)
    }

    private fun stubRedis(result: () -> Boolean) {
        kotlinx.coroutines.runBlocking {
            Mockito.doAnswer { result() }.`when`(redisCache).isAvailable()
        }
    }

    private fun stubWs(
        quotes: Boolean,
        orders: Boolean,
    ) {
        Mockito.`when`(webSocketManager.isConnected(WsStream.QUOTES)).thenReturn(quotes)
        Mockito.`when`(webSocketManager.isConnected(WsStream.ORDERS)).thenReturn(orders)
    }

    @Test
    fun `simulation stays up when quotes stream disconnected`() {
        stubWs(quotes = false, orders = true)
        stubRedis { true }

        val health = indicator(mode = "SIMULATION").health()

        assertEquals("UP", health.status.code)
        assertEquals("SIMULATION", health.details["mode"])
        assertEquals("DISCONNECTED", health.details["ws.quotes"])
    }

    @Test
    fun `down when redis unavailable`() {
        stubWs(quotes = true, orders = true)
        stubRedis { false }

        val health = indicator(mode = "SIMULATION").health()

        assertEquals("DOWN", health.status.code)
        assertEquals("DOWN", health.details["redis"])
    }

    @Test
    fun `down when redis check throws`() {
        stubWs(quotes = true, orders = true)
        stubRedis { throw IllegalStateException("redis timeout") }

        val health = indicator(mode = "SIMULATION").health()

        assertEquals("DOWN", health.status.code)
    }

    @Test
    fun `simulation up outside trading hours`() {
        stubWs(quotes = true, orders = true)
        stubRedis { true }

        val health = indicator(mode = "SIMULATION", hour = 3).health()

        assertEquals("UP", health.status.code)
        assertEquals("CLOSED", health.details["trading_hours"])
        assertEquals("SIMULATION", health.details["mode"])
    }

    @Test
    fun `up inside trading hours with ws and redis healthy`() {
        stubWs(quotes = true, orders = false)
        stubRedis { true }

        val health = indicator(mode = "SIMULATION", hour = 12).health()

        assertEquals("UP", health.status.code)
        assertEquals("DISCONNECTED", health.details["ws.orders"])
        assertEquals("ACTIVE", health.details["trading_hours"])
    }

    @Test
    fun `live down when quotes disconnected during trading hours`() {
        stubWs(quotes = false, orders = true)
        stubRedis { true }

        val health = indicator(mode = "LIVE", hour = 12).health()

        assertEquals("DOWN", health.status.code)
        assertEquals("LIVE", health.details["mode"])
        assertEquals("ACTIVE", health.details["trading_hours"])
    }

    @Test
    fun `live up outside trading hours even if quotes disconnected`() {
        stubWs(quotes = false, orders = true)
        stubRedis { true }

        val health = indicator(mode = "LIVE", hour = 3).health()

        assertEquals("UP", health.status.code)
        assertEquals("CLOSED", health.details["trading_hours"])
        assertEquals("DISCONNECTED", health.details["ws.quotes"])
    }
}
