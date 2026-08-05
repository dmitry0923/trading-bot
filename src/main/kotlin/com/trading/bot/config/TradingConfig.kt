package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация торгового ядра (prefix = "trading").
 *
 * @property mode режим торговли: SIMULATION | LIVE
 * @property tickers список торговых тикеров (Si — фьючерс, остальные — акции)
 * @property botIntervalMs период бот-цикла, мс
 * @property strategyIntervalMs период стратегического цикла, мс
 * @property monitorIntervalMs период fallback-поллинга котировок, мс
 * @property maxOpenPositionsForNewEntry максимум открытых позиций для новых входов
 * @property timeframe основной таймфрейм свечей (обратная совместимость)
 * @property timeframes список таймфреймов для мульти-таймфрейм анализа
 * @property wsQuotesEnabled признак того, что real-time котировки идут через WebSocket
 */
@Component
@ConfigurationProperties(prefix = "trading")
class TradingConfig {
    var mode: String = "SIMULATION"
    var tickers: List<String> = listOf("Si", "SBER", "GAZP", "LKOH", "VTBR", "ROSN", "NVTK", "PLZL", "MGNT", "TATN")
    var botIntervalMs: Long = 300000
    var strategyIntervalMs: Long = 600000
    var monitorIntervalMs: Long = 10000
    var maxOpenPositionsForNewEntry: Int = 3
    var timeframe: String = "MINUTE_10"
    var timeframes: List<String> = listOf("MINUTE_10")
    var wsQuotesEnabled: Boolean = true
}
