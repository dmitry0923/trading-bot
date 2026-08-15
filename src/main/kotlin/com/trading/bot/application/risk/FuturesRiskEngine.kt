package com.trading.bot.application.risk

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.DailyRiskGuard
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.MarketRegime
import com.trading.bot.domain.risk.MarketRegimeProvider
import com.trading.bot.domain.risk.RiskEngine
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.risk.TradingCalendar
import com.trading.bot.domain.risk.VolatilityFilter
import com.trading.bot.infrastructure.metrics.MutableGauges
import com.trading.bot.model.PositionDirection
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
 *   5. Стрессовый режим волатильности (Market Regime = STRESS) → MARKET_STRESS.
 *   6. Уже есть открытая позиция (max futuresMaxOpenPositions) → MAX_POSITIONS.
 *   7. Инструмент поддерживается (futures) → UNSUPPORTED_INSTRUMENT.
 *   8. Входные данные валидны → INVALID_INPUT.
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
    private val marketRegimeProvider: MarketRegimeProvider,
    private val instrumentsConfig: InstrumentsConfig,
    private val meterRegistry: MeterRegistry,
) : RiskEngine {
    private val logger = KotlinLogging.logger {}

    override suspend fun canEnter(request: EntryRequest): RiskVerdict {
        if (!riskConfig.enabled) return reject("RISK_DISABLED")
        if (!leverageConfig.enabled) return reject("LEVERAGE_DISABLED")
        if (!tradingCalendar.isTradingAllowed()) return reject("OUTSIDE_HOURS")
        if (dailyRiskGuard.isDailyLossLimitReached(request.accountId)) return reject("DAILY_LIMIT")
        if (dailyRiskGuard.isEntryBlocked(request.accountId)) return reject("DRAWDOWN_PROTECTION")
        if (volatilityFilter.isVolatilityAnomalous()) return reject("VOLATILITY_INDEX")
        if (riskConfig.marketRegimeEnabled && marketRegimeProvider.currentRegime() == MarketRegime.STRESS) {
            return reject("MARKET_STRESS")
        }

        if (request.openPositions.size >= (request.maxOpenPositions ?: riskConfig.futuresMaxOpenPositions)) {
            return reject("MAX_POSITIONS")
        }

        if (!instrumentsConfig.isFutures(request.ticker)) return reject("UNSUPPORTED_INSTRUMENT")
        if (request.entryPrice <= BigDecimal.ZERO ||
            request.portfolioMoney <= BigDecimal.ZERO ||
            request.currentGo <= BigDecimal.ZERO
        ) {
            return reject("INVALID_INPUT")
        }

        MutableGauges.set(meterRegistry, "risk.futures.entry.allowed", 1.0)
        logger.info { "Entry ALLOWED ${request.ticker} ${request.direction}" }
        return RiskVerdict.Allowed
    }

    private fun reject(reason: String): RiskVerdict = rejected(reason, meterRegistry, logger)

    // ===================== Guardrail ликвидации (удержание) =====================

    /**
     * Проверка приближения к ликвидации.
     *
     * Формула расстояния (в % от остаточного буфера маржи), НАПРАВЛЕННАЯ по
     * направлению позиции (LONG/SHORT). |abs()| здесь недопустим: цена, уже
     * прошедшая уровень ликвидации, не может выглядеть «безопасной».
     *
     *   totalBuffer = |entryPrice - liquidationPrice|  (движение цены до ликвидации)
     *   remaining:
     *     LONG  -> currentPrice - liquidationPrice
     *     SHORT -> liquidationPrice - currentPrice
     *   distanceToLiquidation % = remaining / totalBuffer * 100
     *
     * На входе distance = 100%. По мере убытка буфер тает:
     *   remaining <= 0 (цена прошла уровень ликвидации) → CRITICAL
     *   < criticalLiquidationDistancePercent → CRITICAL (немедленное закрытие)
     *   < minLiquidationDistancePercent → WARNING
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

        // Направленный остаток буфера: отрицательный — цена УЖЕ прошла ликвидацию.
        val remaining =
            when (position.direction) {
                PositionDirection.LONG -> currentPrice.subtract(liq)
                PositionDirection.SHORT -> liq.subtract(currentPrice)
            }
        if (remaining <= BigDecimal.ZERO) {
            MutableGauges.set(meterRegistry, "futures.liquidation.distance", 0.0, Tags.of("ticker", position.ticker))
            logger.warn {
                "${position.ticker} ${position.direction} LIQUIDATED price=$currentPrice liq=$liq"
            }
            return LiquidationStatus.CRITICAL
        }

        val distancePercent =
            remaining
                .divide(totalBuffer, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
                .toDouble()
        MutableGauges.set(meterRegistry, "futures.liquidation.distance", distancePercent, Tags.of("ticker", position.ticker))

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

    enum class LiquidationStatus { SAFE, WARNING, CRITICAL }
}
