package com.trading.bot.infrastructure.health

import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsStream
import com.trading.bot.config.RiskConfig
import com.trading.bot.service.ReactiveRedisCacheService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Unit-тесты [TradingHealthIndicator]: четыре состояния (WS, Redis, торговые часы) и
 * исключение Redis. Ранее пакет `infrastructure/health` имел 0 % покрытия ветвей
 * (скан-18): ошибка в health-индикаторе не ловилась тестами вовсе.
 */
class TradingHealthIndicatorTest {
    private val webSocketManager = Mockito.mock(WebSocketManager::class.java)
    private val redisCache = Mockito.mock(ReactiveRedisCacheService::class.java)

    private fun riskConfig(
        start: String,
        end: String,
    ): RiskConfig =
        RiskConfig().apply {
            tradingHoursStart = start
            tradingHoursEnd = end
        }

    private fun activeWindow(): Pair<String, String> {
        val now = LocalTime.now(ZoneId.of("Europe/Moscow"))
        val start = now.minusHours(1)
        val end = now.plusHours(1)
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        return start.format(fmt) to end.format(fmt)
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
    fun `down when quotes stream disconnected`() {
        stubWs(quotes = false, orders = true)
        stubRedis { true }
        val (start, end) = activeWindow()

        val health = TradingHealthIndicator(webSocketManager, riskConfig(start, end), redisCache).health()

        assertEquals("DOWN", health.status.code)
        assertEquals("DISCONNECTED", health.details["ws.quotes"])
    }

    @Test
    fun `down when redis unavailable`() {
        stubWs(quotes = true, orders = true)
        stubRedis { false }
        val (start, end) = activeWindow()

        val health = TradingHealthIndicator(webSocketManager, riskConfig(start, end), redisCache).health()

        assertEquals("DOWN", health.status.code)
        assertEquals("DOWN", health.details["redis"])
    }

    @Test
    fun `down when redis check throws`() {
        stubWs(quotes = true, orders = true)
        stubRedis { throw IllegalStateException("redis timeout") }
        val (start, end) = activeWindow()

        val health = TradingHealthIndicator(webSocketManager, riskConfig(start, end), redisCache).health()

        assertEquals("DOWN", health.status.code)
    }

    @Test
    fun `out of service outside trading hours`() {
        stubWs(quotes = true, orders = true)
        stubRedis { true }

        val health = TradingHealthIndicator(webSocketManager, riskConfig("03:00", "03:01"), redisCache).health()

        assertEquals("OUT_OF_SERVICE", health.status.code)
        assertEquals("Outside trading hours", health.details["reason"])
    }

    @Test
    fun `up inside trading hours with ws and redis healthy`() {
        stubWs(quotes = true, orders = false)
        stubRedis { true }
        val (start, end) = activeWindow()

        val health = TradingHealthIndicator(webSocketManager, riskConfig(start, end), redisCache).health()

        assertEquals("UP", health.status.code)
        assertEquals("DISCONNECTED", health.details["ws.orders"])
        assertEquals("ACTIVE", health.details["trading_hours"])
    }
}
