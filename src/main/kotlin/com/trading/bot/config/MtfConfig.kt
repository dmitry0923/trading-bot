package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация multi-timeframe фильтра входа (prefix = "mtf.filter").
 *
 * Фильтр старшего таймфрейма (roadmap v2.5): вход по 10-минутному таймфрейму
 * гейтится трендом часового/дневного. Входы, идущие ПРОТИВ тренда старшего ТФ,
 * блокируются (BUY при DOWN, SELL при UP).
 *
 * @property enabled включает фильтр (live; бэктест — `bt.mtf-filter-enabled`)
 * @property higherTimeframe старший таймфрейм тренда (HOUR_1/H1, DAY_1/D1)
 * @property bars целевое число баров старшего ТФ для расчёта тренда (lookback
 *   младшего ТФ = bars × длительность бакета). Меньше 30 баров старшего ТФ —
 *   тренд не вычисляется → fail-closed БЛОК.
 */
@Component
@ConfigurationProperties(prefix = "mtf.filter")
class MtfConfig {
    var enabled: Boolean = false
    var higherTimeframe: String = "HOUR_1"
    var bars: Int = 40
}
