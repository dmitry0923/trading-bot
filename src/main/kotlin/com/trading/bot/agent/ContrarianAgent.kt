package com.trading.bot.agent

import com.trading.bot.model.*
import org.springframework.stereotype.Component

@Component
class ContrarianAgent {
    suspend fun challenge(draft: StrategyAgent.Draft, tech: TechnicalReport, fund: FundamentalReport, snapshot: MarketSnapshot, cycleId: String): String {
        return "No strong objections"
    }
}
