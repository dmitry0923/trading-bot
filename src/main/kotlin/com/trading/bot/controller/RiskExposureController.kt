package com.trading.bot.controller

import com.trading.bot.config.TradingConfig
import com.trading.bot.model.dto.RiskExposureReport
import com.trading.bot.service.RiskExposureService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Correlation Engine API (read-only, доступен и ADMIN, и ANALYTICS).
 *
 * - `GET /api/v1/risk/exposure` — live-снимок портфельного риска: Gross/Net Exposure
 *   (% AUM), секторная экспозиция, корреляционная матрица открытых позиций,
 *   effectiveN, VaR95 и единый Exposure Score (0..100).
 * - `GET /api/v1/risk/correlation?tickers=&timeframe=&period=` — полная корреляционная
 *   матрица watchlist (heatmap). Без tickers — по trading.tickers.
 *
 * @see RiskExposureService
 */
@RestController
@RequestMapping("/api/v1/risk")
class RiskExposureController(
    private val riskExposureService: RiskExposureService,
    private val tradingConfig: TradingConfig,
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Live-снимок портфельного риска по открытым позициям.
     */
    @GetMapping("/exposure")
    suspend fun exposure(): RiskExposureReport {
        meterRegistry.counter("api.risk.exposure").increment()
        return riskExposureService.buildSnapshot()
    }

    /**
     * Полная корреляционная матрица для heatmap watchlist.
     *
     * @param tickers тикеры через запятую (по умолчанию — trading.tickers)
     * @param timeframe таймфрейм свечей (MINUTE_10 / HOUR_1 / DAY_1)
     * @param period глубина расчёта в свечах
     */
    @GetMapping("/correlation")
    fun correlation(
        @RequestParam(required = false) tickers: List<String>?,
        @RequestParam(defaultValue = "MINUTE_10") timeframe: String,
        @RequestParam(defaultValue = "50") period: Int,
    ): Map<String, Map<String, Double?>> {
        val watch = tickers?.takeIf { it.isNotEmpty() } ?: tradingConfig.tickers
        meterRegistry.counter("api.risk.correlation", Tags.of("tickers", watch.size.toString())).increment()
        return riskExposureService.correlationMatrix(watch, timeframe, period)
    }
}
