package com.trading.bot.infrastructure.health

import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsStream
import com.trading.bot.config.RiskConfig
import com.trading.bot.service.ReactiveRedisCacheService
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.time.ZoneId

/**
 * Составной HealthIndicator для торговой системы.
 *
 * Проверяет:
 * - WebSocket connectivity (quotes + orders streams)
 * - Redis availability (key-value store for strategy state)
 * - Trading hours (MOEX session 10:00–18:30 MSK)
 *
 * Health status:
 * - UP: все критичные компоненты доступны
 * - DOWN: WS quotes отключён или Redis недоступен
 * - OUT_OF_SERVICE: WS доступен, но вне торговых часов
 */
@Component
class TradingHealthIndicator(
    private val webSocketManager: WebSocketManager,
    private val riskConfig: RiskConfig,
    private val redisCache: ReactiveRedisCacheService,
) : HealthIndicator {

    override fun health(): Health {
        val builder = Health.Builder()

        // WebSocket Quotes — критичен: без цен нельзя торговать
        val quotesConnected = webSocketManager.isConnected(WsStream.QUOTES)
        builder.withDetail("ws.quotes", if (quotesConnected) "CONNECTED" else "DISCONNECTED")

        // WebSocket Orders — важен, но не критичен
        val ordersConnected = webSocketManager.isConnected(WsStream.ORDERS)
        builder.withDetail("ws.orders", if (ordersConnected) "CONNECTED" else "DISCONNECTED")

        // Redis — критичен для state (best-effort, не suspend)
        val redisUp = try {
            val result = kotlinx.coroutines.runBlocking {
                redisCache.isAvailable()
            }
            result
        } catch (_: Exception) {
            false
        }
        builder.withDetail("redis", if (redisUp) "UP" else "DOWN")

        // Trading hours
        val now = LocalTime.now(ZoneId.of("Europe/Moscow"))
        val start = LocalTime.parse(riskConfig.tradingHoursStart)
        val end = LocalTime.parse(riskConfig.tradingHoursEnd)
        val inHours = !now.isBefore(start) && !now.isAfter(end)
        builder.withDetail("trading_hours", if (inHours) "ACTIVE" else "CLOSED")
        builder.withDetail("trading_hours.msk", now.toString())

        // Determine overall status
        return when {
            !quotesConnected -> builder.down().build()
            !redisUp -> builder.down().build()
            !inHours -> builder.outOfService().withDetail("reason", "Outside trading hours").build()
            else -> builder.up().build()
        }
    }
}
