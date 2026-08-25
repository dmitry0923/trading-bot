package com.trading.bot.integration

import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsConnectionEvent
import kotlinx.coroutines.flow.emptyFlow
import org.mockito.Mockito
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class WebSocketMockConfig {
    @Bean
    fun webSocketManager(): WebSocketManager =
        Mockito.mock(WebSocketManager::class.java) { invocation ->
            when (invocation.method.name) {
                "isConnected" -> true
                "getEvents" -> emptyFlow<WsConnectionEvent>()
                else -> null
            }
        }
}
