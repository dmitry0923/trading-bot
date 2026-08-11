package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Конфигурация бэктеста (prefix = "bt").
 *
 * Параметры прогона по умолчанию для REST-бэктеста (`/api/v1/backtest/{ticker}`,
 * `/api/v1/backtest/{ticker}/validate`). Каждый вызов может переопределить их
 * query-параметрами; значения здесь используются, когда параметр не указан.
 *
 * @property initialCapital стартовый капитал прогона (руб).
 * @property days глубина истории в днях по умолчанию.
 * @property timeframe таймфрейм свечей по умолчанию.
 * @property minBarsForSignal минимальное число баров для сигнала (warm-up).
 * @property slPercent стоп-лосс в процентах от цены входа (например 2.0 = 2%).
 * @property tpPercent тейк-профит в процентах от цены входа (например 4.0 = 4%).
 * @property capitalSlice доля текущего капитала на одну позицию (0.20 = 20%).
 */
@Component
@ConfigurationProperties(prefix = "bt")
class BacktestConfig {
    var initialCapital: BigDecimal = BigDecimal("100000")
    var days: Int = 365
    var timeframe: String = "MINUTE_10"
    var minBarsForSignal: Int = 30
    var slPercent: Double = 2.0
    var tpPercent: Double = 4.0
    var capitalSlice: Double = 0.20
}
