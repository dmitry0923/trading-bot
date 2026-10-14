package com.trading.bot.backtest.portfolio

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация research-движка cross-sectional momentum + carry портфеля фьючерсов,
 * prefix = "bt.portfolio" (env `BT_PORTFOLIO_*`).
 *
 * **Default off** ([enabled] = false): live-путь, LIVE-guard и направленная стратегия
 * не затрагиваются. Инструмент предназначен для честного OOS/WFA-замера кросс-секционного
 * момента с funding-тилтом, см. `docs/26-cross-sectional-momentum-carry.md`.
 */
@Component
@ConfigurationProperties(prefix = "bt.portfolio")
class PortfolioMomentumConfig {
    /** research-флаг: включать движок (по умолчанию выключен). */
    var enabled: Boolean = false

    /** Юниверс инструментов для ранжирования (перпетуалы с полной историей). */
    var universe: List<String> = listOf("CNYRUBF", "USDRUBF", "EURRUBF", "GLDRUBF", "IMOEXF")

    /** Таймфрейм свечей юниверса. */
    var timeframe: String = "MINUTE_10"

    /** Окно momentum (лог-доходность), бары. */
    var lookbackBars: Int = 1680

    /** Инвертировать знак momentum-члена: true → cross-sectional mean-reversion (long отстающие). */
    var reversalEnabled: Boolean = false

    /** Период между ребалансами, бары. */
    var rebalanceBars: Int = 420

    /** Число длинных ног (лучшие по score). */
    var longCount: Int = 1

    /** Число коротких ног (худшие по score). */
    var shortCount: Int = 1

    /** Доля истории, отводимая под out-of-sample (вердикт — только по ней). */
    var oosFraction: Double = 0.30

    /** Целевой суммарный нотионал книги как доля капитала. */
    var capitalSlice: Double = 1.0

    /** Вес momentum-терма в score (0 = carry-центричный режим при carryWeight>0). */
    var momentumWeight: Double = 1.0

    /** Вес carry-тилта λ в score (0 = читый momentum). */
    var carryWeight: Double = 0.0

    /** Окно усреднения funding для carry, число последних клирингов. */
    var carryLookbackDays: Int = 5

    /** Минимум валидных инструментов в сечении для формирования сигнала. */
    var minUniverse: Int = 3

    /** Веса ног обратно пропорционально их волатильности (risk-parity); иначе равные нотионалы. */
    var volScalingEnabled: Boolean = false

    /** Окно оценки реализованной волатильности ноги, бары. */
    var volLookbackBars: Int = 420

    /** Масштабировать суммарный нотионал книги к целевой волатильности (de-risk при высокой σ). */
    var volTargetEnabled: Boolean = false

    /** Целевая годовая волатильность книги (доля). Скалирование только вниз (≤ 1). */
    var volTargetAnnualized: Double = 0.15

    /** Число баров в году для аннуализации волатильности книги. */
    var volTargetBarsPerYear: Double = 21_000.0

    /** Стартовый капитал бэктеста, руб. */
    var initialCapital: Double = 1_000_000.0

    /** Использовать фактические SWAPRATE из funding_history (иначе — spec fallback). */
    var useFundingHistory: Boolean = true
}
