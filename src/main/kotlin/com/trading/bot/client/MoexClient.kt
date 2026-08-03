package com.trading.bot.client

import com.trading.bot.model.Candle
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime

@Component
class MoexClient {
    suspend fun getCandles(ticker: String, days: Int, from: LocalDateTime): List<Candle> {
        return emptyList()
    }
}
