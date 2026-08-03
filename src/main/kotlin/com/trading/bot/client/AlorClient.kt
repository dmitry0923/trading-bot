package com.trading.bot.client

import com.trading.bot.model.Candle
import com.trading.bot.model.MarketSnapshot
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime

@Component
class AlorClient {
    suspend fun getMarketSnapshot(ticker: String): MarketSnapshot? {
        return MarketSnapshot(currentPrice = BigDecimal("100"))
    }

    suspend fun getLastPrice(ticker: String): BigDecimal? {
        return BigDecimal("100")
    }

    suspend fun placeLimitOrder(ticker: String, side: String, qty: Int, price: BigDecimal): String? {
        return "order-$ticker-${System.currentTimeMillis()}"
    }

    suspend fun placeMarketOrder(ticker: String, side: String, qty: Int): String? {
        return "market-$ticker-${System.currentTimeMillis()}"
    }
}
