package com.trading.bot.config
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
@Component @ConfigurationProperties(prefix = "trading")
data class TradingConfig(var mode: String = "SIMULATION", var tickers: List<String> = emptyList(), var botIntervalMs: Long = 300000, var strategyIntervalMs: Long = 600000, var monitorIntervalMs: Long = 600000, var maxOpenPositionsForNewEntry: Int = 0, var timeframe: String = "MINUTE_10")
