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
    var tickers: List<String> = listOf("Si", "SBER", "GAZP", "LKOH", "VTBR", "ROSN", "NVTK", "PLZL", "MGNT", "TATN", "CNYRUB_TOM")
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

    // ===== Signal freshness / LLM advisory budget (P1-аудит) =====

    /**
     * Жёсткий бюджет времени LLM-советника (мс). Если LLM отвечает дольше —
     * советник возвращает NEUTRAL (fail-open), сигнал идёт без поправки. Гарантия,
     * что LLM НИКОГДА не добавляет задержку в order-execution correctness:
     * решение принимается по детерминированной стратегии, советник — только
     * параллельный фильтр с жёстким дедлайном. 1000 мс = бюджет ~1 с на совет.
     */
    var advisorBudgetMs: Long = 1000

    /**
     * Максимальный возраст рыночного снапшота (мс), при котором сигнал считается
     * пригодным к публикации/исполнению. Проверяется ПОВТОРНО ПОСЛЕ ответа LLM-
     * советника (и непосредственно перед order-admission в DecisionEngine через
     * [com.trading.bot.application.MarketDataGate]). Если снапшот устарел после
     * долгого LLM-вызова — сигнал отклоняется (HOLD), а не исполняется «в старую цену».
     */
    var signalMaxAgeMs: Long = 15_000

    /**
     * Максимальное отклонение текущей цены от цены сигнала (в %, |new/target - 1|),
     * при котором сигнал ещё исполняется. Защита от исполнения по цене, сильно
     * ушедшей от уровня, на котором стратегия приняла решение (stale-decision risk).
     * 1.0 = допускается движение ≤ 1% от цены сигнала.
     */
    var signalMaxPriceDeviationPercent: Double = 1.0

    /**
     * Максимальный спред (ask-bid)/mid в %, при котором сигнал исполняется.
     * Широкий спред после долгого LLM-вызова — отклонение сигнала. 2.0 = ≤ 2%.
     */
    var signalMaxSpreadPercent: Double = 2.0
}
