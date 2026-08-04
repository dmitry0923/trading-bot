package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.*
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class RiskManagementService(
    private val riskConfig: RiskConfig
) {
    private val logger = mu.KotlinLogging.logger {}
    private var dailyPnL: BigDecimal = BigDecimal.ZERO

    fun isDailyLossLimitReached(): Boolean =
        dailyPnL <= riskConfig.maxDailyLossRub.negate()

    fun validateNewStrategy(strategy: Strategy, openPositions: List<Position>): RiskCheckResult {
        if (riskConfig.enabled && isDailyLossLimitReached()) {
            return RiskCheckResult(false, "Daily loss limit reached ($dailyPnL <= -${riskConfig.maxDailyLossRub})", 0)
        }
        if (riskConfig.enabled && openPositions.size >= riskConfig.maxOpenPositions) {
            return RiskCheckResult(false, "Max open positions reached (${riskConfig.maxOpenPositions})", 0)
        }
        if (riskConfig.enabled && exceedsSectorExposure(strategy.ticker, openPositions)) {
            val sector = sectorOf(strategy.ticker)
            val count = openPositions.count { sectorOf(it.ticker) == sector }
            return RiskCheckResult(
                false,
                "Sector concentration exceeded: $count open in sector $sector >= max ${riskConfig.maxSectorExposure}",
                0
            )
        }
        return RiskCheckResult(true, "OK", strategy.quantity)
    }

    /**
     * Проверка волатильности: ATR% от цены больше лимита → вход запрещён.
     * Вызывается перед открытием позиции (при наличии ATR).
     */
    fun isVolatilityTooHigh(atr: BigDecimal?, price: BigDecimal): Boolean {
        if (!riskConfig.enabled || atr == null || atr <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return false
        val atrPercent = atr.multiply(BigDecimal("100"))
            .divide(price, 4, java.math.RoundingMode.HALF_UP)
            .toDouble()
        val result = atrPercent > riskConfig.maxVolatilityPercent
        logger.info { "Volatility check: ATR%=$atrPercent vs limit=${riskConfig.maxVolatilityPercent}% -> ${if (result) "BLOCK" else "OK"}" }
        return result
    }

    /**
     * Секторная концентрация: количество открытых позиций в одном секторе.
     * Справочник секторов — из risk.sectors (ticker -> sector), иначе "UNKNOWN".
     */
    fun exceedsSectorExposure(ticker: String, openPositions: List<Position>): Boolean {
        val sector = sectorOf(ticker)
        val count = openPositions.count { sectorOf(it.ticker) == sector }
        return count >= riskConfig.maxSectorExposure
    }

    fun sectorOf(ticker: String): String =
        riskConfig.sectors[ticker] ?: "UNKNOWN"

    fun shouldCloseBySL(pos: Position, price: BigDecimal): Boolean {
        return when (pos.direction) {
            PositionDirection.LONG -> pos.stopLoss != null && price <= pos.stopLoss
            PositionDirection.SHORT -> pos.stopLoss != null && price >= pos.stopLoss
        }
    }

    fun shouldCloseByTP(pos: Position, price: BigDecimal): Boolean {
        return when (pos.direction) {
            PositionDirection.LONG -> pos.takeProfit != null && price >= pos.takeProfit
            PositionDirection.SHORT -> pos.takeProfit != null && price <= pos.takeProfit
        }
    }

    fun shouldCloseByTrailing(pos: Position, price: BigDecimal): Boolean {
        if (!riskConfig.trailingStopEnabled || pos.trailingStopPrice == null) return false
        return when (pos.direction) {
            PositionDirection.LONG -> price <= pos.trailingStopPrice
            PositionDirection.SHORT -> price >= pos.trailingStopPrice
        }
    }

    fun updateTrailingStop(pos: Position, price: BigDecimal) {
        if (!riskConfig.trailingStopEnabled) return
        val percent = BigDecimal(riskConfig.trailingStopPercent.toString()).divide(BigDecimal("100"))
        val newStop = when (pos.direction) {
            PositionDirection.LONG -> price.multiply(BigDecimal.ONE.subtract(percent))
            PositionDirection.SHORT -> price.multiply(BigDecimal.ONE.add(percent))
        }
        pos.trailingStopPrice = newStop.setScale(2, RoundingMode.HALF_UP)
    }

    fun calcSL(entryPrice: BigDecimal, direction: PositionDirection): BigDecimal {
        val percent = BigDecimal(riskConfig.defaultStopLossPercent.toString()).divide(BigDecimal("100"))
        return when (direction) {
            PositionDirection.LONG -> entryPrice.multiply(BigDecimal.ONE.subtract(percent)).setScale(2, RoundingMode.HALF_UP)
            PositionDirection.SHORT -> entryPrice.multiply(BigDecimal.ONE.add(percent)).setScale(2, RoundingMode.HALF_UP)
        }
    }

    fun calcTP(entryPrice: BigDecimal, direction: PositionDirection): BigDecimal {
        val percent = BigDecimal(riskConfig.defaultTakeProfitPercent.toString()).divide(BigDecimal("100"))
        return when (direction) {
            PositionDirection.LONG -> entryPrice.multiply(BigDecimal.ONE.add(percent)).setScale(2, RoundingMode.HALF_UP)
            PositionDirection.SHORT -> entryPrice.multiply(BigDecimal.ONE.subtract(percent)).setScale(2, RoundingMode.HALF_UP)
        }
    }

    fun updateDailyPnL(pnl: BigDecimal) {
        dailyPnL = dailyPnL.add(pnl)
    }

    fun getDailyPnL(): BigDecimal = dailyPnL
}
