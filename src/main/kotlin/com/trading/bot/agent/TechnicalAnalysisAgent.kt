package com.trading.bot.agent

import com.trading.bot.model.Candle
import com.trading.bot.model.MarketSnapshot
import com.trading.bot.model.TechnicalReport
import org.springframework.stereotype.Component

@Component
class TechnicalAnalysisAgent {
    suspend fun analyze(ticker: String, candles: List<Candle>, snapshot: MarketSnapshot, cycleId: String): TechnicalReport {
        return TechnicalReport(trend = "NEUTRAL", rsi = 50.0, atr = 1.5)
    }
}
