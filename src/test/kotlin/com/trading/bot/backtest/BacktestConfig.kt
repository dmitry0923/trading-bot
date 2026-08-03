package com.trading.bot.backtest

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "trading")
data class BacktestConfig(
    var backtestYears: Int = 2,
    var tickers: List<String> = listOf("SBER"),
    var initialCapital: java.math.BigDecimal = java.math.BigDecimal("1000000"),
    var commissionPercent: Double = 0.05,
    var slippagePercent: Double = 0.02
)
