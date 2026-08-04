package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

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
    var semanticCacheTtlMinutes: Long = 10

    var guardrailsMaxPriceDeviationPercent: Double = 3.0

    var circuitBreakerEnabled: Boolean = true
    var rateLimiterEnabled: Boolean = true
    var retryEnabled: Boolean = true
}
