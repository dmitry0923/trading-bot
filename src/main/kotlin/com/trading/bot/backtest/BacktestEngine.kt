package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.BacktestResultEntity
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.BacktestResultRepository
import com.trading.bot.repository.CandleRepository
import com.trading.bot.service.MlEntryFilter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID

/**
 * Движок бэктеста.
 *
 * - Загружает исторические свечи (10-мин, MOEX ISS) из БД
 * - Проходит по свечам, генерируя сигналы через [BacktestSignalGenerator]:
 *   по умолчанию детерминированная эвристика RSI/MACD, при `bt.agent.enabled=true`
 *   — конвейер LLM-агентов (roadmap 13.8.1, профиль `backtest`)
 * - Симулирует исполнение: комиссия 0.05% на оборот, проскальзывание 0.1% (market)
 * - Проверяет SL/TP по внутрисвечному диапазону (high/low)
 * - При `bt.ml-filter-enabled=true` гейтит входы ML-фильтром ([MlEntryFilter],
 *   roadmap 13.11.6): признаки на момент бара, confidence=null; блокировки
 *   считаются в метрику `bt_ml_blocked_total{ticker}` (live-гейт не затрагивается)
 * - Считает метрики: Sharpe, MaxDD, PF, win rate
 * - Сохраняет результат в `backtest_results` (roadmap v2.2, 13.7.3) и инкрементирует
 *   `bt_pass_total{result=PASS|REJECT}` для сравнения итераций стратегии.
 *
 * Лотность позиций берётся из [InstrumentsConfig] (как в live) — размер позиции
 * округляется вниз до целого лота, совпадая с реальным исполнением на бирже.
 *
 * Параметры прогона по умолчанию (слайс капитала, SL/TP, стартовый капитал,
 * глубина истории, таймфрейм, warm-up) — из [BacktestConfig] (prefix `bt.*`).
 *
 * Запуск (проект): ./gradlew bootRun --args="--spring.profiles.active=backtest"
 */
@Service
class BacktestEngine(
    private val candleRepo: CandleRepository,
    private val instrumentsConfig: InstrumentsConfig = InstrumentsConfig(),
    private val backtestConfig: BacktestConfig = BacktestConfig(),
    private val backtestResultRepository: BacktestResultRepository? = null,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val meterRegistry: MeterRegistry? = null,
    private val signalGenerator: BacktestSignalGenerator = DeterministicBacktestSignalGenerator(),
    private val mlEntryFilter: MlEntryFilter? = null,
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
     * @param days глубина истории в днях (по умолчанию `bt.days`)
     * @param timeframe таймфрейм свечей (по умолчанию `bt.timeframe`)
     * @param initialCapital стартовый капитал (по умолчанию `bt.initial-capital`)
     * @param minBarsForSignal минимальное число баров для сигнала (по умолчанию `bt.min-bars-for-signal`)
     * @param slPercent стоп-лосс в долях от цены входа (по умолчанию `bt.sl-percent` / 100)
     * @param tpPercent тейк-профит в долях от цены входа (по умолчанию `bt.tp-percent` / 100)
     * @param commissionMultiplier множитель ставки комиссии (1.0 = базово; стресс-прогон 13.7.8)
     * @param slippageMultiplier множитель ставки проскальзывания (1.0 = базово)
     */
    suspend fun run(
        ticker: String,
        days: Int = backtestConfig.days,
        timeframe: String = backtestConfig.timeframe,
        initialCapital: BigDecimal = backtestConfig.initialCapital,
        minBarsForSignal: Int = backtestConfig.minBarsForSignal,
        slPercent: Double = backtestConfig.slPercent / 100.0,
        tpPercent: Double = backtestConfig.tpPercent / 100.0,
        commissionMultiplier: Double = 1.0,
        slippageMultiplier: Double = 1.0,
    ): BacktestResult {
        val from = LocalDateTime.now().minusDays(days.toLong())
        val candles = candleRepo.findByTickerAndTimeframeAndTimeBetween(ticker, timeframe, from, LocalDateTime.now())
        if (candles.size < minBarsForSignal + 2) {
            logger.warn { "Backtest $ticker: insufficient candles (${candles.size})" }
            return emptyResult(ticker)
        }
        logger.info { "Backtest $ticker: ${candles.size} candles loaded" }
        val result =
            simulate(
                ticker,
                candles,
                initialCapital,
                minBarsForSignal,
                slPercent,
                tpPercent,
                commissionMultiplier,
                slippageMultiplier,
            )
        persistResult(ticker, result, days, timeframe, initialCapital, minBarsForSignal, slPercent, tpPercent)
        return result
    }

    /**
     * Сохранение результата прогона в `backtest_results` (roadmap v2.2, 13.7.3)
     * и метрика `bt_pass_total{result=PASS|REJECT}`. Пустые прогоны (0 сделок)
     * не сохраняются, чтобы не засорять историю сравнения итераций.
     */
    private suspend fun persistResult(
        ticker: String,
        result: BacktestResult,
        days: Int,
        timeframe: String,
        initialCapital: BigDecimal,
        minBarsForSignal: Int,
        slPercent: Double,
        tpPercent: Double,
    ) {
        if (result.totalTrades == 0) return
        meterRegistry?.counter("bt_pass_total", "result", if (result.isPassable()) "PASS" else "REJECT")?.increment()
        val params =
            mapOf(
                "days" to days,
                "timeframe" to timeframe,
                "initialCapital" to initialCapital,
                "minBarsForSignal" to minBarsForSignal,
                "slPercent" to slPercent,
                "tpPercent" to tpPercent,
            )
        try {
            backtestResultRepository?.save(
                BacktestResultEntity(
                    ticker = ticker,
                    params = objectMapper.writeValueAsString(params),
                    metrics = objectMapper.writeValueAsString(result.metrics()),
                ),
            )
        } catch (e: Exception) {
            // Персист результата — best-effort: сбой записи не должен ронять прогон.
            logger.warn(e) { "Backtest $ticker: failed to persist result" }
        }
    }

    /**
     * Симуляция по закрытым свечам. Сигнал на баре i -> исполнение по открытию бара i+1.
     *
     * Учёт: при открытии cash -= entry*qty + commission_entry.
     * При закрытии cash += exit*qty - commission_exit (тело возвращается автоматически).
     * PnL сделки включает обе комиссии.
     *
     * @param commissionMultiplier множитель ставки комиссии (стресс-прогоны 13.7.8)
     * @param slippageMultiplier множитель ставки проскальзывания (стресс-прогоны 13.7.8)
     */
    suspend fun simulate(
        ticker: String,
        candles: List<Candle>,
        initialCapital: BigDecimal = backtestConfig.initialCapital,
        minBarsForSignal: Int = backtestConfig.minBarsForSignal,
        slPercent: Double = backtestConfig.slPercent / 100.0,
        tpPercent: Double = backtestConfig.tpPercent / 100.0,
        commissionMultiplier: Double = 1.0,
        slippageMultiplier: Double = 1.0,
    ): BacktestResult {
        var cash = initialCapital
        val equityCurve = ArrayList<BigDecimal>()
        val tradeReturns = ArrayList<Double>()
        val cycleId = "backtest-$ticker-${UUID.randomUUID()}"
        var mlBlockedCount = 0

        var position: PositionSim? = null
        val sorted = candles.sortedBy { it.time }

        for (i in 1 until sorted.size) {
            val current = sorted[i]

            // Закрытие по SL/TP на внутрисвечном диапазоне текущей свечи
            val pos0 = position
            if (pos0 != null && pos0.stopLoss != null && pos0.takeProfit != null) {
                when (SimulatedExecution.hitStopOrTarget(current, pos0.stopLoss, pos0.takeProfit)) {
                    SimulatedExecution.StopTpHit.STOP -> {
                        cash =
                            closePosition(
                                ticker,
                                pos0,
                                "STOP_LOSS",
                                pos0.stopLoss,
                                cash,
                                equityCurve,
                                tradeReturns,
                                commissionMultiplier,
                                slippageMultiplier,
                            )
                        position = null
                    }

                    SimulatedExecution.StopTpHit.TARGET -> {
                        cash =
                            closePosition(
                                ticker,
                                pos0,
                                "TAKE_PROFIT",
                                pos0.takeProfit,
                                cash,
                                equityCurve,
                                tradeReturns,
                                commissionMultiplier,
                                slippageMultiplier,
                            )
                        position = null
                    }

                    null -> {}
                }
            }

            val signal = signalGenerator.signal(ticker, sorted, i - 1, minBarsForSignal, cycleId)
            if (signal == StrategyAction.HOLD || signal == StrategyAction.CLOSE) {
                // Удержание: фиксируем equity по текущей цене закрытия
                equityCurve.add(equityAt(cash, position, current.closePrice))
                continue
            }

            val curPos = position
            val entering = curPos == null || isOpposite(curPos.direction, signal)
            if (entering && backtestConfig.mlFilterEnabled && mlEntryFilter != null) {
                // ML-фильтр входа (раздел 13.11.6): признаки на момент бара, confidence
                // у детерминированного генератора отсутствует → null (отдельная категория).
                val blockReason = mlEntryFilter.shouldBlock(ticker, signal, null, current.time, requireEnabled = false)
                if (blockReason != null) {
                    mlBlockedCount++
                    logger.debug { "Backtest $ticker: ML filter blocked entry at ${current.time}: $blockReason" }
                    meterRegistry?.counter("bt_ml_blocked_total", "ticker", ticker)?.increment()
                    if (curPos != null) {
                        // Сигнал инверсии, но встречный вход отклонён фильтром → закрыть текущую позицию
                        cash =
                            closePosition(
                                ticker,
                                curPos,
                                "ML_FILTER_REVERSAL",
                                current.openPrice,
                                cash,
                                equityCurve,
                                tradeReturns,
                                commissionMultiplier,
                                slippageMultiplier,
                            )
                        position = null
                    }
                    equityCurve.add(equityAt(cash, position, current.closePrice))
                    continue
                }
            }

            if (curPos != null) {
                // Инверсия сигнала: закрыть текущую позицию и открыть встречную
                val opposite = if (signal == StrategyAction.BUY) PositionDirection.SHORT else PositionDirection.LONG
                if (curPos.direction == opposite) {
                    cash =
                        closePosition(
                            ticker,
                            curPos,
                            "REVERSAL",
                            current.openPrice,
                            cash,
                            equityCurve,
                            tradeReturns,
                            commissionMultiplier,
                            slippageMultiplier,
                        )
                    position =
                        openPosition(
                            ticker,
                            signal,
                            current.openPrice,
                            cash,
                            i,
                            slPercent,
                            tpPercent,
                            commissionMultiplier,
                            slippageMultiplier,
                        )
                    if (position != null) {
                        cash = applyOpen(cash, position, commissionMultiplier)
                    }
                }
                equityCurve.add(equityAt(cash, position, current.closePrice))
                continue
            }

            // Открытие новой позиции на открытии текущей свечи
            position =
                openPosition(
                    ticker,
                    signal,
                    current.openPrice,
                    cash,
                    i,
                    slPercent,
                    tpPercent,
                    commissionMultiplier,
                    slippageMultiplier,
                )
            if (position != null) {
                cash = applyOpen(cash, position, commissionMultiplier)
            }
            equityCurve.add(equityAt(cash, position, current.closePrice))
        }

        // Закрыть оставшуюся позицию по последней цене
        position?.let { pos ->
            cash =
                closePosition(
                    ticker,
                    pos,
                    "END_OF_PERIOD",
                    sorted.last().closePrice,
                    cash,
                    equityCurve,
                    tradeReturns,
                    commissionMultiplier,
                    slippageMultiplier,
                )
        }
        equityCurve.add(cash)

        val result = BacktestMetrics.compute(ticker, equityCurve, tradeReturns)
        logger.info {
            "Backtest $ticker: return=${String.format("%.2f%%", result.totalReturn * 100)}, " +
                "Sharpe=${String.format("%.2f", result.sharpeRatio)}, Sortino=${String.format("%.2f", result.sortinoRatio)}, " +
                "MDD=${String.format("%.2f%%", result.maxDrawdown * 100)}, PF=${String.format("%.2f", result.profitFactor)}, " +
                "win=${String.format("%.2f%%", result.winRate * 100)}, expectancy=${String.format("%.2f", result.expectancy)}, " +
                "W/L=${String.format("%.2f", result.winLossRatio)}, trades=${result.totalTrades}, " +
                "mlBlocked=$mlBlockedCount " +
                "-> ${if (result.isPassable()) "PASS" else "REJECT"}"
        }
        return result
    }

    private fun isOpposite(
        direction: PositionDirection,
        signal: StrategyAction,
    ): Boolean =
        (signal == StrategyAction.BUY && direction == PositionDirection.SHORT) ||
            (signal == StrategyAction.SELL && direction == PositionDirection.LONG)

    private fun commissionRate(multiplier: Double): BigDecimal = SimulatedExecution.COMMISSION_RATE.multiply(BigDecimal.valueOf(multiplier))

    private fun slippageRate(multiplier: Double): BigDecimal =
        SimulatedExecution.MARKET_SLIPPAGE_RATE.multiply(BigDecimal.valueOf(multiplier))

    /**
     * Учёт открытия позиции: комиссия входа списывается, номинал остаётся в кэше
     * (позиция учитывается как нереализованный PnL в [equityAt]).
     */
    private fun applyOpen(
        cash: BigDecimal,
        pos: PositionSim,
        commissionMultiplier: Double = 1.0,
    ): BigDecimal =
        cash.subtract(
            SimulatedExecution.commissionOn(pos.entryPrice, commissionRate(commissionMultiplier)),
        )

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
        ticker: String,
        signal: StrategyAction,
        price: BigDecimal,
        cash: BigDecimal,
        bar: Int,
        slPercent: Double,
        tpPercent: Double,
        commissionMultiplier: Double = 1.0,
        slippageMultiplier: Double = 1.0,
    ): PositionSim? {
        if (cash <= BigDecimal.ZERO) return null
        val lotSize = instrumentsConfig.find(ticker)?.lotSize ?: 1
        val capitalSlice = cash.multiply(BigDecimal.valueOf(backtestConfig.capitalSlice))
        val qty = capitalSlice.divide(price, 0, RoundingMode.DOWN).toInt()
        val lotQty = SimulatedExecution.lotRounded(qty, lotSize)
        if (lotQty <= 0) return null

        val direction = if (signal == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val fill = SimulatedExecution.marketFill(price, direction == PositionDirection.LONG, slippageRate(slippageMultiplier))
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
        commissionMultiplier: Double = 1.0,
        slippageMultiplier: Double = 1.0,
    ): BigDecimal {
        val fill = SimulatedExecution.marketFill(price, pos.direction == PositionDirection.SHORT, slippageRate(slippageMultiplier))
        val commissionEntry = SimulatedExecution.commissionOn(pos.entryPrice, commissionRate(commissionMultiplier))
        val commissionExit = SimulatedExecution.commissionOn(fill.price, commissionRate(commissionMultiplier))
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
     * Пустой результат прогона (недостаточно данных / нет сделок).
     */
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
