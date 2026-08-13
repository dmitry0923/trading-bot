package com.trading.bot.backtest

import com.trading.bot.config.TradingConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service

/** Результат прогона гейта: вердикт + панель по всем тикерам портфеля. */
data class PortfolioBacktestCheck(
    val verdict: PortfolioBacktestVerdict,
    val panel: PanelBacktestResponse,
)

/**
 * Гейт приёмки стратегии (roadmap 13.3 п.2): прогон панельного бэктеста по ВСЕМ
 * тикерам портфеля из `trading.tickers` с параметрами `bt.*` и проверка
 * критериев раздела 11.5 — `isPassable()` = PASS хотя бы у большинства (14.9).
 *
 * Используется как REST-эндпоинт `POST /api/v1/backtest/portfolio-check` перед
 * продвижением каждой новой стратегии. Историю не подкачивает с MOEX
 * (loadHistory=false) — гейт оценивает стратегию на уже сохранённых свечах.
 */
@Service
class PortfolioBacktestGuard(
    private val panelBacktestService: PanelBacktestService,
    private val tradingConfig: TradingConfig,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun checkPortfolio(): PortfolioBacktestCheck {
        val panel =
            panelBacktestService.run(
                PanelBacktestRequest(
                    tickers = tradingConfig.tickers,
                    loadHistory = false,
                ),
            )
        val verdict = evaluate(panel.summary)
        logger.info {
            "Portfolio backtest gate: pass=${verdict.passCount}/${verdict.tickerCount} " +
                "share=${"%.1f".format(verdict.passShare * 100)}% " +
                "-> ${if (verdict.accepted) "ACCEPTED" else "REJECTED"}"
        }
        return PortfolioBacktestCheck(verdict, panel)
    }

    fun evaluate(summary: PanelBacktestSummary): PortfolioBacktestVerdict {
        val verdict = PortfolioBacktestGate.evaluate(summary)
        meterRegistry
            .counter(
                "bt.portfolio.gate",
                Tags.of("verdict", if (verdict.accepted) "PASS" else "REJECT"),
            ).increment()
        meterRegistry.gauge("bt.portfolio.pass_share", verdict.passShare)
        return verdict
    }
}
