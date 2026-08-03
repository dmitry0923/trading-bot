package com.trading.bot.config
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
@Component @ConfigurationProperties(prefix = "llm")
data class LlmConfig(var apiKey: String = "", var baseUrl: String = "", var model: String = "kimi-k3", var timeoutSec: Int = 60, var maxTokens: Int = 4096, var temperature: Double = 0.15)
