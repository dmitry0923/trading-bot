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
    private var dailyPnL: BigDecimal = BigDecimal.ZERO

    fun isDailyLossLimitReached(): Boolean =
        dailyPnL <= riskConfig.maxDailyLossRub.negate()

    fun validateNewStrategy(strategy: Strategy, openPositions: List<Position>): RiskCheckResult {
        if (isDailyLossLimitReached()) {
            return RiskCheckResult(false, "Daily loss limit reached ($dailyPnL <= -${riskConfig.maxDailyLossRub})", 0)
        }
        if (openPositions.size >= riskConfig.maxOpenPositions) {
            return RiskCheckResult(false, "Max open positions reached", 0)
        }
        return RiskCheckResult(true, "OK", strategy.quantity)
    }

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
