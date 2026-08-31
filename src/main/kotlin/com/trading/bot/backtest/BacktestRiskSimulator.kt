package com.trading.bot.backtest

import com.trading.bot.application.decision.NetEvGate
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.Atr
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.Position
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * In-memory воспроизведение полной цепочки risk gates из [com.trading.bot.application.decision.DecisionEngine.doOpenPosition].
 *
 * Цель — backtest parity: бэктест прогоняет ТЕ ЖЕ проверки, что и LIVE, чтобы
 * результаты отражали реальное поведение риск-менеджмента, а не idealized условия.
 *
 * Архитектура:
 * - Вся state — in-memory (дневной P&L, пик equity, открытые позиции, история сделок)
 * - Нет зависимостей от БД, Redis, Alor API
 * - Существующие чистые функции (NetEvGate, ExitRules, PositionSizer) используются напрямую
 * - Kelly sizing считается из накопленной истории сделок через [KellyMath]
 *
 * Воспроизводимые gates (зеркало DecisionEngine.doOpenPosition):
 * 1. DegenerateCaseGuard (spread, gap)
 * 2. Daily loss limit (дневной лимит убытка)
 * 3. Drawdown protection (скользящие 7d/30d лимиты, consecutive losses, shadow mode)
 * 4. Volatility index (упрощённый: ATR% > maxVolatilityPercent)
 * 5. Duplicate position
 * 6. Max positions
 * 7. Sector exposure
 * 8. ATR% guard
 * 9. Pre-sizing: correlation (упрощённый: sector-based)
 * 10. Kelly sizing (Wilson + staged + vol targeting + confidence + drawdown degradation)
 * 11. Post-sizing: gross/net exposure
 * 12. NET EV gate (reuse NetEvGate directly)
 * 13. Portfolio risk (упрощённый: concentration + effective positions)
 * 14. Minimum lot policy
 */
class BacktestRiskSimulator(
    private val riskConfig: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val netEvGate: NetEvGate? = null,
) {
    private val logger = KotlinLogging.logger {}

    // ===== In-memory state =====

    /** Дневной P&L (сумма pnl закрытых сделок за текущий день). Сбрасывается при смене дня. */
    private var dailyPnl: BigDecimal = BigDecimal.ZERO

    /** Текущий день (для автосброса dailyPnl). */
    private var currentDay: LocalDate? = null

    /** Пиковый equity (для drawdown tracking). */
    private var peakEquity: BigDecimal = BigDecimal.ZERO

    /** Текущий equity (mark-to-market). */
    private var currentEquity: BigDecimal = BigDecimal.ZERO

    /** История сделок (для Kelly sizing). */
    private val tradeHistory = BacktestTradeAnalysisService()

    /** Открытые позиции (模拟, для проверки duplicate/max/sector/exposure). */
    private val openPositions = mutableListOf<BacktestSimPosition>()

    /** Циклический ID для идентификации прогона. */
    var cycleId: String = "backtest-sim"

    /**
     * Результат проверки всех risk gates.
     */
    data class GateResult(
        val allowed: Boolean,
        val reason: String? = null,
        val kellySizeLots: Int = 0,
        val kellySizeRub: BigDecimal = BigDecimal.ZERO,
        val netEvResult: NetEvGate.GateResult? = null,
    )

    /**
     * Sim-позиция для проверки portfolio limits (упрощённая версия Position).
     */
    data class BacktestSimPosition(
        val ticker: String,
        val direction: PositionDirection,
        val quantity: Int,
        val entryPrice: BigDecimal,
        val notional: BigDecimal,
        val sector: String,
        val entryTime: LocalDateTime,
    )

    // ===== Public API =====

    /**
     * Инициализация state перед началом прогона.
     */
    fun initialize(initialCapital: BigDecimal) {
        dailyPnl = BigDecimal.ZERO
        currentDay = null
        peakEquity = initialCapital
        currentEquity = initialCapital
        tradeHistory.allTrades().forEach { /* clear — create new instance */ }
        openPositions.clear()
    }

    /**
     * Полная проверка всех risk gates перед входом — зеркало DecisionEngine.doOpenPosition.
     *
     * @param ticker тикер
     * @param signal стратегический сигнал (BUY/SELL)
     * @param entryPrice цена входа
     * @param cash текущий cash
     * @param candle текущая свеча (для spread/gap checks)
     * @param history история свечей (для ATR, volatility)
     * @param signalStrength сила сигнала (0..1) для confidence sizing
     * @param currentTime текущее время симуляции
     * @return [GateResult] — pass/block + kelly size
     */
    suspend fun checkEntry(
        ticker: String,
        signal: StrategyAction,
        entryPrice: BigDecimal,
        cash: BigDecimal,
        candle: Candle,
        history: List<Candle>,
        signalStrength: Double? = null,
        currentTime: LocalDateTime = candle.time,
    ): GateResult {
        val direction = if (signal == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val spec = instrumentsConfig.find(ticker)
        val lotSize = spec?.lotSize?.coerceAtLeast(1) ?: 1
        val notionalPerLot = entryPrice.multiply(BigDecimal(lotSize))

        // Сброс дневного P&L при смене дня
        val day = currentTime.toLocalDate()
        if (currentDay != day) {
            dailyPnl = BigDecimal.ZERO
            currentDay = day
        }

        // ATR из history
        val atr = if (history.isNotEmpty()) Atr.calculate(history, riskConfig.futuresAtrStopPeriod) else null

        // ===== Gate 1: DegenerateCaseGuard (spread, gap) =====
        if (history.size >= 2) {
            val prevCandle = history[history.size - 2]
            val gap = abs(candle.openPrice.subtract(prevCandle.closePrice).toDouble())
            val prevClose = prevCandle.closePrice.toDouble()
            if (prevClose > 0) {
                val gapPercent = gap / prevClose * 100.0
                if (gapPercent > riskConfig.maxVolatilityPercent) {
                    logger.debug { "Backtest risk: DEGENERATE gap ${String.format("%.2f%%", gapPercent)} for $ticker" }
                    return GateResult(allowed = false, reason = "DEGENERATE_GAP")
                }
            }
        }

        // ===== Gate 2: Daily loss limit =====
        if (isDailyLossLimitReached(cash)) {
            logger.debug { "Backtest risk: DAILY_LIMIT for $ticker (dailyPnl=$dailyPnl)" }
            return GateResult(allowed = false, reason = "DAILY_LIMIT")
        }

        // ===== Gate 3: Drawdown protection =====
        if (isDrawdownBlocking()) {
            logger.debug { "Backtest risk: DRAWDOWN_PROTECTION for $ticker" }
            return GateResult(allowed = false, reason = "DRAWDOWN_PROTECTION")
        }

        // ===== Gate 4: Volatility (ATR% guard) =====
        if (atr == null || atr <= BigDecimal.ZERO || entryPrice <= BigDecimal.ZERO) {
            // ATR — обязательный risk input (паритет с live StockRiskEngine /
            // RiskManagementService). Недоступность данных о волатильности блокирует
            // вход (fail-closed), см. risk.volatility-fail-closed.
            if (riskConfig.volatilityFailClosed) {
                logger.debug { "Backtest risk: VOLATILITY_GUARD $ticker ATR unavailable (=$atr) -> fail-closed" }
                return GateResult(allowed = false, reason = "VOLATILITY_GUARD")
            }
        } else {
            val atrPercent = atr.multiply(BigDecimal("100")).divide(entryPrice, 4, RoundingMode.HALF_UP).toDouble()
            if (atrPercent > riskConfig.maxVolatilityPercent) {
                logger.debug { "Backtest risk: VOLATILITY_GUARD $ticker ATR%=${String.format("%.2f%%", atrPercent)}" }
                return GateResult(allowed = false, reason = "VOLATILITY_GUARD")
            }
        }

        // ===== Gate 5: Duplicate position =====
        if (openPositions.any { it.ticker == ticker }) {
            logger.debug { "Backtest risk: DUPLICATE_POSITION for $ticker" }
            return GateResult(allowed = false, reason = "DUPLICATE_POSITION")
        }

        // ===== Gate 6: Max positions =====
        if (openPositions.size >= riskConfig.maxOpenPositions) {
            logger.debug { "Backtest risk: MAX_POSITIONS (${openPositions.size}/${riskConfig.maxOpenPositions})" }
            return GateResult(allowed = false, reason = "MAX_POSITIONS")
        }

        // ===== Gate 7: Sector exposure =====
        val sector = riskConfig.sectors[ticker] ?: "UNKNOWN"
        val sectorCount = openPositions.count { it.sector == sector }
        if (sectorCount >= riskConfig.maxSectorExposure) {
            logger.debug { "Backtest risk: SECTOR_EXPOSURE $ticker ($sector: $sectorCount)" }
            return GateResult(allowed = false, reason = "SECTOR_EXPOSURE")
        }

        // ===== Gate 8: Kelly sizing =====
        val kellyResult =
            calculateKellySize(
                ticker = ticker,
                entryPrice = entryPrice,
                cash = cash,
                lotSize = lotSize,
                atr = atr,
                signalStrength = signalStrength,
            )

        if (kellyResult.first <= 0) {
            val reason =
                if (kellyResult.second > BigDecimal.ZERO && kellyResult.second < notionalPerLot) {
                    "KELLY_BELOW_MIN_LOT"
                } else {
                    "ZERO_RISK_SIZE"
                }
            logger.debug { "Backtest risk: $reason for $ticker (kellyRub=${kellyResult.second})" }
            return GateResult(allowed = false, reason = reason, kellySizeRub = kellyResult.second)
        }

        // ===== Gate 9: Post-sizing gross/net exposure =====
        val candidateNotional = notionalPerLot.multiply(BigDecimal(kellyResult.first))
        val exposureCheck = checkPortfolioExposure(ticker, direction, candidateNotional)
        if (exposureCheck != null) {
            logger.debug { "Backtest risk: $exposureCheck for $ticker" }
            return GateResult(allowed = false, reason = exposureCheck)
        }

        // ===== Gate 10: NET EV gate =====
        val expectedNet = calculateExpectedNet(ticker)
        val netEvResult =
            if (netEvGate != null) {
                val snapshot = createSyntheticSnapshot(ticker, entryPrice, spec)
                netEvGate.check(ticker, expectedNet, snapshot)
            } else {
                NetEvGate.GateResult.Pass
            }

        if (netEvResult is NetEvGate.GateResult.Blocked) {
            logger.debug { "Backtest risk: NET_EV for $ticker (netEV=${netEvResult.netEV})" }
            return GateResult(
                allowed = false,
                reason = "NET_EV_TOO_LOW",
                kellySizeLots = kellyResult.first,
                kellySizeRub = kellyResult.second,
                netEvResult = netEvResult,
            )
        }

        // ===== Gate 11: Simplified portfolio concentration =====
        val concentrationCheck = checkConcentration(ticker, direction, candidateNotional)
        if (concentrationCheck != null) {
            logger.debug { "Backtest risk: $concentrationCheck for $ticker" }
            return GateResult(allowed = false, reason = concentrationCheck)
        }

        // ===== ALL GATES PASSED =====
        logger.debug {
            "Backtest risk: ALLOWED $ticker $direction lots=${kellyResult.first} " +
                "(kellyRub=${kellyResult.second}, dailyPnl=$dailyPnl)"
        }
        return GateResult(
            allowed = true,
            kellySizeLots = kellyResult.first,
            kellySizeRub = kellyResult.second,
            netEvResult = netEvResult,
        )
    }

    /**
     * Записать результат закрытия позиции — обновляет state для будущих проверок.
     */
    fun recordClose(
        ticker: String,
        direction: PositionDirection,
        entryPrice: BigDecimal,
        exitPrice: BigDecimal,
        quantity: Int,
        entryTime: LocalDateTime,
        exitTime: LocalDateTime,
        closeReason: String,
        commission: BigDecimal,
        cash: BigDecimal,
    ) {
        val spec = instrumentsConfig.find(ticker)
        val lotSize = spec?.lotSize?.toLong() ?: 1L
        val pnl =
            when (direction) {
                PositionDirection.LONG -> exitPrice.subtract(entryPrice)
                PositionDirection.SHORT -> entryPrice.subtract(exitPrice)
            }.multiply(BigDecimal(quantity * lotSize)).subtract(commission)

        tradeHistory.recordClose(
            BacktestTradeAnalysisService.ClosedTradeRecord(
                ticker = ticker,
                pnl = pnl,
                entryPrice = entryPrice,
                exitPrice = exitPrice,
                direction = direction,
                entryTime = entryTime,
                exitTime = exitTime,
                closeReason = closeReason,
                commission = commission,
            ),
        )

        // Update daily P&L
        val day = exitTime.toLocalDate()
        if (currentDay != day) {
            dailyPnl = BigDecimal.ZERO
            currentDay = day
        }
        dailyPnl = dailyPnl.add(pnl)

        // Update peak equity
        val newEquity = cash.add(pnl)
        if (newEquity > peakEquity) peakEquity = newEquity
        currentEquity = newEquity

        // Remove from open positions
        openPositions.removeAll { it.ticker == ticker }
    }

    /**
     * Открытие позиции — добавляет в tracked open positions.
     */
    fun recordOpen(
        ticker: String,
        direction: PositionDirection,
        quantity: Int,
        entryPrice: BigDecimal,
        lotSize: Int,
        entryTime: LocalDateTime,
    ) {
        val notional = entryPrice.multiply(BigDecimal(quantity * lotSize))
        openPositions.add(
            BacktestSimPosition(
                ticker = ticker,
                direction = direction,
                quantity = quantity,
                entryPrice = entryPrice,
                notional = notional,
                sector = riskConfig.sectors[ticker] ?: "UNKNOWN",
                entryTime = entryTime,
            ),
        )
    }

    // ===== Internal gate implementations =====

    /**
     * Дневной лимит убытка: min(effectiveDailyLossPercent × AUM, maxDailyLossRub).
     */
    private fun isDailyLossLimitReached(currentCash: BigDecimal): Boolean {
        if (currentCash <= BigDecimal.ZERO) return true
        val percentLimit =
            currentCash
                .multiply(
                    BigDecimal(riskConfig.maxDailyLossPercent),
                ).divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val effectiveLimit =
            if (riskConfig.maxDailyLossRub > BigDecimal.ZERO) {
                minOf(percentLimit, riskConfig.maxDailyLossRub)
            } else {
                percentLimit
            }
        return dailyPnl <= effectiveLimit.negate()
    }

    /**
     * Drawdown protection: rolling 7d, 30d, consecutive losses, shadow mode.
     */
    private fun isDrawdownBlocking(): Boolean {
        if (peakEquity <= BigDecimal.ZERO) return false

        // Drawdown from peak
        val drawdownPercent = BigDecimal.ONE.subtract(currentEquity.divide(peakEquity, 6, RoundingMode.HALF_UP)).toDouble() * 100.0
        if (drawdownPercent >= riskConfig.drawdownScaleTiers.keys.maxOrNull() ?: 15.0) return true

        // Rolling loss limits
        val rolling7d = tradeHistory.rollingPnl(LocalDateTime.now(), 7)
        val rolling30d = tradeHistory.rollingPnl(LocalDateTime.now(), 30)
        val rolling7dLimit =
            peakEquity
                .multiply(
                    BigDecimal(riskConfig.maxRollingLossPercent7d),
                ).divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val rolling30dLimit =
            peakEquity
                .multiply(
                    BigDecimal(riskConfig.maxRollingLossPercent30d),
                ).divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        if (rolling7d <= rolling7dLimit.negate()) return true
        if (rolling30d <= rolling30dLimit.negate()) return true

        // Consecutive losses → shadow mode
        if (riskConfig.shadowModeEnabled && tradeHistory.currentConsecutiveLosses() >= riskConfig.maxConsecutiveLosses) {
            return true
        }

        return false
    }

    /**
     * Kelly sizing — воспроизводит StockEntryProfile.sizePosition:
     * 1. Wilson lower bound win rate
     * 2. Kelly fraction
     * 3. Staged multiplier by sample size
     * 4. Volatility targeting
     * 5. Confidence sizing
     * 6. Drawdown degradation
     * 7. Risk cap
     *
     * @return (lots, kellySizeRub)
     */
    private fun calculateKellySize(
        ticker: String,
        entryPrice: BigDecimal,
        cash: BigDecimal,
        lotSize: Int,
        atr: BigDecimal?,
        signalStrength: Double?,
    ): Pair<Int, BigDecimal> {
        val spec = instrumentsConfig.find(ticker)
        val notionalPerLot = entryPrice.multiply(BigDecimal(lotSize))

        // 1. Gather stats from trade history
        val stats = tradeHistory.analyze(currentTime = LocalDateTime.now())[ticker]

        // 2. Kelly base
        val base =
            if (stats == null) {
                // No data: fallback fraction
                val fallbackFraction = minOf(riskConfig.kellyNoDataFraction, riskConfig.kellyMaxPositionFraction)
                cash.multiply(BigDecimal(fallbackFraction.toString()))
            } else {
                val w = KellyMath.wilsonLowerBound(stats.winRate, stats.totalTrades, riskConfig.kellyWilsonZ)
                val avgLossAbs = abs(stats.avgLoss.toDouble()).coerceAtLeast(0.01)
                val r = stats.avgWin.toDouble() / avgLossAbs
                val kelly = KellyMath.rawKellyFraction(w, stats.avgWin.toDouble(), avgLossAbs)
                val effectiveFraction =
                    riskConfig.kellyFraction * KellyMath.sampleSizeMultiplier(stats.totalTrades, riskConfig.kellySampleSizeTiers)
                val safeKelly = (kelly * effectiveFraction).coerceIn(0.0, riskConfig.kellyMaxPositionFraction)
                if (safeKelly > 0) cash.multiply(BigDecimal(safeKelly)) else BigDecimal.ZERO
            }

        var size = base

        // 3. Volatility targeting
        if (atr != null && atr > BigDecimal.ZERO && entryPrice > BigDecimal.ZERO) {
            val atrPercent = atr.multiply(BigDecimal("100")).divide(entryPrice, 4, RoundingMode.HALF_UP).toDouble()
            val dailyVol = KellyMath.dailyVolFromAtr(atrPercent, riskConfig.volatilityFallbackCandlesPerDay)
            val volMult =
                KellyMath.volatilityMultiplier(
                    dailyVol,
                    riskConfig.volatilityTargetPercent,
                    riskConfig.minVolatilitySizeMultiplier,
                    riskConfig.maxVolatilitySizeMultiplier,
                )
            size = size.multiply(BigDecimal(volMult))
        }

        // 4. Confidence sizing
        if (riskConfig.confidenceSizingEnabled && signalStrength != null) {
            // Simplified: use 0.60 as default threshold (no DB-based calibration in backtest)
            val threshold = 0.60
            val factor =
                KellyMath.confidenceFactor(
                    signalStrength,
                    threshold,
                    riskConfig.confidenceSizingCeiling,
                    riskConfig.confidenceSizingMinFactor,
                    riskConfig.confidenceSizingMaxFactor,
                )
            size = size.multiply(BigDecimal(factor))
        }

        // 5. Drawdown degradation
        val drawdownPercent =
            if (peakEquity > BigDecimal.ZERO) {
                BigDecimal.ONE.subtract(currentEquity.divide(peakEquity, 6, RoundingMode.HALF_UP)).toDouble() * 100.0
            } else {
                0.0
            }
        val ddFactor = KellyMath.drawdownScaleMultiplier(drawdownPercent, riskConfig.drawdownScaleTiers)
        val recoveryFactor =
            if (tradeHistory.currentConsecutiveLosses() >=
                riskConfig.maxConsecutiveLosses
            ) {
                riskConfig.kellyDrawdownReduction
            } else {
                1.0
            }
        size = size.multiply(BigDecimal(minOf(ddFactor, recoveryFactor)))

        // 6. Risk cap: loss at stop must not exceed riskPerTradePercent% of cash
        val effectiveSl = spec?.effectiveSlPercent(riskConfig.defaultStopLossPercent) ?: riskConfig.defaultStopLossPercent
        val commissionPerLot = spec?.commissionRub ?: BigDecimal.ZERO
        val useAtr = atr != null && atr > BigDecimal.ZERO && riskConfig.atrSlMultiplier > BigDecimal.ZERO
        val slDistance =
            if (useAtr) {
                atr.multiply(riskConfig.atrSlMultiplier)
            } else {
                entryPrice.multiply(effectiveSl).divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
            }
        val lossPerLot = slDistance.multiply(BigDecimal(lotSize)).add(commissionPerLot.multiply(BigDecimal(2)))
        val riskAmount =
            cash
                .multiply(
                    BigDecimal(riskConfig.riskPerTradePercent.toString()),
                ).divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val maxLotsByRisk =
            if (lossPerLot > BigDecimal.ZERO) {
                riskAmount.divide(lossPerLot, 0, RoundingMode.DOWN).toInt()
            } else {
                0
            }

        // 7. Convert Kelly rub to lots
        val kellyLots =
            if (notionalPerLot > BigDecimal.ZERO) {
                size.divide(notionalPerLot, 0, RoundingMode.DOWN).toInt()
            } else {
                0
            }

        val finalLots = minOf(kellyLots, maxLotsByRisk)
        return finalLots to size
    }

    /**
     * Проверка gross/net exposure limits (упрощённая).
     */
    private fun checkPortfolioExposure(
        ticker: String,
        direction: PositionDirection,
        candidateNotional: BigDecimal,
    ): String? {
        val totalLong = openPositions.filter { it.direction == PositionDirection.LONG }.sumOf { it.notional }
        val totalShort = openPositions.filter { it.direction == PositionDirection.SHORT }.sumOf { it.notional }

        val newLong = if (direction == PositionDirection.LONG) totalLong.add(candidateNotional) else totalLong
        val newShort = if (direction == PositionDirection.SHORT) totalShort.add(candidateNotional) else totalShort
        val gross = newLong.add(newShort)
        val net = newLong.subtract(newShort).abs()
        val aum = if (currentEquity > BigDecimal.ZERO) currentEquity else BigDecimal("100000")

        val grossPercent = gross.multiply(BigDecimal("100")).divide(aum, 4, RoundingMode.HALF_UP).toDouble()
        val netPercent = net.multiply(BigDecimal("100")).divide(aum, 4, RoundingMode.HALF_UP).toDouble()

        if (grossPercent > riskConfig.maxGrossExposurePercent) return "GROSS_EXPOSURE"
        if (netPercent > riskConfig.maxNetExposurePercent) return "NET_EXPOSURE"
        return null
    }

    /**
     * Проверка concentration: Effective positions (упрощённая HHI без корреляционной матрицы).
     * Если 1 позиция — effective = 1.0 (ниже minEffectivePositions → BLOCK).
     */
    private fun checkConcentration(
        ticker: String,
        direction: PositionDirection,
        candidateNotional: BigDecimal,
    ): String? {
        if (!riskConfig.portfolioRiskEnabled) return null

        val allNotionals = openPositions.map { abs(it.notional.toDouble()) }.toMutableList()
        val sign = if (direction == PositionDirection.LONG) 1.0 else -1.0
        allNotionals.add(sign * candidateNotional.toDouble())

        val gross = allNotionals.sumOf { abs(it) }
        if (gross <= 0.0) return null

        // Simplified effective positions: 1/HHI (equal weights → N, single position → 1)
        val weights = allNotionals.map { it / gross }
        val hhi = weights.sumOf { it * it }
        val effectivePositions = if (hhi > 0.0) 1.0 / hhi else 1.0

        if (effectivePositions < riskConfig.minEffectivePositions) return "PORTFOLIO_CONCENTRATION"

        // Directional concentration
        val net = allNotionals.sum()
        val concentration = abs(net) / gross * 100.0
        if (concentration > riskConfig.maxDirectionalConcentrationPercent) return "DIRECTIONAL_CONCENTRATION"

        return null
    }

    /**
     * Expected net profit per lot — из trade history (Wilson-based).
     * null если недостаточно статистики.
     */
    private fun calculateExpectedNet(ticker: String): BigDecimal? {
        val stats = tradeHistory.analyze(currentTime = LocalDateTime.now())[ticker] ?: return null
        if (stats.totalTrades < riskConfig.kellyMinTrades) return null
        if (stats.avgWin <= BigDecimal.ZERO && stats.avgLoss <= BigDecimal.ZERO) return null

        val w = KellyMath.wilsonLowerBound(stats.winRate, stats.totalTrades, riskConfig.kellyWilsonZ)
        val wBd = BigDecimal(w)
        val oneMinusW = BigDecimal(1 - w)
        return wBd.multiply(stats.avgWin).subtract(oneMinusW.multiply(stats.avgLoss))
    }

    /**
     * Synthetic MarketSnapshot для NetEvGate (bid/ask из candle data).
     */
    private fun createSyntheticSnapshot(
        ticker: String,
        price: BigDecimal,
        spec: InstrumentsConfig.InstrumentSpec?,
    ): MarketSnapshot {
        val priceStep = spec?.priceStep ?: BigDecimal("0.01")
        return MarketSnapshot(
            ticker = ticker,
            currentPrice = price,
            bid = price.subtract(priceStep),
            ask = price.add(priceStep),
            timestamp = java.time.Instant.now(),
        )
    }

    // ===== Status accessors for metrics =====

    fun currentDailyPnl(): BigDecimal = dailyPnl

    fun currentDrawdownPercent(): Double =
        if (peakEquity > BigDecimal.ZERO) {
            BigDecimal.ONE.subtract(currentEquity.divide(peakEquity, 6, RoundingMode.HALF_UP)).toDouble() * 100.0
        } else {
            0.0
        }

    fun openPositionCount(): Int = openPositions.size

    fun closedTradeCount(): Int = tradeHistory.totalTrades()

    fun maxConsecutiveLosses(): Int = tradeHistory.maxConsecutiveLosses()
}
