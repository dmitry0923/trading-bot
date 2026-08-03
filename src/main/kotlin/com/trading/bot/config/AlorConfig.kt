package com.trading.bot.config
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
@Component @ConfigurationProperties(prefix = "alor")
data class AlorConfig(var apiUrl: String = "", var wsUrl: String = "", var token: String = "", var refreshToken: String = "", var portfolio: String = "", var exchange: String = "MOEX")
