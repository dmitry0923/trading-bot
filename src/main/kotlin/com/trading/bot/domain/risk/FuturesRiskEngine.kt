package com.trading.bot.domain.risk

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Риск-движок для фьючерсов (Si). Risk-first: любое действие сначала проходит через него.
 *
 * Депозит 50 000 ₽. Дневной лимит убытка = maxDailyLossPercent% AUM
 * (risk.max-daily-loss-percent). Максимум 1 открытая позиция.
 * Плечо 2x (LeverageConfig).
 *
 * Порядок проверок перед входом (все обязательны):
 *   1. Trading hours (10:00–18:30 МСК) → OUTSIDE_HOURS.
 *   2. Daily loss limit (единый источник — [DailyRiskGuard]) → DAILY_LIMIT.
 *   3. Multi-Tier Drawdown Protection (7d/30d rolling, Shadow/Read-only) → DRAWDOWN_PROTECTION.
 *   4. Аномальный индекс волатильности MOEX (RVI) → VOLATILITY_INDEX.
 *   5. Уже есть открытая позиция (max 1) → MAX_POSITIONS.
 *   6. Расчёт размера позиции через FuturesPositionSizer (включая лимит маржи
 *      maxMarginUsagePercent) → quantity == 0 → запрет (INSUFFICIENT_MARGIN и др.).
 *
 * Guardrails во время удержания:
 *   - checkLiquidationDistance: остаточный буфер маржи
 *     < minLiquidationDistancePercent → WARNING, < criticalLiquidationDistancePercent → CRITICAL.
 *   - updateTrailingStop: только в прибыль, с учётом вариационной маржи.
 */
@Service
class FuturesRiskEngine(
    private val riskConfig: RiskConfig,
    private val leverageConfig: LeverageConfig,
    private val positionSizer: FuturesPositionSizer,
    private val positionRepo: PositionRepository,
    private val tradingCalendar: TradingCalendar,
    private val instrumentsConfig: InstrumentsConfig,
    private val dailyRiskGuard: DailyRiskGuard,
    private val volatilityFilter: VolatilityFilter,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

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
        currentGo: BigDecimal,
    ): EntryValidationResult {
        if (!riskConfig.enabled) return reject("RISK_DISABLED")
        if (!leverageConfig.enabled) return reject("LEVERAGE_DISABLED")
        if (!tradingCalendar.isTradingAllowed()) return reject("OUTSIDE_HOURS")
        if (dailyRiskGuard.isDailyLossLimitReached()) return reject("DAILY_LIMIT")
        if (dailyRiskGuard.isEntryBlocked()) return reject("DRAWDOWN_PROTECTION")
        if (volatilityFilter.isVolatilityAnomalous()) return reject("VOLATILITY_INDEX")

        val open = positionRepo.findByStatus(PositionStatus.OPEN)
        if (open.size >= riskConfig.futuresMaxOpenPositions) return reject("MAX_POSITIONS")

        val instrument = instrumentsConfig.find(ticker)
        if (instrument == null || instrument.type != "FUTURES") return reject("UNSUPPORTED_INSTRUMENT")
        if (entryPrice <= BigDecimal.ZERO || portfolioMoney <= BigDecimal.ZERO || currentGo <= BigDecimal.ZERO) {
            return reject("INVALID_INPUT")
        }

        val stopLossPoints = riskConfig.defaultStopLossPoints
        val size = positionSizer.calculateContracts(ticker, portfolioMoney, stopLossPoints, currentGo, entryPrice, direction)
        if (size.quantity == 0) return reject(size.reason ?: "ZERO_RISK_SIZE")

        // SL/TP в ценах: entry ± пункты * priceStep
        val priceStep = instrument.priceStep
        val slOffset = BigDecimal(stopLossPoints).multiply(priceStep)
        val tpOffset = BigDecimal(riskConfig.defaultTakeProfitPoints).multiply(priceStep)
        val stopLossPrice =
            when (direction) {
                PositionDirection.LONG -> entryPrice.subtract(slOffset)
                PositionDirection.SHORT -> entryPrice.add(slOffset)
            }
        val takeProfitPrice =
            when (direction) {
                PositionDirection.LONG -> entryPrice.add(tpOffset)
                PositionDirection.SHORT -> entryPrice.subtract(tpOffset)
            }

        meterRegistry.gauge("futures.position.size", size.quantity.toDouble())
        meterRegistry.gauge("futures.margin.used", size.marginRequired.toDouble())
        val marginUtilizationPercent =
            size.marginRequired
                .multiply(BigDecimal("100"))
                .divide(portfolioMoney, 4, RoundingMode.HALF_UP)
                .toDouble()
        meterRegistry.gauge("risk.margin.utilization", marginUtilizationPercent)
        size.liquidationPrice?.let {
            meterRegistry.gauge(
                "futures.liquidation.distance",
                Tags.of("ticker", ticker),
                distanceToLiquidation(entryPrice, it, entryPrice),
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
            reason = null,
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
            reason = reason,
        )
    }

    // ===================== Daily P&L =====================

    /**
     * Учёт P&L закрытой фьючерсной сделки в дневном итоге.
     * Делегирование в единый источник [DailyRiskGuard] — без локального
     * состояния и без записи в daily_risk_snapshot.
     */
    fun updateDailyPnL(pnl: BigDecimal) {
        dailyRiskGuard.updateDailyPnl(pnl)
        meterRegistry.gauge("risk.daily.pnl", dailyRiskGuard.getDailyPnl().toDouble())
        meterRegistry.gauge("risk.daily.limit.reached", if (dailyRiskGuard.isDailyLossLimitReached()) 1.0 else 0.0)
    }

    /**
     * Достигнут ли дневной лимит убытка.
     *
     * @return true, если dailyPnL <= -maxDailyLossPercent% AUM (единый источник)
     */
    fun isDailyLossLimitReached(): Boolean = dailyRiskGuard.isDailyLossLimitReached()

    /**
     * Текущий дневной P&L (единый источник [DailyRiskGuard]).
     */
    fun getDailyPnL(): BigDecimal = dailyRiskGuard.getDailyPnl()

    /**
     * Сброс дневного риск-состояния. Нет локального состояния — аккумулятор
     * живёт в [DailyRiskGuard]. Метод сохранён для обратной совместимости.
     */
    fun resetDailyState() {
        dailyRiskGuard.cachedOrNeutral()
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
     *   < minLiquidationDistancePercent → WARNING
     *   < criticalLiquidationDistancePercent → CRITICAL (немедленное закрытие)
     *
     * Для Si: buffer = 15 ₽, на стопе (0.5 ₽) остаётся 96.7% → SAFE; guardrail — страховка при пробое стопа.
     */
    fun checkLiquidationDistance(
        position: Position,
        currentPrice: BigDecimal,
    ): LiquidationStatus {
        val liq = position.liquidationPrice
        if (liq == null || liq <= BigDecimal.ZERO || currentPrice <= BigDecimal.ZERO) {
            return LiquidationStatus.SAFE
        }
        val totalBuffer = position.entryPrice.subtract(liq).abs()
        if (totalBuffer <= BigDecimal.ZERO) return LiquidationStatus.CRITICAL

        val distancePercent = distanceToLiquidation(position.entryPrice, liq, currentPrice)
        meterRegistry.gauge("futures.liquidation.distance", Tags.of("ticker", position.ticker), distancePercent)

        val status =
            when {
                distancePercent < riskConfig.criticalLiquidationDistancePercent -> LiquidationStatus.CRITICAL
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

    private fun distanceToLiquidation(
        entry: BigDecimal,
        liq: BigDecimal,
        current: BigDecimal,
    ): Double {
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
    fun updateTrailingStop(
        position: Position,
        currentPrice: BigDecimal,
    ) {
        if (!riskConfig.trailingStopEnabled) return
        if (position.instrumentType != InstrumentType.FUTURES) return

        val pointValue = instrumentsConfig.pointValue(position.ticker)
        val qty = BigDecimal(position.quantity)
        val variationMargin =
            when (position.direction) {
                PositionDirection.LONG -> {
                    currentPrice.subtract(position.entryPrice).multiply(pointValue).multiply(qty)
                }

                PositionDirection.SHORT -> {
                    position.entryPrice
                        .subtract(currentPrice)
                        .multiply(pointValue)
                        .multiply(qty)
                }
            }
        position.variationMargin = variationMargin

        if (variationMargin <= BigDecimal.ZERO) return

        val percent = BigDecimal(riskConfig.trailingStopPercent.toString()).divide(BigDecimal("100"))
        var candidate =
            when (position.direction) {
                PositionDirection.LONG -> currentPrice.multiply(BigDecimal.ONE.subtract(percent))
                PositionDirection.SHORT -> currentPrice.multiply(BigDecimal.ONE.add(percent))
            }

        // Не ослабляем ниже жёсткого стопа
        position.stopLoss?.let { hardStop ->
            candidate =
                when (position.direction) {
                    PositionDirection.LONG -> if (candidate < hardStop) hardStop else candidate
                    PositionDirection.SHORT -> if (candidate > hardStop) hardStop else candidate
                }
        }

        val currentStop = position.trailingStopPrice
        val improved =
            when (position.direction) {
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
        val reason: String?,
    )

    enum class LiquidationStatus { SAFE, WARNING, CRITICAL }
}
