package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация брокера Alor (prefix = "alor").
 *
 * @property apiUrl базовый URL REST API Alor
 * @property wsUrl базовый URL WebSocket Alor
 * @property token токен доступа (get token, а не refresh)
 * @property refreshToken refresh-токен для продления доступа
 * @property portfolio номер портфеля
 * @property exchange биржа (по умолчанию MOEX)
 * @property retryEnabled включать Resilience4j retry (exponential backoff + jitter) для REST-вызовов
 * @property rateLimiterEnabled включать Resilience4j RateLimiter (защита от 429)
 * @property maxOrderRetries максимум повторных доставок ордера через outbox (bounded retry)
 * @property wsReconcileOnReconnect выполнять полную State Reconciliation (REST-портфель,
 *   позиции, сделки) при каждом переподключении WebSocket
 * @property wsHeartbeatIntervalMs период heartbeat-ping, мс
 * @property wsHeartbeatTimeoutMs таймаут «тихого» соединения (watchdog), мс
 * @property wsStaleMessageAgeMs максимальный возраст сообщения в очереди обработки, мс
 */
@Component
@ConfigurationProperties(prefix = "alor")
class AlorConfig {
    var apiUrl: String = "https://api.alor.ru"
    var wsUrl: String = "wss://api.alor.ru/ws"
    var token: String = ""
    var refreshToken: String = ""
    var portfolio: String = "D12345"
    var exchange: String = "MOEX"
    var retryEnabled: Boolean = true
    var rateLimiterEnabled: Boolean = true
    var maxOrderRetries: Int = 5
    var wsReconcileOnReconnect: Boolean = true
    var wsHeartbeatIntervalMs: Long = 30_000
    var wsHeartbeatTimeoutMs: Long = 45_000
    var wsStaleMessageAgeMs: Long = 5_000
    var entryPartialFillCancelAfterMs: Long = 30_000
    var outboxBackoffBaseSeconds: Int = 10
    var outboxBackoffMaxSeconds: Int = 120
}
