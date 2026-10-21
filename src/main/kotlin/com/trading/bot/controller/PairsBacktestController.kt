package com.trading.bot.controller

import com.trading.bot.backtest.pairs.PairsBacktestService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Research-эндпоинт market-neutral FX-pairs бэктеста (`docs/25`).
 *
 * `GET /api/v1/pairs/backtest` — прогон пары (default CNYRUBF/USDRUBF) с IS/OOS
 * split; `GET /api/v1/pairs/validate` — walk-forward (фолды + consistency).
 * Query-параметры перекрывают `bt.pairs.*` без перезапуска. Live-путь не
 * затрагивается; вердикт — только по OOS/агрегату фолдов.
 */
@RestController
@RequestMapping("/api/v1/pairs")
class PairsBacktestController(
    private val service: PairsBacktestService,
) {
    @GetMapping("/backtest")
    suspend fun backtest(
        @RequestParam(required = false) legA: String?,
        @RequestParam(required = false) legB: String?,
        @RequestParam(required = false) timeframe: String?,
        @RequestParam(defaultValue = "730") days: Int,
        @RequestParam(required = false) lookbackBars: Int?,
        @RequestParam(required = false) entryZ: Double?,
        @RequestParam(required = false) exitZ: Double?,
        @RequestParam(required = false) stopZ: Double?,
        @RequestParam(required = false) maxHoldBars: Int?,
        @RequestParam(required = false) oosFraction: Double?,
        @RequestParam(required = false) capitalSlice: Double?,
        @RequestParam(required = false) initialCapital: Double?,
    ) = service.run(
        legA = legA,
        legB = legB,
        timeframe = timeframe,
        days = days,
        lookbackBars = lookbackBars,
        entryZ = entryZ,
        exitZ = exitZ,
        stopZ = stopZ,
        maxHoldBars = maxHoldBars,
        oosFraction = oosFraction,
        capitalSlice = capitalSlice,
        initialCapital = initialCapital,
    )

    @GetMapping("/validate")
    suspend fun validate(
        @RequestParam(required = false) legA: String?,
        @RequestParam(required = false) legB: String?,
        @RequestParam(required = false) timeframe: String?,
        @RequestParam(defaultValue = "730") days: Int,
        @RequestParam(required = false) lookbackBars: Int?,
        @RequestParam(required = false) entryZ: Double?,
        @RequestParam(required = false) exitZ: Double?,
        @RequestParam(required = false) stopZ: Double?,
        @RequestParam(required = false) maxHoldBars: Int?,
        @RequestParam(defaultValue = "6") folds: Int,
        @RequestParam(required = false) capitalSlice: Double?,
        @RequestParam(required = false) initialCapital: Double?,
    ) = service.validate(
        legA = legA,
        legB = legB,
        timeframe = timeframe,
        days = days,
        lookbackBars = lookbackBars,
        entryZ = entryZ,
        exitZ = exitZ,
        stopZ = stopZ,
        maxHoldBars = maxHoldBars,
        capitalSlice = capitalSlice,
        initialCapital = initialCapital,
        folds = folds,
    )
}
