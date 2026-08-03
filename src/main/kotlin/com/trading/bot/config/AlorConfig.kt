package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "alor")
class AlorConfig {
    var apiUrl: String = "https://api.alor.ru"
    var wsUrl: String = "wss://api.alor.ru/ws"
    var token: String = ""
    var refreshToken: String = ""
    var portfolio: String = "D12345"
    var exchange: String = "MOEX"
}
