package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация LLM-клиента (prefix = "llm").
 *
 * Гибкое подключение к LLM: по умолчанию агрегатор RouterAI (routerai.ru),
 * с возможностью переключения на Kimi, DeepSeek или Qwen. Активный провайдер
 * и модель могут быть переопределены через UI (BotSettings.llmProvider /
 * llmModel / llmBaseUrl / llmApiKey), см. [com.trading.bot.service.SettingsService].
 *
 * @property provider активный провайдер по умолчанию
 * @property apiKey ключ API активного провайдера
 * @property baseUrl базовый URL (для обратной совместимости — Kimi)
 * @property model модель по умолчанию (для обратной совместимости — Kimi)
 * @property routerAiBaseUrl базовый URL агрегатора RouterAI
 * @property routerAiModel модель через RouterAI (по умолчанию авто-роутинг)
 * @property kimiBaseUrl базовый URL Kimi (Moonshot)
 * @property kimiModel модель Kimi
 * @property deepseekBaseUrl базовый URL DeepSeek
 * @property deepseekModel модель DeepSeek
 * @property qwenBaseUrl базовый URL Qwen (DashScope, OpenAI-совместимый режим)
 * @property qwenModel модель Qwen
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
    var provider: LlmProvider = LlmProvider.ROUTER_AI
    var apiKey: String = ""
    var baseUrl: String = "https://api.moonshot.cn/v1"
    var model: String = "kimi-k3"

    var routerAiBaseUrl: String = "https://routerai.ru/api/v1"
    var routerAiModel: String = "auto"
    var kimiBaseUrl: String = "https://api.moonshot.cn/v1"
    var kimiModel: String = "kimi-k3"
    var deepseekBaseUrl: String = "https://api.deepseek.com/v1"
    var deepseekModel: String = "deepseek-chat"
    var qwenBaseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    var qwenModel: String = "qwen-plus"

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

    /**
     * Возвращает базовый URL и модель по умолчанию для провайдера.
     */
    fun endpointFor(provider: LlmProvider): Pair<String, String> =
        when (provider) {
            LlmProvider.ROUTER_AI -> routerAiBaseUrl to routerAiModel
            LlmProvider.KIMI -> kimiBaseUrl to kimiModel
            LlmProvider.DEEPSEEK -> deepseekBaseUrl to deepseekModel
            LlmProvider.QWEN -> qwenBaseUrl to qwenModel
        }
}
