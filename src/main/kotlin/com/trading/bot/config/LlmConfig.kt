package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация LLM-клиента (prefix = "llm").
 *
 * @property apiKey ключ API провайдера
 * @property baseUrl базовый URL OpenAI-совместимого API
 * @property model название модели
 * @property timeoutSec таймаут HTTP-запроса, сек
 * @property maxTokens лимит токенов в ответе
 * @property temperature температура генерации
 * @property semanticCacheEnabled включает semantic cache поверх вызовов
 * @property semanticCacheTtlMinutes TTL записей semantic cache, мин
 * @property guardrailsMaxPriceDeviationPercent макс. отклонение целевой цены от рынка, %
 * @property circuitBreakerEnabled включает Circuit Breaker
 * @property rateLimiterEnabled включает Rate Limiter
 * @property retryEnabled включает Retry
 * @property queueCapacity ёмкость очереди запросов LLM (FIFO)
 * @property queueConcurrency максимальное число одновременных LLM-вызовов
 */
@Component
@ConfigurationProperties(prefix = "llm")
class LlmConfig {
    var apiKey: String = ""
    var baseUrl: String = "https://api.moonshot.cn/v1"
    var model: String = "kimi-k3"
    var timeoutSec: Long = 30
    var maxTokens: Int = 4096
    var temperature: Double = 0.15

    var semanticCacheEnabled: Boolean = true
    var semanticCacheTtlMinutes: Long = 30

    var guardrailsMaxPriceDeviationPercent: Double = 3.0

    var circuitBreakerEnabled: Boolean = true
    var rateLimiterEnabled: Boolean = true
    var retryEnabled: Boolean = true

    var queueCapacity: Int = 64
    var queueConcurrency: Int = 2
}
