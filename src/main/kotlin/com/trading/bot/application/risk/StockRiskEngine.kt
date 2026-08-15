package com.trading.bot.application.risk

import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.DailyRiskGuard
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.RiskEngine
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.risk.TradingCalendar
import com.trading.bot.domain.risk.VolatilityFilter
import com.trading.bot.infrastructure.metrics.MutableGauges
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Риск-движок для акций/валют. Отвечает ТОЛЬКО на вопрос «можно ли входить» —
 * Да/Нет ([RiskVerdict]). Размер позиции (Kelly) и SL/TP считают
 * адаптивный риск-менеджмент (в оркестраторе входа) и OrderBuilder.
 *
 * Порядок проверок перед входом:
 *   1. Trading hours → OUTSIDE_HOURS.
 *   2. Daily loss limit ([DailyRiskGuard]) → DAILY_LIMIT.
 *   3. Drawdown protection → DRAWDOWN_PROTECTION.
 *   4. Аномальный индекс волатильности (RVI) → VOLATILITY_INDEX.
 *   5. Входные данные валидны → INVALID_INPUT.
 *   6. Нет открытой позиции по этому тикеру → DUPLICATE_POSITION.
 *   7. Максимум открытых позиций → MAX_POSITIONS.
 *   8. Секторная концентрация → SECTOR_EXPOSURE.
 *   9. ATR%-фильтр волатильности инструмента → VOLATILITY_GUARD.
 *
 * Открытые позиции приходят через [EntryRequest.openPositions] — без БД.
 */
@Service
class StockRiskEngine(
    private val riskConfig: RiskConfig,
    private val leverageConfig: LeverageConfig,
    private val tradingCalendar: TradingCalendar,
    private val dailyRiskGuard: DailyRiskGuard,
    private val volatilityFilter: VolatilityFilter,
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

        if (request.entryPrice <= BigDecimal.ZERO || request.portfolioMoney <= BigDecimal.ZERO) {
            return reject("INVALID_INPUT")
        }
        if (request.openPositions.any { it.ticker == request.ticker }) return reject("DUPLICATE_POSITION")
        val maxOpen = request.maxOpenPositions ?: riskConfig.maxOpenPositions
        if (request.openPositions.size >= maxOpen) return reject("MAX_POSITIONS")
        if (exceedsSectorExposure(request.ticker, request.openPositions)) return reject("SECTOR_EXPOSURE")
        if (isVolatilityTooHigh(request.atr, request.entryPrice)) return reject("VOLATILITY_GUARD")

        MutableGauges.set(meterRegistry, "risk.stock.entry.allowed", 1.0)
        logger.info { "Entry ALLOWED ${request.ticker} ${request.direction}" }
        return RiskVerdict.Allowed
    }

    private fun exceedsSectorExposure(
        ticker: String,
        openPositions: List<com.trading.bot.model.entity.Position>,
    ): Boolean {
        val sector = sectorOf(ticker)
        return openPositions.count { sectorOf(it.ticker) == sector } >= riskConfig.maxSectorExposure
    }

    private fun sectorOf(ticker: String): String = riskConfig.sectors[ticker] ?: "UNKNOWN"

    private fun isVolatilityTooHigh(
        atr: BigDecimal?,
        price: BigDecimal,
    ): Boolean {
        if (!riskConfig.enabled || atr == null || atr <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return false
        val atrPercent =
            atr
                .multiply(BigDecimal("100"))
                .divide(price, 4, RoundingMode.HALF_UP)
                .toDouble()
        return atrPercent > riskConfig.maxVolatilityPercent
    }

    private fun reject(reason: String): RiskVerdict = rejected(reason, meterRegistry, logger)
}
