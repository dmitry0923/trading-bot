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
 * @property marketDataMaxAgeMs максимальный возраст последнего тика (WS или REST-fallback)
 *   для разрешения НОВЫХ входов в позиции; при устаревших данных входы блокируются
 *   (защита от торговли на «мёртвых» данных после обрыва WebSocket)
 * @property candleStaleBufferMs дополнительный буфер к свежести последней свечи в
 *   стратегическом цикле (поверх 2×длительности таймфрейма); при устаревших свечах
 *   тикер пропускается
 * @property pairs пары для арбитража: тикер -> связанный инструмент
 *   (напр. "Si" -> "USDRUB"). Задаёт relatedQuote в StrategyContext; без пары
 *   ArbitrageStrategy всегда HOLD
 * @property obiEntryThreshold порог |OBI| для блокировки входа (0.0..1.0):
 *   BUY блокируется при obi < -threshold, SELL — при obi > threshold.
 *   0.0 = проверка отключена (по умолчанию 0.5 = умеренная фильтрация)
 */
@Component
@ConfigurationProperties(prefix = "trading")
class TradingConfig {
    var mode: String = "SIMULATION"
    var tickers: List<String> = listOf("Si", "SBER", "GAZP", "LKOH", "VTBR", "ROSN", "NVTK", "PLZL", "MGNT", "TATN", "CNY_RUB")
    var botIntervalMs: Long = 300000
    var strategyIntervalMs: Long = 600000
    var monitorIntervalMs: Long = 10000
    var maxOpenPositionsForNewEntry: Int = 3
    var timeframe: String = "MINUTE_10"
    var timeframes: List<String> = listOf("MINUTE_10")
    var wsQuotesEnabled: Boolean = true
    var marketDataMaxAgeMs: Long = 15_000
    var candleStaleBufferMs: Long = 120_000
    var pairs: Map<String, String> = emptyMap()
    var obiEntryThreshold: Double = 0.5
}
