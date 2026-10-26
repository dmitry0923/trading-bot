package com.trading.bot.tuning

import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Gate Check для Monthly Tuning Engine (docs/24, Фаза 3.5).
 *
 * Проверяет метрики прошедшего месяца и выносит вердикт:
 * - [GateVerdict.PASS]  — стратегия в норме, micro-tuning достаточен.
 * - [GateVerdict.WARN]  — ухудшение, мониторинг усилен.
 * - [GateVerdict.ALERT] — стратегия убыточна, торговля приостанавливается.
 *
 * Пороги gate check (по умолчанию):
 * - PF > 1.2 → PASS; 1.0 < PF < 1.2 → WARN; PF ≤ 1.0 → ALERT
 * - Max Drawdown < 15% → PASS; 15–20% → WARN; > 20% → ALERT
 * - Win Rate > 48% → PASS; 40–48% → WARN; < 40% → ALERT
 * - Sharpe > 0.3 → PASS; 0.0–0.3 → WARN; < 0.0 → ALERT
 * - Trades ≥ 20 → статистика значима; < 20 → WARN (мало данных)
 *
 * Итоговый вердикт — наихудший из всех проверок.
 */
@Component
class PerformanceGateCheck {
    /**
     * Вердикт gate check.
     */
    enum class GateVerdict {
        /** Стратегия в норме. Micro-tuning достаточен. */
        PASS,

        /** Ухудшение показателей. Мониторинг усилен, туннинг обязателен. */
        WARN,

        /**
         * Стратегия убыточна или сильная просадка. Торговля останавливается
         * автоматически, требуется ручной анализ.
         */
        ALERT,
    }

    /**
     * Метрики прошедшего месяца для gate check.
     *
     * @property profitFactor коэффициент прибыльности (PF = totalProfit / totalLoss).
     * @property maxDrawdownPct максимальная просадка в процентах (0..100).
     * @property winRatePct процент прибыльных сделок (0..100).
     * @property sharpe коэффициент Шарпа за период.
     * @property tradesCount количество сделок за период.
     */
    data class MonthlyMetrics(
        val profitFactor: Double,
        val maxDrawdownPct: Double,
        val winRatePct: Double,
        val sharpe: Double,
        val tradesCount: Int,
    )

    /**
     * Результат gate check с детализацией по каждому критерию.
     *
     * @property verdict итоговый вердикт (наихудший из всех критериев).
     * @property reasons список причин, поясняющих вердикт.
     * @property metrics исходные метрики для записи в БД.
     */
    data class GateCheckResult(
        val verdict: GateVerdict,
        val reasons: List<String>,
        val metrics: MonthlyMetrics,
    )

    /**
     * Выполняет gate check на основе метрик прошедшего месяца.
     *
     * @param metrics метрики прошедшего месяца.
     * @return результат с вердиктом и детализацией причин.
     */
    fun check(metrics: MonthlyMetrics): GateCheckResult {
        val verdicts = mutableListOf<Pair<GateVerdict, String>>()

        // 1. Profit Factor
        verdicts +=
            when {
                metrics.profitFactor > PF_PASS -> GateVerdict.PASS to fmt("PF=%.2f > $PF_PASS", metrics.profitFactor)
                metrics.profitFactor > PF_WARN -> GateVerdict.WARN to fmt("PF=%.2f (${PF_WARN}..${PF_PASS}]", metrics.profitFactor)
                else -> GateVerdict.ALERT to fmt("PF=%.2f ≤ $PF_WARN (стратегия убыточна)", metrics.profitFactor)
            }

        // 2. Max Drawdown
        verdicts +=
            when {
                metrics.maxDrawdownPct < DD_PASS -> {
                    GateVerdict.PASS to fmt("DD=%.1f%% < $DD_PASS%%", metrics.maxDrawdownPct)
                }

                metrics.maxDrawdownPct < DD_WARN -> {
                    GateVerdict.WARN to
                        fmt("DD=%.1f%% (${DD_PASS}..${DD_WARN}%%)", metrics.maxDrawdownPct)
                }

                else -> {
                    GateVerdict.ALERT to fmt("DD=%.1f%% > $DD_WARN%% (критическая просадка)", metrics.maxDrawdownPct)
                }
            }

        // 3. Win Rate
        verdicts +=
            when {
                metrics.winRatePct > WR_PASS -> GateVerdict.PASS to fmt("WinRate=%.1f%% > $WR_PASS%%", metrics.winRatePct)
                metrics.winRatePct > WR_WARN -> GateVerdict.WARN to fmt("WinRate=%.1f%% (${WR_WARN}..${WR_PASS}%%)", metrics.winRatePct)
                else -> GateVerdict.ALERT to fmt("WinRate=%.1f%% ≤ $WR_WARN%%", metrics.winRatePct)
            }

        // 4. Sharpe
        verdicts +=
            when {
                metrics.sharpe > SHARPE_PASS -> GateVerdict.PASS to fmt("Sharpe=%.2f > $SHARPE_PASS", metrics.sharpe)
                metrics.sharpe >= SHARPE_WARN -> GateVerdict.WARN to fmt("Sharpe=%.2f (${SHARPE_WARN}..${SHARPE_PASS}]", metrics.sharpe)
                else -> GateVerdict.ALERT to fmt("Sharpe=%.2f < $SHARPE_WARN", metrics.sharpe)
            }

        // 5. Минимальное количество сделок
        verdicts +=
            when {
                metrics.tradesCount >= MIN_TRADES_PASS -> {
                    GateVerdict.PASS to "Trades=${metrics.tradesCount} ≥ $MIN_TRADES_PASS"
                }

                metrics.tradesCount >= MIN_TRADES_WARN -> {
                    GateVerdict.WARN to
                        "Trades=${metrics.tradesCount} < $MIN_TRADES_PASS (мало данных)"
                }

                else -> {
                    GateVerdict.ALERT to "Trades=${metrics.tradesCount} < $MIN_TRADES_WARN (статистика незначима)"
                }
            }

        val worstVerdict = verdicts.maxOf { it.first }
        val reasons = verdicts.map { "${it.first}: ${it.second}" }

        return GateCheckResult(
            verdict = worstVerdict,
            reasons = reasons,
            metrics = metrics,
        )
    }

    companion object {
        // Profit Factor пороги
        const val PF_PASS = 1.2
        const val PF_WARN = 1.0

        // Drawdown пороги (%)
        const val DD_PASS = 15.0
        const val DD_WARN = 20.0

        // Win Rate пороги (%)
        const val WR_PASS = 48.0
        const val WR_WARN = 40.0

        // Sharpe пороги
        const val SHARPE_PASS = 0.3
        const val SHARPE_WARN = 0.0

        // Минимум сделок
        const val MIN_TRADES_PASS = 20
        const val MIN_TRADES_WARN = 10
    }

    /** Locale-независимое форматирование: строки-причины попадают в metrics/notes
     *  и алерты, где запятая как десятичный разделитель ломает разбор (docs/28 §3.1 №37). */
    private fun fmt(
        pattern: String,
        vararg args: Any?,
    ): String = String.format(Locale.ROOT, pattern, *args)
}
