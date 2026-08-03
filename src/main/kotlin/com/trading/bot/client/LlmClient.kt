package com.trading.bot.client

import org.springframework.stereotype.Component

@Component
class LlmClient {
    data class LlmResponse(val content: String)

    suspend fun chat(system: String, user: String): LlmResponse {
        return LlmResponse("{\"action\":\"HOLD\",\"targetPrice\":100,\"quantity\":0,\"stopLoss\":null,\"takeProfit\":null,\"trailingStop\":false,\"confidence\":0.0,\"reasoning\":\"default\"}")
    }
}
