package com.trading.bot.backtest

import com.trading.bot.client.AlorClient
import com.trading.bot.client.LlmClient
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.*
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.RedisCacheService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.math.pow
import kotlin.math.sqrt

@Component
class BacktestEngine(
    private val candleRepository: CandleRepository,
    private val positionRepository: PositionRepository,
    private val riskManagementService: RiskManagementService,
    private val redisCacheService: RedisCacheService,
    private val alorClient: AlorClient,
    private val backtestConfig: BacktestConfig,
    private val tradingConfig: TradingConfig
) {
    private val logger = KotlinLogging.logger {}

    fun runBacktest(ticker: String): BacktestResult {
        logger.info { "=== BACKTEST START: $ticker ===" }
        val startDate = LocalDateTime.now().minusYears(backtestConfig.backtestYears.toLong())
        val endDate = LocalDateTime.now()

        // 1. Load historical candles
        val candles = candleRepository.findByTickerAndTimeframeAndTimeBetween(
            ticker, tradingConfig.timeframe, startDate, endDate
        ).sortedBy { it.time }

        if (candles.size < 100) {
            logger.warn { "Not enough candles for backtest: ${candles.size}" }
            return emptyResult(ticker, startDate, endDate)
        }

        logger.info { "Loaded ${candles.size} candles for $ticker" }

        var capital = backtestConfig.initialCapital
        var maxCapital = capital
        var maxDrawdown = BigDecimal.ZERO
        var maxDrawdownPercent = 0.0
        val equityCurve = mutableListOf<EquityPoint>()
        val trades = mutableListOf<BacktestTrade>()
        var openTrade: SimulatedTrade? = null
        var dailyPnl = BigDecimal.ZERO
        var dailyTrades = 0

        // 2. Walk-forward simulation
        for (i in 50 until candles.size step 10) { // check every 10 candles
            val currentCandle = candles[i]
            val currentPrice = currentCandle.close
            val currentTime = currentCandle.time

            // Update equity
            val unrealizedPnl = openTrade?.let { trade ->
                if (trade.direction == "LONG") {
                    currentPrice.subtract(trade.entryPrice).multiply(BigDecimal(trade.quantity))
                } else {
                    trade.entryPrice.subtract(currentPrice).multiply(BigDecimal(trade.quantity))
                }
            } ?: BigDecimal.ZERO

            val currentEquity = capital.add(unrealizedPnl)
            equityCurve.add(EquityPoint(currentTime, currentEquity))

            if (currentEquity > maxCapital) {
                maxCapital = currentEquity
            }
            val dd = maxCapital.subtract(currentEquity)
            val ddPct = dd.divide(maxCapital, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100)).toDouble()
            if (dd > maxDrawdown) {
                maxDrawdown = dd
                maxDrawdownPercent = ddPct
            }

            // Check open trade
            if (openTrade != null) {
                // Check stop loss
                val slHit = if (openTrade.direction == "LONG") {
                    currentPrice <= openTrade.stopLoss
                } else {
                    currentPrice >= openTrade.stopLoss
                }

                // Check take profit
                val tpHit = if (openTrade.direction == "LONG") {
                    currentPrice >= openTrade.takeProfit
                } else {
                    currentPrice <= openTrade.takeProfit
                }

                if (slHit || tpHit) {
                    val exitPrice = if (slHit) openTrade.stopLoss else openTrade.takeProfit
                    val pnl = if (openTrade.direction == "LONG") {
                        exitPrice.subtract(openTrade.entryPrice).multiply(BigDecimal(openTrade.quantity))
                    } else {
                        openTrade.entryPrice.subtract(exitPrice).multiply(BigDecimal(openTrade.quantity))
                    }
                    val commission = openTrade.entryPrice.add(exitPrice)
                        .multiply(BigDecimal(openTrade.quantity))
                        .multiply(BigDecimal(backtestConfig.commissionPercent / 100))
                    val netPnl = pnl.subtract(commission)

                    capital = capital.add(netPnl)
                    dailyPnl = dailyPnl.add(netPnl)
                    dailyTrades++

                    trades.add(BacktestTrade(
                        entryTime = openTrade.entryTime,
                        exitTime = currentTime,
                        direction = openTrade.direction,
                        entryPrice = openTrade.entryPrice,
                        exitPrice = exitPrice,
                        quantity = openTrade.quantity,
                        pnl = netPnl,
                        pnlPercent = netPnl.divide(
                            openTrade.entryPrice.multiply(BigDecimal(openTrade.quantity)),
                            4, RoundingMode.HALF_UP
                        ).multiply(BigDecimal(100)).toDouble(),
                        exitReason = if (slHit) "STOP_LOSS" else "TAKE_PROFIT"
                    ))

                    openTrade = null
                }
            }

            // Generate signal (simplified: use price action for backtest)
            // In real test, this would call StrategyService with mocked LLM
            if (openTrade == null && i < candles.size - 20) {
                val signal = generateSignal(candles.subList(0, i + 1))
                if (signal != null) {
                    val qty = (backtestConfig.initialCapital.divide(currentPrice, 0, RoundingMode.DOWN)).toInt()
                        .coerceAtMost(100).coerceAtLeast(1)
                    val sl = if (signal == "LONG") {
                        currentPrice.multiply(BigDecimal(1 - tradingConfig.stopLossPercent / 100))
                    } else {
                        currentPrice.multiply(BigDecimal(1 + tradingConfig.stopLossPercent / 100))
                    }
                    val tp = if (signal == "LONG") {
                        currentPrice.multiply(BigDecimal(1 + tradingConfig.takeProfitPercent / 100))
                    } else {
                        currentPrice.multiply(BigDecimal(1 - tradingConfig.takeProfitPercent / 100))
                    }

                    openTrade = SimulatedTrade(
                        entryTime = currentTime,
                        direction = signal,
                        entryPrice = currentPrice,
                        quantity = qty,
                        stopLoss = sl.setScale(2, RoundingMode.HALF_UP),
                        takeProfit = tp.setScale(2, RoundingMode.HALF_UP)
                    )
                }
            }
        }

        // Close any open trade at last price
        openTrade?.let { trade ->
            val lastPrice = candles.last().close
            val pnl = if (trade.direction == "LONG") {
                lastPrice.subtract(trade.entryPrice).multiply(BigDecimal(trade.quantity))
            } else {
                trade.entryPrice.subtract(lastPrice).multiply(BigDecimal(trade.quantity))
            }
            capital = capital.add(pnl)
            trades.add(BacktestTrade(
                entryTime = trade.entryTime,
                exitTime = candles.last().time,
                direction = trade.direction,
                entryPrice = trade.entryPrice,
                exitPrice = lastPrice,
                quantity = trade.quantity,
                pnl = pnl,
                pnlPercent = pnl.divide(
                    trade.entryPrice.multiply(BigDecimal(trade.quantity)),
                    4, RoundingMode.HALF_UP
                ).multiply(BigDecimal(100)).toDouble(),
                exitReason = "END_OF_TEST"
            ))
        }

        // Calculate metrics
        val winning = trades.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val losing = trades.count { (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
        val totalReturn = capital.subtract(backtestConfig.initialCapital)
        val totalReturnPct = totalReturn.divide(backtestConfig.initialCapital, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100)).toDouble()
        val winRate = if (trades.isNotEmpty()) winning.toDouble() / trades.size else 0.0

        val grossProfit = trades.filter { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
            .sumOf { it.pnl ?: BigDecimal.ZERO }
        val grossLoss = trades.filter { (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
            .sumOf { kotlin.math.abs((it.pnl ?: BigDecimal.ZERO).toDouble()) }
        val profitFactor = if (grossLoss > 0) grossProfit.toDouble() / grossLoss else 0.0

        val avgWin = if (winning > 0) grossProfit.divide(BigDecimal(winning), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val avgLoss = if (losing > 0) BigDecimal(grossLoss / losing).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO

        // Sharpe (simplified: daily returns)
        val returns = equityCurve.zipWithNext { a, b ->
            b.equity.subtract(a.equity).divide(a.equity, 6, RoundingMode.HALF_UP).toDouble()
        }
        val avgReturn = returns.average()
        val stdDev = if (returns.size > 1) {
            val mean = avgReturn
            sqrt(returns.map { (it - mean).pow(2) }.average())
        } else 0.0
        val sharpe = if (stdDev > 0) (avgReturn * 252) / (stdDev * sqrt(252.0)) else 0.0

        logger.info {
            """
            === BACKTEST RESULT: $ticker ===
            Period: $startDate to $endDate
            Trades: ${trades.size} (Win: $winning, Loss: $losing)
            Win Rate: ${String.format("%.1f", winRate * 100)}%
            Total Return: $totalReturn (${String.format("%.2f", totalReturnPct)}%)
            Max Drawdown: $maxDrawdown (${String.format("%.2f", maxDrawdownPercent)}%)
            Sharpe: ${String.format("%.2f", sharpe)}
            Profit Factor: ${String.format("%.2f", profitFactor)}
            =================================
            """.trimIndent()
        }

        return BacktestResult(
            ticker = ticker,
            startDate = startDate,
            endDate = endDate,
            totalTrades = trades.size,
            winningTrades = winning,
            losingTrades = losing,
            winRate = winRate,
            totalReturn = totalReturn,
            totalReturnPercent = totalReturnPct,
            maxDrawdown = maxDrawdown,
            maxDrawdownPercent = maxDrawdownPercent,
            sharpeRatio = sharpe,
            profitFactor = profitFactor,
            averageWin = avgWin,
            averageLoss = avgLoss,
            equityCurve = equityCurve,
            trades = trades
        )
    }

    private fun generateSignal(candles: List<Candle>): String? {
        if (candles.size < 20) return null
        val closes = candles.map { it.close.toDouble() }
        val sma20 = closes.takeLast(20).average()
        val sma50 = closes.takeLast(50).average()
        val last = closes.last()
        val prev = closes[closes.size - 2]

        return when {
            last > sma20 && sma20 > sma50 && last > prev * 1.001 -> "LONG"
            last < sma20 && sma20 < sma50 && last < prev * 0.999 -> "SHORT"
            else -> null
        }
    }

    private fun emptyResult(ticker: String, start: LocalDateTime, end: LocalDateTime) = BacktestResult(
        ticker = ticker, startDate = start, endDate = end,
        totalTrades = 0, winningTrades = 0, losingTrades = 0,
        winRate = 0.0, totalReturn = BigDecimal.ZERO, totalReturnPercent = 0.0,
        maxDrawdown = BigDecimal.ZERO, maxDrawdownPercent = 0.0,
        sharpeRatio = 0.0, profitFactor = 0.0,
        averageWin = BigDecimal.ZERO, averageLoss = BigDecimal.ZERO,
        equityCurve = emptyList(), trades = emptyList()
    )

    private data class SimulatedTrade(
        val entryTime: LocalDateTime,
        val direction: String,
        val entryPrice: BigDecimal,
        val quantity: Int,
        val stopLoss: BigDecimal,
        val takeProfit: BigDecimal
    )
}
