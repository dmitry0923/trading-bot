package com.trading.bot.application.risk

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.DailyRiskGuard
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.RiskEngine
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.risk.TradingCalendar
import com.trading.bot.domain.risk.VolatilityFilter
import com.trading.bot.model.entity.Position
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Риск-движок для фьючерсов (Si). Отвечает ТОЛЬКО на вопрос «можно ли входить» —
 * Да/Нет ([RiskVerdict]). Размер позиции и SL/TP вычисляет
 * [com.trading.bot.domain.risk.PositionSizer] (FuturesPositionSizer), параметры
 * заявки собирает OrderBuilder.
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
 *   5. Уже есть открытая позиция (max futuresMaxOpenPositions) → MAX_POSITIONS.
 *   6. Инструмент поддерживается (futures) → UNSUPPORTED_INSTRUMENT.
 *   7. Входные данные валидны → INVALID_INPUT.
 *
 * Без Spring-состояния и без БД: открытые позиции приходят через
 * [EntryRequest.openPositions].
 */
@Service
class FuturesRiskEngine(
    private val riskConfig: RiskConfig,
    private val leverageConfig: LeverageConfig,
    private val tradingCalendar: TradingCalendar,
    private val dailyRiskGuard: DailyRiskGuard,
    private val volatilityFilter: VolatilityFilter,
    private val instrumentsConfig: InstrumentsConfig,
    private val meterRegistry: MeterRegistry,
) : RiskEngine {
    private val logger = KotlinLogging.logger {}

    override suspend fun canEnter(request: EntryRequest): RiskVerdict {
        if (!riskConfig.enabled) return reject("RISK_DISABLED")
        if (!leverageConfig.enabled) return reject("LEVERAGE_DISABLED")
        if (!tradingCalendar.isTradingAllowed()) return reject("OUTSIDE_HOURS")
        if (dailyRiskGuard.isDailyLossLimitReached()) return reject("DAILY_LIMIT")
        if (dailyRiskGuard.isEntryBlocked()) return reject("DRAWDOWN_PROTECTION")
        if (volatilityFilter.isVolatilityAnomalous()) return reject("VOLATILITY_INDEX")

        if (request.openPositions.size >= riskConfig.futuresMaxOpenPositions) return reject("MAX_POSITIONS")

        if (!instrumentsConfig.isFutures(request.ticker)) return reject("UNSUPPORTED_INSTRUMENT")
        if (request.entryPrice <= BigDecimal.ZERO ||
            request.portfolioMoney <= BigDecimal.ZERO ||
            request.currentGo <= BigDecimal.ZERO
        ) {
            return reject("INVALID_INPUT")
        }

        meterRegistry.gauge("risk.futures.entry.allowed", 1.0)
        logger.info { "Entry ALLOWED ${request.ticker} ${request.direction}" }
        return RiskVerdict.Allowed
    }

    private fun reject(reason: String): RiskVerdict {
        meterRegistry.counter("risk.entry.rejected", Tags.of("reason", reason)).increment()
        logger.warn { "Entry REJECTED: $reason" }
        return RiskVerdict.Rejected(reason)
    }

    // ===================== Guardrail ликвидации (удержание) =====================

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

    enum class LiquidationStatus { SAFE, WARNING, CRITICAL }
}
