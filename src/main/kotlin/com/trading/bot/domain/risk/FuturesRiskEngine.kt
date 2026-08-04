package com.trading.bot.domain.risk

import com.trading.bot.application.TradingHoursGuard
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.repository.DailyRiskSnapshotRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Риск-движок для фьючерсов (Si). Risk-first: любое действие сначала проходит через него.
 *
 * Депозит 50 000 ₽. Дневной лимит убытка 10% = 5 000 ₽ (risk.max-daily-loss-rub).
 * Максимум 1 открытая позиция. Плечо 2x (LeverageConfig).
 *
 * Порядок проверок перед входом (все обязательны):
 *   1. Сброс daily-состояния при смене дня (с восстановлением из daily_risk_snapshot).
 *   2. Trading hours (10:00–18:30 МСК) → OUTSIDE_HOURS.
 *   3. Daily loss limit → DAILY_LIMIT.
 *   4. Уже есть открытая позиция (max 1) → MAX_POSITIONS.
 *   5. Расчёт размера позиции через FuturesPositionSizer → quantity == 0 → запрет.
 *   6. marginRequired <= portfolioMoney * maxMarginUsagePercent (30%) → INSUFFICIENT_MARGIN.
 *
 * Guardrails во время удержания:
 *   - checkLiquidationDistance: остаточный буфер маржи < 25% → WARNING, < 10% → CRITICAL.
 *   - updateTrailingStop: только в прибыль, с учётом вариационной маржи.
 */
@Service
class FuturesRiskEngine(
    private val riskConfig: RiskConfig,
    private val leverageConfig: LeverageConfig,
    private val positionSizer: FuturesPositionSizer,
    private val positionRepo: PositionRepository,
    private val tradingHoursGuard: TradingHoursGuard,
    private val instrumentsConfig: InstrumentsConfig,
    private val dailyRiskSnapshotRepo: DailyRiskSnapshotRepository,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}
    private val moscowZone: ZoneId = ZoneId.of("Europe/Moscow")

    private var dailyPnL: BigDecimal = BigDecimal.ZERO
    private var dailyLossLimitReached: Boolean = false
    private var lastTradingDate: LocalDate = LocalDate.now(moscowZone)
    private var maxDrawdownToday: BigDecimal = BigDecimal.ZERO

    /** Доля остаточного буфера маржи для CRITICAL-ликвидации (10%). */
    private val criticalLiquidationPercent = 10.0

    init {
        restoreDailyState()
    }

    // ===================== Вход =====================

    /**
     * Все проверки перед входом. Возвращает EntryValidationResult:
     * allowed=false + reason, либо разрешённый размер позиции с SL/TP/liq.
     */
    suspend fun validateEntry(
        ticker: String,
        entryPrice: BigDecimal,
        direction: PositionDirection,
        portfolioMoney: BigDecimal,
        currentGo: BigDecimal
    ): EntryValidationResult {
        resetDailyStateIfNewDay()

        if (!riskConfig.enabled) return reject("RISK_DISABLED")
        if (!leverageConfig.enabled) return reject("LEVERAGE_DISABLED")
        if (!tradingHoursGuard.isTradingAllowed()) return reject("OUTSIDE_HOURS")
        if (dailyLossLimitReached) return reject("DAILY_LIMIT")

        val open = positionRepo.findByStatus(PositionStatus.OPEN)
        if (open.size >= riskConfig.futuresMaxOpenPositions) return reject("MAX_POSITIONS")

        val instrument = instrumentsConfig.find(ticker)
        if (instrument == null || instrument.type != "FUTURES") return reject("UNSUPPORTED_INSTRUMENT")
        if (entryPrice <= BigDecimal.ZERO || portfolioMoney <= BigDecimal.ZERO || currentGo <= BigDecimal.ZERO) {
            return reject("INVALID_INPUT")
        }

        val stopLossPoints = riskConfig.defaultStopLossPoints
        val size = positionSizer.calculateSiContracts(portfolioMoney, stopLossPoints, currentGo, entryPrice, direction)
        if (size.quantity == 0) return reject(size.reason ?: "ZERO_RISK_SIZE")

        // 6. Маржинальная проверка: marginRequired <= депозит * 30%
        val marginBudget = portfolioMoney
            .multiply(BigDecimal(riskConfig.maxMarginUsagePercent.toString()))
            .divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        if (size.marginRequired > marginBudget) return reject("INSUFFICIENT_MARGIN")

        // SL/TP в ценах: entry ± пункты * priceStep
        val priceStep = instrument.priceStep
        val slOffset = BigDecimal(stopLossPoints).multiply(priceStep)
        val tpOffset = BigDecimal(riskConfig.defaultTakeProfitPoints).multiply(priceStep)
        val stopLossPrice = when (direction) {
            PositionDirection.LONG -> entryPrice.subtract(slOffset)
            PositionDirection.SHORT -> entryPrice.add(slOffset)
        }
        val takeProfitPrice = when (direction) {
            PositionDirection.LONG -> entryPrice.add(tpOffset)
            PositionDirection.SHORT -> entryPrice.subtract(tpOffset)
        }

        meterRegistry.gauge("futures.position.size", size.quantity.toDouble())
        meterRegistry.gauge("futures.margin.used", size.marginRequired.toDouble())
        val marginUtilizationPercent = size.marginRequired
            .multiply(BigDecimal("100"))
            .divide(portfolioMoney, 4, RoundingMode.HALF_UP)
            .toDouble()
        meterRegistry.gauge("risk.margin.utilization", marginUtilizationPercent)
        size.liquidationPrice?.let {
            meterRegistry.gauge(
                "futures.liquidation.distance",
                Tags.of("ticker", ticker),
                distanceToLiquidation(entryPrice, it, entryPrice).toDouble()
            )
        }

        logger.info {
            "Entry ALLOWED $ticker $direction qty=${size.quantity} " +
                "margin=${size.marginRequired} risk=${size.riskAmount} sl=$stopLossPrice tp=$takeProfitPrice liq=${size.liquidationPrice}"
        }
        return EntryValidationResult(
            allowed = true,
            quantity = size.quantity,
            marginRequired = size.marginRequired,
            stopLossPrice = stopLossPrice,
            takeProfitPrice = takeProfitPrice,
            liquidationPrice = size.liquidationPrice,
            reason = null
        )
    }

    private fun reject(reason: String): EntryValidationResult {
        meterRegistry.counter("risk.entry.rejected", Tags.of("reason", reason)).increment()
        logger.warn { "Entry REJECTED: $reason" }
        return EntryValidationResult(
            allowed = false,
            quantity = 0,
            marginRequired = BigDecimal.ZERO,
            stopLossPrice = BigDecimal.ZERO,
            takeProfitPrice = BigDecimal.ZERO,
            liquidationPrice = null,
            reason = reason
        )
    }

    // ===================== Daily P&L =====================

    /**
     * Обновляет дневной P&L (закрытая сделка). При dailyPnL <= -5000 → dailyLossLimitReached = true.
     * Состояние персистится в daily_risk_snapshot.
     */
    fun updateDailyPnL(pnl: BigDecimal) {
        resetDailyStateIfNewDay()
        dailyPnL = dailyPnL.add(pnl)
        if (dailyPnL < maxDrawdownToday) {
            maxDrawdownToday = dailyPnL
        }
        if (dailyPnL <= riskConfig.maxDailyLossRub.negate()) {
            dailyLossLimitReached = true
            logger.error { "DAILY LOSS LIMIT reached: dailyPnL=$dailyPnL <= -${riskConfig.maxDailyLossRub}" }
        }
        persistDailyState()
        meterRegistry.gauge("risk.daily.pnl", dailyPnL.toDouble())
        meterRegistry.gauge("risk.daily.limit.reached", if (dailyLossLimitReached) 1.0 else 0.0)
    }

    /**
     * Достигнут ли дневной лимит убытка.
     *
     * @return true, если dailyPnL <= -maxDailyLossRub
     */
    fun isDailyLossLimitReached(): Boolean {
        resetDailyStateIfNewDay()
        return dailyLossLimitReached
    }

    /**
     * Текущий дневной P&L.
     *
     * @return накопленный дневной P&L (восстановленный из снапшота при смене дня)
     */
    fun getDailyPnL(): BigDecimal {
        resetDailyStateIfNewDay()
        return dailyPnL
    }

    /**
     * Смена календарного дня (МСК) → сброс дневного состояния.
     * При рестарте в течение дня восстанавливает значения из БД.
     */
    private fun resetDailyStateIfNewDay() {
        val today = LocalDate.now(moscowZone)
        if (lastTradingDate == today) return
        lastTradingDate = today
        loadDailyState(today)
    }

    /**
     * Полный сброс/восстановление дневного риск-состояния для текущего дня
     * (из daily_risk_snapshot либо нулевое). Используется при старте, доступен
     * для ручного сброса (admin) и для тестов.
     */
    fun resetDailyState() {
        lastTradingDate = LocalDate.now(moscowZone)
        loadDailyState(lastTradingDate)
    }

    private fun loadDailyState(today: LocalDate) {
        val snapshot = try {
            dailyRiskSnapshotRepo.findByDate(today)
        } catch (e: Exception) {
            logger.warn(e) { "Daily risk snapshot load failed" }
            null
        }
        dailyPnL = snapshot?.dailyPnl ?: BigDecimal.ZERO
        dailyLossLimitReached = snapshot?.limitReached ?: false
        maxDrawdownToday = snapshot?.maxDrawdownToday ?: BigDecimal.ZERO
        logger.info {
            "Daily risk state for $today: dailyPnL=$dailyPnL limitReached=$dailyLossLimitReached"
        }
    }

    private fun restoreDailyState() {
        try {
            resetDailyState()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to restore daily risk state on startup" }
        }
    }

    private fun persistDailyState() {
        try {
            dailyRiskSnapshotRepo.upsert(lastTradingDate, dailyPnL, dailyLossLimitReached, maxDrawdownToday)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist daily risk snapshot" }
        }
    }

    // ===================== Ликвидация =====================

    /**
     * Проверка приближения к ликвидации.
     *
     * Формула расстояния (в % от остаточного буфера маржи):
     *   totalBuffer     = |entryPrice - liquidationPrice|  (движение цены до ликвидации)
     *   remainingBuffer = |currentPrice - liquidationPrice|
     *   distanceToLiquidation % = remainingBuffer / totalBuffer * 100
     *
     * На входе distance = 100%. По мере убытка буфер тает:
     *   < 25% (minLiquidationDistancePercent) → WARNING
     *   < 10%                                   → CRITICAL (немедленное закрытие)
     *
     * Для Si: buffer = 15 ₽, на стопе (0.5 ₽) остаётся 96.7% → SAFE; guardrail — страховка при пробое стопа.
     */
    fun checkLiquidationDistance(position: Position, currentPrice: BigDecimal): LiquidationStatus {
        val liq = position.liquidationPrice
        if (liq == null || liq <= BigDecimal.ZERO || currentPrice <= BigDecimal.ZERO) {
            return LiquidationStatus.SAFE
        }
        val totalBuffer = position.entryPrice.subtract(liq).abs()
        if (totalBuffer <= BigDecimal.ZERO) return LiquidationStatus.CRITICAL

        val distancePercent = distanceToLiquidation(position.entryPrice, liq, currentPrice)
        meterRegistry.gauge("futures.liquidation.distance", Tags.of("ticker", position.ticker), distancePercent.toDouble())

        val status = when {
            distancePercent < criticalLiquidationPercent -> LiquidationStatus.CRITICAL
            distancePercent < riskConfig.minLiquidationDistancePercent -> LiquidationStatus.WARNING
            else -> LiquidationStatus.SAFE
        }
        if (status != LiquidationStatus.SAFE) {
            logger.warn {
                "${position.ticker} ${position.direction} distanceToLiquidation=$distancePercent% " +
                    "price=$currentPrice liq=$liq status=$status"
            }
        }
        return status
    }

    private fun distanceToLiquidation(entry: BigDecimal, liq: BigDecimal, current: BigDecimal): Double {
        val totalBuffer = entry.subtract(liq).abs()
        if (totalBuffer <= BigDecimal.ZERO) return 0.0
        val remaining = current.subtract(liq).abs()
        return remaining
            .divide(totalBuffer, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
            .toDouble()
    }

    // ===================== Trailing stop =====================

    /**
     * Обновление trailing stop для фьючерсов с учётом вариационной маржи.
     * - Считает variationMargin: (price - entry) * qty * pointValue (LONG), знак обратный для SHORT.
     * - Двигает trailing stop только в прибыль (vm > 0) и только в «улучшающую» сторону.
     * - Никогда не ослабляет ниже жёсткого stopLoss.
     */
    fun updateTrailingStop(position: Position, currentPrice: BigDecimal) {
        if (!riskConfig.trailingStopEnabled) return
        if (position.instrumentType != InstrumentType.FUTURES) return

        val pointValue = instrumentsConfig.pointValue(position.ticker)
        val qty = BigDecimal(position.quantity)
        val variationMargin = when (position.direction) {
            PositionDirection.LONG -> currentPrice.subtract(position.entryPrice).multiply(pointValue).multiply(qty)
            PositionDirection.SHORT -> position.entryPrice.subtract(currentPrice).multiply(pointValue).multiply(qty)
        }
        position.variationMargin = variationMargin

        if (variationMargin <= BigDecimal.ZERO) return

        val percent = BigDecimal(riskConfig.trailingStopPercent.toString()).divide(BigDecimal("100"))
        var candidate = when (position.direction) {
            PositionDirection.LONG -> currentPrice.multiply(BigDecimal.ONE.subtract(percent))
            PositionDirection.SHORT -> currentPrice.multiply(BigDecimal.ONE.add(percent))
        }

        // Не ослабляем ниже жёсткого стопа
        position.stopLoss?.let { hardStop ->
            candidate = when (position.direction) {
                PositionDirection.LONG -> if (candidate < hardStop) hardStop else candidate
                PositionDirection.SHORT -> if (candidate > hardStop) hardStop else candidate
            }
        }

        val currentStop = position.trailingStopPrice
        val improved = when (position.direction) {
            PositionDirection.LONG -> currentStop == null || candidate > currentStop
            PositionDirection.SHORT -> currentStop == null || candidate < currentStop
        }
        if (improved) {
            position.trailingStopPrice = candidate.setScale(4, RoundingMode.HALF_UP)
            logger.debug { "Trailing stop updated ${position.ticker} -> $candidate (vm=$variationMargin)" }
        }
    }

    // ===================== Результаты =====================

    data class EntryValidationResult(
        val allowed: Boolean,
        val quantity: Int,
        val marginRequired: BigDecimal,
        val stopLossPrice: BigDecimal,
        val takeProfitPrice: BigDecimal,
        val liquidationPrice: BigDecimal?,
        val reason: String?
    )

    enum class LiquidationStatus { SAFE, WARNING, CRITICAL }
}
