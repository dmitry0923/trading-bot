package com.trading.bot.controller

import com.trading.bot.backtest.portfolio.PortfolioMomentumService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Research-эндпоинты cross-sectional momentum + carry портфеля фьючерсов (`docs/26`).
 *
 * `GET /api/v1/portfolio/momentum` — прогон с IS/OOS split; `GET
 * /api/v1/portfolio/momentum/validate` — walk-forward (фолды + consistency), параметр
 * `holdoutFraction` отрезает holdout до WFA (протокол IS70/OOS30).
 * Query-параметры перекрывают `bt.portfolio.*` без перезапуска. Live-путь не
 * затрагивается; вердикт — только по OOS/агрегату фолдов.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
class PortfolioMomentumController(
    private val service: PortfolioMomentumService,
) {
    @GetMapping("/momentum")
    suspend fun backtest(
        @RequestParam(required = false) universe: List<String>?,
        @RequestParam(required = false) timeframe: String?,
        @RequestParam(defaultValue = "730") days: Int,
        @RequestParam(required = false) lookbackBars: Int?,
        @RequestParam(required = false) skipBars: Int?,
        @RequestParam(required = false) reversalEnabled: Boolean?,
        @RequestParam(required = false) rebalanceBars: Int?,
        @RequestParam(required = false) longCount: Int?,
        @RequestParam(required = false) shortCount: Int?,
        @RequestParam(required = false) oosFraction: Double?,
        @RequestParam(required = false) capitalSlice: Double?,
        @RequestParam(required = false) momentumWeight: Double?,
        @RequestParam(required = false) carryWeight: Double?,
        @RequestParam(required = false) carryLookbackDays: Int?,
        @RequestParam(required = false) minUniverse: Int?,
        @RequestParam(required = false) initialCapital: Double?,
        @RequestParam(required = false) volScalingEnabled: Boolean?,
        @RequestParam(required = false) volLookbackBars: Int?,
        @RequestParam(required = false) volTargetEnabled: Boolean?,
        @RequestParam(required = false) volTargetAnnualized: Double?,
        @RequestParam(required = false) volTargetBarsPerYear: Double?,
    ) = service.run(
        universe = universe,
        timeframe = timeframe,
        days = days,
        lookbackBars = lookbackBars,
        skipBars = skipBars,
        reversalEnabled = reversalEnabled,
        rebalanceBars = rebalanceBars,
        longCount = longCount,
        shortCount = shortCount,
        oosFraction = oosFraction,
        capitalSlice = capitalSlice,
        momentumWeight = momentumWeight,
        carryWeight = carryWeight,
        carryLookbackDays = carryLookbackDays,
        minUniverse = minUniverse,
        initialCapital = initialCapital,
        volScalingEnabled = volScalingEnabled,
        volLookbackBars = volLookbackBars,
        volTargetEnabled = volTargetEnabled,
        volTargetAnnualized = volTargetAnnualized,
        volTargetBarsPerYear = volTargetBarsPerYear,
    )

    @GetMapping("/momentum/validate")
    suspend fun validate(
        @RequestParam(required = false) universe: List<String>?,
        @RequestParam(required = false) timeframe: String?,
        @RequestParam(defaultValue = "730") days: Int,
        @RequestParam(required = false) lookbackBars: Int?,
        @RequestParam(required = false) skipBars: Int?,
        @RequestParam(required = false) reversalEnabled: Boolean?,
        @RequestParam(required = false) rebalanceBars: Int?,
        @RequestParam(required = false) longCount: Int?,
        @RequestParam(required = false) shortCount: Int?,
        @RequestParam(defaultValue = "8") folds: Int,
        @RequestParam(required = false) capitalSlice: Double?,
        @RequestParam(required = false) momentumWeight: Double?,
        @RequestParam(required = false) carryWeight: Double?,
        @RequestParam(required = false) carryLookbackDays: Int?,
        @RequestParam(required = false) minUniverse: Int?,
        @RequestParam(required = false) initialCapital: Double?,
        @RequestParam(required = false) volScalingEnabled: Boolean?,
        @RequestParam(required = false) volLookbackBars: Int?,
        @RequestParam(required = false) volTargetEnabled: Boolean?,
        @RequestParam(required = false) volTargetAnnualized: Double?,
        @RequestParam(required = false) volTargetBarsPerYear: Double?,
        @RequestParam(required = false) holdoutFraction: Double?,
    ) = service.validate(
        universe = universe,
        timeframe = timeframe,
        days = days,
        lookbackBars = lookbackBars,
        skipBars = skipBars,
        reversalEnabled = reversalEnabled,
        rebalanceBars = rebalanceBars,
        longCount = longCount,
        shortCount = shortCount,
        capitalSlice = capitalSlice,
        momentumWeight = momentumWeight,
        carryWeight = carryWeight,
        carryLookbackDays = carryLookbackDays,
        minUniverse = minUniverse,
        initialCapital = initialCapital,
        volScalingEnabled = volScalingEnabled,
        volLookbackBars = volLookbackBars,
        volTargetEnabled = volTargetEnabled,
        volTargetAnnualized = volTargetAnnualized,
        volTargetBarsPerYear = volTargetBarsPerYear,
        folds = folds,
        holdoutFraction = holdoutFraction,
    )

    /**
     * Deployment-gate портфеля (`docs/26` P1 шаг 4): dev-backtest + walk-forward на
     * dev-части + одноразовый holdout + Monte Carlo + проверка структурной вместимости
     * фолдов (§11.9). Вердикт: `REJECTED | RESEARCH_ONLY | PAPER_ALLOWED | LIVE_ALLOWED`.
     * `researchMode=true` по умолчанию — LIVE недостижим без `confirmedForProduction`.
     */
    @GetMapping("/momentum/deployment-gate")
    suspend fun deploymentGate(
        @RequestParam(required = false) universe: List<String>?,
        @RequestParam(required = false) timeframe: String?,
        @RequestParam(defaultValue = "3220") days: Int,
        @RequestParam(required = false) lookbackBars: Int?,
        @RequestParam(required = false) skipBars: Int?,
        @RequestParam(required = false) reversalEnabled: Boolean?,
        @RequestParam(required = false) rebalanceBars: Int?,
        @RequestParam(required = false) longCount: Int?,
        @RequestParam(required = false) shortCount: Int?,
        @RequestParam(defaultValue = "8") folds: Int,
        @RequestParam(defaultValue = "0.30") holdoutFraction: Double,
        @RequestParam(defaultValue = "2000") simulations: Int,
        @RequestParam(defaultValue = "42") seed: Long,
        @RequestParam(defaultValue = "true") researchMode: Boolean,
        @RequestParam(defaultValue = "false") confirmedForProduction: Boolean,
        @RequestParam(required = false) capitalSlice: Double?,
        @RequestParam(required = false) momentumWeight: Double?,
        @RequestParam(required = false) carryWeight: Double?,
        @RequestParam(required = false) carryLookbackDays: Int?,
        @RequestParam(required = false) minUniverse: Int?,
        @RequestParam(required = false) initialCapital: Double?,
        @RequestParam(required = false) volScalingEnabled: Boolean?,
        @RequestParam(required = false) volLookbackBars: Int?,
        @RequestParam(required = false) volTargetEnabled: Boolean?,
        @RequestParam(required = false) volTargetAnnualized: Double?,
        @RequestParam(required = false) volTargetBarsPerYear: Double?,
    ) = service.deploymentGate(
        universe = universe,
        timeframe = timeframe,
        days = days,
        lookbackBars = lookbackBars,
        skipBars = skipBars,
        reversalEnabled = reversalEnabled,
        rebalanceBars = rebalanceBars,
        longCount = longCount,
        shortCount = shortCount,
        capitalSlice = capitalSlice,
        momentumWeight = momentumWeight,
        carryWeight = carryWeight,
        carryLookbackDays = carryLookbackDays,
        minUniverse = minUniverse,
        initialCapital = initialCapital,
        volScalingEnabled = volScalingEnabled,
        volLookbackBars = volLookbackBars,
        volTargetEnabled = volTargetEnabled,
        volTargetAnnualized = volTargetAnnualized,
        volTargetBarsPerYear = volTargetBarsPerYear,
        folds = folds,
        holdoutFraction = holdoutFraction,
        researchMode = researchMode,
        confirmedForProduction = confirmedForProduction,
        simulations = simulations,
        seed = seed,
    )
}
