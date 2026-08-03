package com.trading.bot.config
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
@Component @ConfigurationProperties(prefix = "moex")
data class MoexConfig(var baseUrl: String = "https://iss.moex.com/iss")
