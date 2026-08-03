package com.trading.bot.service
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.*
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

@Service
class RiskManagementService(private val riskConfig: RiskConfig) {
    private val logger = KotlinLogging.logger {}
    private val dailyPnL = ConcurrentHashMap<LocalDate, BigDecimal>()

    data class Check(val allowed: Boolean, val reason: String, val adjustedQty: Int = 0)

    fun validateNewStrategy(s: Strategy, open: List<Position>): Check {
        if (!riskConfig.enabled) return Check(true, "Risk off")
        if (s.action == StrategyAction.HOLD) return Check(true, "HOLD")
        val loss = dailyPnL.getOrDefault(LocalDate.now(), BigDecimal.ZERO)
        if (loss < riskConfig.maxDailyLossRub.negate()) return Check(false, "Daily loss limit")
        if (s.confidence < 0.60) return Check(false, "Low confidence: ${s.confidence}")
        val val_ = s.targetPrice.multiply(BigDecimal(s.quantity))
        if (val_ > riskConfig.maxPositionRub) {
            val maxQ = riskConfig.maxPositionRub.divide(s.targetPrice, 0, RoundingMode.DOWN).toInt()
            return Check(true, "Reduced", maxQ.coerceAtLeast(1))
        }
        return Check(true, "OK", s.quantity)
    }
    fun calcSL(entry: BigDecimal, dir: PositionDirection): BigDecimal = when(dir) {
        PositionDirection.LONG -> entry.multiply(BigDecimal(1 - riskConfig.defaultStopLossPercent / 100)).setScale(2, RoundingMode.HALF_UP)
        PositionDirection.SHORT -> entry.multiply(BigDecimal(1 + riskConfig.defaultStopLossPercent / 100)).setScale(2, RoundingMode.HALF_UP)
    }
    fun calcTP(entry: BigDecimal, dir: PositionDirection): BigDecimal = when(dir) {
        PositionDirection.LONG -> entry.multiply(BigDecimal(1 + riskConfig.defaultTakeProfitPercent / 100)).setScale(2, RoundingMode.HALF_UP)
        PositionDirection.SHORT -> entry.multiply(BigDecimal(1 - riskConfig.defaultTakeProfitPercent / 100)).setScale(2, RoundingMode.HALF_UP)
    }
    fun updateTrailingStop(pos: Position, price: BigDecimal): Boolean {
        if (!riskConfig.trailingStopEnabled || pos.trailingStopPrice == null) return false
        val newStop = when(pos.direction) {
            PositionDirection.LONG -> price.multiply(BigDecimal(1 - riskConfig.trailingStopPercent / 100)).setScale(2, RoundingMode.HALF_UP)
            PositionDirection.SHORT -> price.multiply(BigDecimal(1 + riskConfig.trailingStopPercent / 100)).setScale(2, RoundingMode.HALF_UP)
        }
        val upd = when(pos.direction) {
            PositionDirection.LONG -> newStop > pos.trailingStopPrice!!
            PositionDirection.SHORT -> newStop < pos.trailingStopPrice!!
        }
        if (upd) { pos.trailingStopPrice = newStop; logger.info { "Trailing stop ${pos.ticker} -> $newStop" } }
        return upd
    }
    fun shouldCloseBySL(pos: Position, price: BigDecimal): Boolean = pos.stopLoss?.let { when(pos.direction) { PositionDirection.LONG -> price <= it; PositionDirection.SHORT -> price >= it } } ?: false
    fun shouldCloseByTP(pos: Position, price: BigDecimal): Boolean = pos.takeProfit?.let { when(pos.direction) { PositionDirection.LONG -> price >= it; PositionDirection.SHORT -> price <= it } } ?: false
    fun shouldCloseByTrailing(pos: Position, price: BigDecimal): Boolean = pos.trailingStopPrice?.let { when(pos.direction) { PositionDirection.LONG -> price <= it; PositionDirection.SHORT -> price >= it } } ?: false
    fun updateDailyPnL(pnl: BigDecimal) { dailyPnL.merge(LocalDate.now(), pnl, BigDecimal::add) }
    fun getDailyPnL(): BigDecimal = dailyPnL.getOrDefault(LocalDate.now(), BigDecimal.ZERO)
}
