package com.trading.bot.backtest.pairs

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация research-движка market-neutral пар (FX relative-value),
 * prefix = "bt.pairs" (env `BT_PAIRS_*`).
 *
 * **Default off** ([enabled] = false): live-путь, LIVE-guard и направленная
 * стратегия не затрагиваются. Инструмент предназначен для честного IS/OOS-замера
 * stat-arb (спред двух коррлированных бессрочных фьючерсов), см.
 * `docs/25-fx-pairs-relative-value.md`.
 */
@Component
@ConfigurationProperties(prefix = "bt.pairs")
class PairsBacktestConfig {
    /** research-флаг: включать движок (по умолчанию выключен). */
    var enabled: Boolean = false

    /** Нога A пары (в спреде — уменьшаемое: `log(A) − beta·log(B)`). */
    var legA: String = "CNYRUBF"

    /** Нога B пары (в спреде — вычитаемое). */
    var legB: String = "USDRUBF"

    /** Таймфрейм свечей обеих ног. */
    var timeframe: String = "MINUTE_10"

    /** Окно расчёта rolling OLS beta и z-score, бары. */
    var lookbackBars: Int = 240

    /** Порог входа по |z|. */
    var entryZ: Double = 2.0

    /** Порог выхода «возврат к среднему» по |z|. */
    var exitZ: Double = 0.5

    /** Порог стопа по расширению |z|. */
    var stopZ: Double = 4.0

    /** Принудительный выход по числу баров удержания; 0 = выключено. */
    var maxHoldBars: Int = 0

    /** Доля истории, отводимая под out-of-sample (вердикт — только по ней). */
    var oosFraction: Double = 0.30

    /** Целевой нотионал ноги как доля стартового капитала. */
    var capitalSlice: Double = 0.50

    /** Стартовый капитал бэктеста, руб. */
    var initialCapital: Double = 100_000.0

    /** Использовать фактические SWAPRATE из funding_history (иначе — spec fallback). */
    var useFundingHistory: Boolean = true

    /** Минимальный beta для входа (иначе пара плохо хеджируется). */
    var betaMin: Double = 0.10
}
