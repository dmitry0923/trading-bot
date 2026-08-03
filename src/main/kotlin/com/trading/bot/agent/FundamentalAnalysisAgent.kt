package com.trading.bot.agent

import com.trading.bot.model.FundamentalReport
import org.springframework.stereotype.Component

@Component
class FundamentalAnalysisAgent {
    suspend fun analyze(ticker: String, cycleId: String): FundamentalReport {
        return FundamentalReport(conclusion = "NEUTRAL")
    }
}
