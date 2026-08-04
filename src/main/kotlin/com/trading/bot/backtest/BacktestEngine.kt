package com.trading.bot.backtest

import com.trading.bot.model.Candle
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.repository.CandleRepository
import com.trading.bot.service.IndicatorCalculator
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * Движок бэктеста.
 *
 * - Загружает исторические свечи (10-мин, MOEX ISS) из БД
 * - Проходит по свечам, генерируя сигналы индикаторами (RSI/MACD/Bollinger)
 * - Симулирует исполнение: комиссия 0.05% на оборот, проскальзывание 0.1% (market)
 * - Проверяет SL/TP по внутрисвечному диапазону (high/low)
 * - Считает метрики: Sharpe, MaxDD, PF, win rate
 *
 * Запуск (проект): ./gradlew bootRun --args="--spring.profiles.active=backtest"
 */
@Service
class BacktestEngine(
    private val candleRepo: CandleRepository,
) {
    private val logger = KotlinLogging.logger {}

    data class PositionSim(
        val direction: PositionDirection,
        val quantity: Int,
        val entryPrice: BigDecimal,
        val stopLoss: BigDecimal?,
        val takeProfit: BigDecimal?,
        val entryBars: Int,
    )

    /**
     * Прогон бэктеста по тикеру за N дней.
     *
     * @param ticker тикер инструмента
     * @param days глубина истории в днях
     * @param timeframe таймфрейм свечей
     * @param initialCapital стартовый капитал
     * @param minBarsForSignal минимальное число баров для сигнала
     * @param slPercent стоп-лосс в % от цены входа (по умолчанию 2%)
     * @param tpPercent тейк-профит в % от цены входа (по умолчанию 4%)
     */
    suspend fun run(
        ticker: String,
        days: Int = 365,
        timeframe: String = "MINUTE_10",
        initialCapital: BigDecimal = BigDecimal("100000"),
        minBarsForSignal: Int = 30,
        slPercent: Double = 0.02,
        tpPercent: Double = 0.04,
    ): BacktestResult {
        val from = LocalDateTime.now().minusDays(days.toLong())
        val candles = candleRepo.findByTickerAndTimeframeAndTimeBetween(ticker, timeframe, from, LocalDateTime.now())
        if (candles.size < minBarsForSignal + 2) {
            logger.warn { "Backtest $ticker: insufficient candles (${candles.size})" }
            return emptyResult(ticker)
        }
        logger.info { "Backtest $ticker: ${candles.size} candles loaded" }
        return simulate(ticker, candles, initialCapital, minBarsForSignal, slPercent, tpPercent)
    }

    /**
     * Прогон бэктеста с предварительной загрузкой истории через [HistoricalDataLoader].
     *
     * @param loader загрузчик исторических данных
     * @param ticker тикер инструмента
     * @param days глубина истории в днях
     * @param slPercent стоп-лосс в % от цены входа
     * @param tpPercent тейк-профит в % от цены входа
     */
    suspend fun runWithHistory(
        loader: HistoricalDataLoader,
        ticker: String,
        days: Int = 730,
        slPercent: Double = 0.02,
        tpPercent: Double = 0.04,
    ): BacktestResult {
        loader.loadAndSave(ticker, days)
        return run(ticker, days, slPercent = slPercent, tpPercent = tpPercent)
    }

    /**
     * Симуляция по закрытым свечам. Сигнал на баре i -> исполнение по открытию бара i+1.
     *
     * Учёт: при открытии cash -= entry*qty + commission_entry.
     * При закрытии cash += exit*qty - commission_exit (тело возвращается автоматически).
     * PnL сделки включает обе комиссии.
     */
    fun simulate(
        ticker: String,
        candles: List<Candle>,
        initialCapital: BigDecimal = BigDecimal("100000"),
        minBarsForSignal: Int = 30,
        slPercent: Double = 0.02,
        tpPercent: Double = 0.04,
    ): BacktestResult {
        var cash = initialCapital
        val equityCurve = ArrayList<BigDecimal>()
        val tradeReturns = ArrayList<Double>()

        var position: PositionSim? = null
        val sorted = candles.sortedBy { it.time }

        for (i in 1 until sorted.size) {
            val current = sorted[i]

            // Закрытие по SL/TP на внутрисвечном диапазоне текущей свечи
            val pos0 = position
            if (pos0 != null && pos0.stopLoss != null && pos0.takeProfit != null) {
                when (SimulatedExecution.hitStopOrTarget(current, pos0.stopLoss, pos0.takeProfit)) {
                    SimulatedExecution.StopTpHit.STOP -> {
                        cash = closePosition(ticker, pos0, "STOP_LOSS", pos0.stopLoss, cash, equityCurve, tradeReturns)
                        position = null
                    }

                    SimulatedExecution.StopTpHit.TARGET -> {
                        cash = closePosition(ticker, pos0, "TAKE_PROFIT", pos0.takeProfit, cash, equityCurve, tradeReturns)
                        position = null
                    }

                    null -> {}
                }
            }

            val signal = signalAt(sorted, i - 1, minBarsForSignal)
            if (signal == StrategyAction.HOLD || signal == StrategyAction.CLOSE) {
                // Удержание: фиксируем equity по текущей цене закрытия
                equityCurve.add(equityAt(cash, position, current.closePrice))
                continue
            }

            val curPos = position
            if (curPos != null) {
                // Инверсия сигнала: закрыть текущую позицию и открыть встречную
                val opposite = if (signal == StrategyAction.BUY) PositionDirection.SHORT else PositionDirection.LONG
                if (curPos.direction == opposite) {
                    cash = closePosition(ticker, curPos, "REVERSAL", current.openPrice, cash, equityCurve, tradeReturns)
                    position = openPosition(signal, current.openPrice, cash, i, slPercent, tpPercent)
                    if (position != null) {
                        cash = applyOpen(cash, position)
                    }
                }
                equityCurve.add(equityAt(cash, position, current.closePrice))
                continue
            }

            // Открытие новой позиции на открытии текущей свечи
            position = openPosition(signal, current.openPrice, cash, i, slPercent, tpPercent)
            if (position != null) {
                cash = applyOpen(cash, position)
            }
            equityCurve.add(equityAt(cash, position, current.closePrice))
        }

        // Закрыть оставшуюся позицию по последней цене
        position?.let { pos ->
            cash = closePosition(ticker, pos, "END_OF_PERIOD", sorted.last().closePrice, cash, equityCurve, tradeReturns)
        }
        equityCurve.add(cash)

        val result = BacktestMetrics.compute(ticker, equityCurve, tradeReturns)
        logger.info {
            "Backtest $ticker: return=${String.format("%.2f%%", result.totalReturn * 100)}, " +
                "Sharpe=${String.format("%.2f", result.sharpeRatio)}, MDD=${String.format("%.2f%%", result.maxDrawdown * 100)}, " +
                "PF=${String.format("%.2f", result.profitFactor)}, win=${String.format("%.2f%%", result.winRate * 100)}, " +
                "trades=${result.totalTrades} -> ${if (result.isPassable()) "PASS" else "REJECT"}"
        }
        return result
    }

    /**
     * Учёт открытия позиции: комиссия входа списывается, номинал остаётся в кэше
     * (позиция учитывается как нереализованный PnL в [equityAt]).
     */
    private fun applyOpen(
        cash: BigDecimal,
        pos: PositionSim,
    ): BigDecimal = cash.subtract(SimulatedExecution.commissionOn(pos.entryPrice))

    /** Оценка текущего капитала: cash + нереализованный PnL позиции (mark-to-market). */
    private fun equityAt(
        cash: BigDecimal,
        position: PositionSim?,
        marketPrice: BigDecimal,
    ): BigDecimal {
        if (position == null) return cash
        val qty = position.quantity.toBigDecimal()
        val unrealized =
            when (position.direction) {
                PositionDirection.LONG -> marketPrice.subtract(position.entryPrice)
                PositionDirection.SHORT -> position.entryPrice.subtract(marketPrice)
            }.multiply(qty)
        return cash.add(unrealized)
    }

    private fun openPosition(
        signal: StrategyAction,
        price: BigDecimal,
        cash: BigDecimal,
        bar: Int,
        slPercent: Double,
        tpPercent: Double,
    ): PositionSim? {
        if (cash <= BigDecimal.ZERO) return null
        val capitalSlice = cash.multiply(BigDecimal("0.20"))
        val qty = capitalSlice.divide(price, 0, RoundingMode.DOWN).toInt()
        val lotQty = SimulatedExecution.lotRounded(qty)
        if (lotQty <= 0) return null

        val direction = if (signal == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val fill = SimulatedExecution.marketFill(price, direction == PositionDirection.LONG)
        val sl = BigDecimal.valueOf(slPercent)
        val tp = BigDecimal.valueOf(tpPercent)
        return PositionSim(
            direction = direction,
            quantity = lotQty,
            entryPrice = fill.price,
            stopLoss =
                when (direction) {
                    PositionDirection.LONG -> fill.price.multiply(BigDecimal.ONE.subtract(sl)).setScale(2, RoundingMode.HALF_UP)
                    PositionDirection.SHORT -> fill.price.multiply(BigDecimal.ONE.add(sl)).setScale(2, RoundingMode.HALF_UP)
                },
            takeProfit =
                when (direction) {
                    PositionDirection.LONG -> fill.price.multiply(BigDecimal.ONE.add(tp)).setScale(2, RoundingMode.HALF_UP)
                    PositionDirection.SHORT -> fill.price.multiply(BigDecimal.ONE.subtract(tp)).setScale(2, RoundingMode.HALF_UP)
                },
            entryBars = bar,
        )
    }

    /**
     * Закрытие позиции: cash += exit*qty - commission_exit.
     * PnL сделки (с обеими комиссиями) записывается в tradeReturns.
     */
    private fun closePosition(
        ticker: String,
        pos: PositionSim,
        reason: String,
        price: BigDecimal,
        cash: BigDecimal,
        equityCurve: MutableList<BigDecimal>,
        tradeReturns: MutableList<Double>,
    ): BigDecimal {
        val fill = SimulatedExecution.marketFill(price, pos.direction == PositionDirection.SHORT)
        val commissionEntry = SimulatedExecution.commissionOn(pos.entryPrice)
        val commissionExit = SimulatedExecution.commissionOn(fill.price)
        val gross =
            when (pos.direction) {
                PositionDirection.LONG -> fill.price.subtract(pos.entryPrice)
                PositionDirection.SHORT -> pos.entryPrice.subtract(fill.price)
            }.multiply(BigDecimal(pos.quantity))
        val pnl = gross.subtract(commissionEntry).subtract(commissionExit)

        tradeReturns.add(pnl.toDouble())
        // Комиссия входа уже списана при открытии; здесь добавляется gross за вычетом комиссии выхода
        val newCash = cash.add(gross).subtract(commissionExit)
        equityCurve.add(newCash)
        logger.debug { "Backtest close $ticker $reason pnl=$pnl" }
        return newCash
    }

    /**
     * Сигнал на основе индикаторов (RSI + MACD + Bollinger).
     * Возвращает BUY/SELL/HOLD.
     */
    fun signalAt(
        candles: List<Candle>,
        index: Int,
        minBars: Int,
    ): StrategyAction {
        val window = candles.subList(0, index + 1)
        if (window.size < minBars) return StrategyAction.HOLD
        val ind = IndicatorCalculator.calculate(window) ?: return StrategyAction.HOLD

        return when {
            ind.rsi < 30 && ind.macdHistogram > 0 -> StrategyAction.BUY
            ind.rsi > 70 && ind.macdHistogram < 0 -> StrategyAction.SELL
            else -> StrategyAction.HOLD
        }
    }

    private fun emptyResult(ticker: String): BacktestResult =
        BacktestResult(
            ticker = ticker,
            totalReturn = 0.0,
            sharpeRatio = 0.0,
            maxDrawdown = 0.0,
            winRate = 0.0,
            profitFactor = 0.0,
            totalTrades = 0,
            avgHoldBars = 0.0,
            equityCurve = emptyList(),
            monthlyReturns = emptyMap(),
        )
}
