package com.trading.bot.infrastructure.health

import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsStream
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.service.ReactiveRedisCacheService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalTime

/**
 * Бизнес-готовность торгового контура (не liveness контейнера).
 *
 * Проверяет:
 * - WebSocket quotes/orders (в LIVE — критично только в торговые часы);
 * - Redis (state store) с жёстким таймаутом 1 с;
 * - торговые часы МСК;
 * - режим работы (SIMULATION/LIVE).
 *
 * Семантика:
 * - SIMULATION: процесс UP, даже если WS отключён или сейчас не торговые часы;
 *   детали показывают фактическое состояние.
 * - LIVE: DOWN только если Redis недоступен или в торговые часы нет WS quotes.
 *   Вне торговых часов UP (процесс здоров, торговля не требуется), детали
 *   `trading_hours=CLOSED` / `ws.quotes=DISCONNECTED`.
 *
 * Docker healthcheck использует `/actuator/health/liveness` (см. application.yml
 * и docker-compose.yml), поэтому бизнес-состояние не роняет `compose up --wait`.
 */
@Component
class TradingHealthIndicator(
    private val webSocketManager: WebSocketManager,
    private val riskConfig: RiskConfig,
    private val redisCache: ReactiveRedisCacheService,
    private val tradingConfig: TradingConfig,
    private val clock: Clock,
) : HealthIndicator {
    override fun health(): Health {
        val builder = Health.Builder()
        val mode = tradingConfig.mode.uppercase()
        builder.withDetail("mode", mode)

        val quotesConnected = webSocketManager.isConnected(WsStream.QUOTES)
        builder.withDetail("ws.quotes", if (quotesConnected) "CONNECTED" else "DISCONNECTED")

        val ordersConnected = webSocketManager.isConnected(WsStream.ORDERS)
        builder.withDetail("ws.orders", if (ordersConnected) "CONNECTED" else "DISCONNECTED")

        // Redis — критичный state store; runBlocking ограничен таймаутом, чтобы
        // деградация Redis не вешала /actuator/health.
        val redisUp =
            try {
                runBlocking {
                    withTimeoutOrNull(REDIS_HEALTH_TIMEOUT_MS) { redisCache.isAvailable() } ?: false
                }
            } catch (_: Exception) {
                false
            }
        builder.withDetail("redis", if (redisUp) "UP" else "DOWN")

        val now = LocalTime.now(clock)
        val start = parseTimeOrNull(riskConfig.tradingHoursStart)
        val end = parseTimeOrNull(riskConfig.tradingHoursEnd)
        val inHours =
            start != null && end != null && start.isBefore(end) &&
                now.isAfter(start) && now.isBefore(end)
        builder.withDetail(
            "trading_hours",
            when {
                start == null || end == null -> "INVALID"
                inHours -> "ACTIVE"
                else -> "CLOSED"
            },
        )
        builder.withDetail("trading_hours.msk", now.toString())

        val liveTradingRequired = mode == "LIVE" && inHours
        return when {
            !redisUp -> builder.down().build()
            liveTradingRequired && !quotesConnected -> builder.down().build()
            else -> builder.up().build()
        }
    }

    private fun parseTimeOrNull(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()

    private companion object {
        const val REDIS_HEALTH_TIMEOUT_MS = 1_000L
    }
}
