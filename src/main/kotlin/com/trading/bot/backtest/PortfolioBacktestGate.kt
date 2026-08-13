package com.trading.bot.backtest

/**
 * Вердикт гейта приёмки стратегии (roadmap 13.3 п.2, раздел 11.5).
 *
 * @property accepted стратегия допускается к продвижению (PASS по большинству тикеров)
 * @property passCount число тикеров, прошедших `isPassable()`
 * @property tickerCount число тикеров в прогоне
 * @property passShare доля прошедших тикеров [0..1]
 * @property minPassShare минимальная доля прошедших для приёмки (по умолчанию 0.5)
 */
data class PortfolioBacktestVerdict(
    val accepted: Boolean,
    val passCount: Int,
    val tickerCount: Int,
    val passShare: Double,
    val minPassShare: Double,
)

/**
 * Чистый гейт приёмки стратегии: бэктест всех тикеров портфеля по критериям
 * раздела 11.5 — стратегия допускается, если `isPassable()` = PASS хотя бы у
 * большинства тикеров (14.9). Без зависимостей — unit-тестируемый.
 */
object PortfolioBacktestGate {
    /** «Большинство» = доля прошедших ≥ 50%. */
    const val DEFAULT_MIN_PASS_SHARE = 0.5

    fun evaluate(
        summary: PanelBacktestSummary,
        minPassShare: Double = DEFAULT_MIN_PASS_SHARE,
    ): PortfolioBacktestVerdict {
        require(minPassShare > 0.0 && minPassShare <= 1.0) { "minPassShare must be in (0, 1]" }
        return PortfolioBacktestVerdict(
            accepted = summary.tickerCount > 0 && summary.passShare >= minPassShare,
            passCount = summary.passCount,
            tickerCount = summary.tickerCount,
            passShare = summary.passShare,
            minPassShare = minPassShare,
        )
    }
}
