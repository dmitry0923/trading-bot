package com.trading.bot.agent

import com.trading.bot.model.*
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class StrategyAgent {
    data class Draft(
        val action: StrategyAction,
        val targetPrice: BigDecimal,
        val quantity: Int,
        val stopLoss: BigDecimal?,
        val takeProfit: BigDecimal?,
        val trailingStop: Boolean,
        val confidence: Double,
        val reasoning: String
    )

    suspend fun formulate(ticker: String, tech: TechnicalReport, fund: FundamentalReport, snapshot: MarketSnapshot, cycleId: String): Draft {
        return Draft(
            action = StrategyAction.HOLD,
            targetPrice = snapshot.currentPrice,
            quantity = 0,
            stopLoss = null,
            takeProfit = null,
            trailingStop = false,
            confidence = 0.0,
            reasoning = "default"
        )
    }
}
