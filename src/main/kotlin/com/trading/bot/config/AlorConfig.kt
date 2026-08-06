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
}
