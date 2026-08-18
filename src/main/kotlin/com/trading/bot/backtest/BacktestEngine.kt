package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.toFuturesAtrStopPolicy
import com.trading.bot.domain.risk.Atr
import com.trading.bot.domain.risk.ExitRules
import com.trading.bot.domain.risk.FuturesStopResolver
import com.trading.bot.domain.risk.PositionSizer
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.BacktestResultEntity
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.BacktestResultRepository
import com.trading.bot.repository.CandleRepository
import com.trading.bot.service.HigherTfTrendFilter
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
 *   roadmap 13.11.6): признаки на момент бара, signalStrength=null; блокировки
 *   считаются в метрику `bt_ml_blocked_total{ticker}` (live-гейт не затрагивается)
 * - При `bt.mtf-filter-enabled=true` гейтит входы multi-timeframe фильтром
 *   ([HigherTfTrendFilter], roadmap v2.5): тренд ресемплированных в старший ТФ
 *   свечей на момент бара, point-in-time без lookahead; блокировки считаются
 *   в метрику `bt_mtf_blocked_total{ticker}` (live-гейт не затрагивается)
 * - Считает метрики: Sharpe, MaxDD, PF, win rate
 * - Сохраняет результат в `backtest_results` (roadmap v2.2, 13.7.3) и инкрементирует
 *   `bt_pass_total{result=PASS|REJECT}` для сравнения итераций стратегии.
 *
 * Лотность позиций берётся из [InstrumentsConfig] (как в live) — размер позиции
 * округляется вниз до целого лота, совпадая с реальным исполнением на бирже.
 *
 * Сайзинг — единый с live: для фьючерсных тикеров размер позиции считает
 * [PositionSizer] (тот же [com.trading.bot.application.risk.FuturesPositionSizer],
 * что и в production-пайплайне), SL/TP — по пунктам ([RiskConfig.defaultStopLossPoints]/
 * [RiskConfig.defaultTakeProfitPoints]), как в [com.trading.bot.application.OrderBuilder].
 * Для акций (и при отсутствии sizer) используется fallback `bt.capital-slice`,
 * ограниченный риск-капом на сделку ([RiskConfig.riskPerTradePercent] против убытка
 * на [RiskConfig.defaultStopLossPercent]) — аналог [com.trading.bot.application.decision.StockEntryProfile].
 * GO в бэктесте берётся из [InstrumentsConfig] (в non-live так же делает
 * [com.trading.bot.infrastructure.alor.AlorFuturesClient]).
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
    private val higherTfTrendFilter: HigherTfTrendFilter? = null,
    private val positionSizer: PositionSizer? = null,
    private val riskConfig: RiskConfig = RiskConfig(),
    private val futuresStopResolver: FuturesStopResolver = FuturesStopResolver(),
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
     * @param slPoints стоп-лосс фьючерса в пунктах (BT-004; null = ATR/дефолт)
     * @param tpPoints тейк-профит фьючерса в пунктах (BT-004; null = дефолт)
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
        slPoints: Int? = null,
        tpPoints: Int? = null,
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
                slPoints,
                tpPoints,
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
     * @param slPoints стоп-лосс фьючерса в пунктах (переопределяет ATR-политику/
     *   [RiskConfig.defaultStopLossPoints]); для акций игнорируется — настройка
     *   walk-forward BT-004, см. [BacktestValidator].
     * @param tpPoints тейк-профит фьючерса в пунктах (переопределяет
     *   [RiskConfig.defaultTakeProfitPoints]); для акций игнорируется.
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
        slPoints: Int? = null,
        tpPoints: Int? = null,
    ): BacktestResult {
        var cash = initialCapital
        val equityCurve = ArrayList<BigDecimal>()
        val tradeReturns = ArrayList<Double>()
        val tradeHoldBars = ArrayList<Int>()
        val commissionAccumulator = mutableListOf(BigDecimal.ZERO)
        val cycleId = "backtest-$ticker-${UUID.randomUUID()}"
        var mlBlockedCount = 0
        var mtfBlockedCount = 0

        var position: PositionSim? = null
        val sorted = candles.sortedBy { it.time }

        for (i in 1 until sorted.size) {
            val current = sorted[i]

            // Закрытие по SL/TP на внутрисвечном диапазоне текущей свечи
            val pos0 = position
            if (pos0 != null && pos0.stopLoss != null && pos0.takeProfit != null) {
                when (SimulatedExecution.hitStopOrTarget(current, pos0.stopLoss, pos0.takeProfit, pos0.direction)) {
                    SimulatedExecution.StopTpHit.STOP -> {
                        cash =
                            closePosition(
                                ticker,
                                pos0,
                                "STOP_LOSS",
                                pos0.stopLoss,
                                cash,
                                i,
                                tradeReturns,
                                tradeHoldBars,
                                commissionAccumulator,
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
                                i,
                                tradeReturns,
                                tradeHoldBars,
                                commissionAccumulator,
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
                equityCurve.add(equityAt(ticker, cash, position, current.closePrice))
                continue
            }

            val curPos = position
            val entering = curPos == null || isOpposite(curPos.direction, signal)
            if (entering && backtestConfig.mlFilterEnabled && mlEntryFilter != null) {
                // ML-фильтр входа (раздел 13.11.6): признаки на момент бара, signalStrength
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
                                i,
                                tradeReturns,
                                tradeHoldBars,
                                commissionAccumulator,
                                commissionMultiplier,
                                slippageMultiplier,
                            )
                        position = null
                    }
                    equityCurve.add(equityAt(ticker, cash, position, current.closePrice))
                    continue
                }
            }

            if (entering && backtestConfig.mtfFilterEnabled && higherTfTrendFilter != null) {
                // Multi-timeframe фильтр тренда (раздел 13.9.1): старший ТФ строится
                // по завершённым к моменту бара свечам (без lookahead), вход против
                // тренда старшего ТФ блокируется.
                val blockReason =
                    higherTfTrendFilter.shouldBlock(
                        ticker,
                        signal,
                        sorted.subList(0, i),
                        current.time,
                        requireEnabled = false,
                    )
                if (blockReason != null) {
                    mtfBlockedCount++
                    logger.debug { "Backtest $ticker: higher-TF filter blocked entry at ${current.time}: $blockReason" }
                    meterRegistry?.counter("bt_mtf_blocked_total", "ticker", ticker)?.increment()
                    if (curPos != null) {
                        // Сигнал инверсии, но встречный вход отклонён фильтром → закрыть текущую позицию
                        cash =
                            closePosition(
                                ticker,
                                curPos,
                                "MTF_FILTER_REVERSAL",
                                current.openPrice,
                                cash,
                                i,
                                tradeReturns,
                                tradeHoldBars,
                                commissionAccumulator,
                                commissionMultiplier,
                                slippageMultiplier,
                            )
                        position = null
                    }
                    equityCurve.add(equityAt(ticker, cash, position, current.closePrice))
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
                            i,
                            tradeReturns,
                            tradeHoldBars,
                            commissionAccumulator,
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
                            slippageMultiplier,
                            sorted.subList(0, i),
                            slPoints,
                            tpPoints,
                        )
                    if (position != null) {
                        cash = applyOpen(cash, position, ticker, commissionMultiplier)
                    }
                }
                equityCurve.add(equityAt(ticker, cash, position, current.closePrice))
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
                    slippageMultiplier,
                    sorted.subList(0, i),
                    slPoints,
                    tpPoints,
                )
            if (position != null) {
                cash = applyOpen(cash, position, ticker, commissionMultiplier)
            }
            equityCurve.add(equityAt(ticker, cash, position, current.closePrice))
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
                    sorted.lastIndex,
                    tradeReturns,
                    tradeHoldBars,
                    commissionAccumulator,
                    commissionMultiplier,
                    slippageMultiplier,
                )
        }
        equityCurve.add(cash)

        val result = BacktestMetrics.compute(ticker, equityCurve, tradeReturns, tradeHoldBars, commissionAccumulator[0])
        logger.info {
            "Backtest $ticker: return=${String.format("%.2f%%", result.totalReturn * 100)}, " +
                "Sharpe=${String.format("%.2f", result.sharpeRatio)}, Sortino=${String.format("%.2f", result.sortinoRatio)}, " +
                "MDD=${String.format("%.2f%%", result.maxDrawdown * 100)}, PF=${String.format("%.2f", result.profitFactor)}, " +
                "win=${String.format("%.2f%%", result.winRate * 100)}, expectancy=${String.format("%.2f", result.expectancy)}, " +
                "W/L=${String.format("%.2f", result.winLossRatio)}, trades=${result.totalTrades}, " +
                "mlBlocked=$mlBlockedCount, mtfBlocked=$mtfBlockedCount " +
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

    private fun slippageTicks(multiplier: Double): Int = (SimulatedExecution.FUTURES_SLIPPAGE_TICKS * multiplier).toInt().coerceAtLeast(1)

    /**
     * Комиссия за сделку: per-instrument фиксированная (commissionRub × лоты)
     * или универсальный процент от оборота.
     */
    private fun computeCommission(
        ticker: String,
        price: BigDecimal,
        quantity: Int,
        commissionMultiplier: Double = 1.0,
    ): BigDecimal {
        val spec = instrumentsConfig.find(ticker)
        val fixedPerLot = spec?.commissionRub
        if (fixedPerLot != null && fixedPerLot > BigDecimal.ZERO) {
            val lotSize = spec.lotSize.coerceAtLeast(1)
            val lots = quantity / lotSize
            if (lots < 1) {
                return SimulatedExecution.commissionOn(price, quantity, commissionRate(commissionMultiplier))
            }
            return SimulatedExecution.commissionFixed(
                fixedPerLot.multiply(BigDecimal.valueOf(commissionMultiplier.toLong())),
                lots,
            )
        }
        return SimulatedExecution.commissionOn(price, quantity, commissionRate(commissionMultiplier))
    }

    /**
     * Цена исполнения market-ордера с проскальзыванием:
     * - фьючерсы — в ТИКАХ (пунктах), как при исполнении на бирже; процентная
     *   ставка от цены фьючерса непропорционально велика (0.1% Si ≈ 92 пункта >
     *   стоп в [com.trading.bot.config.RiskConfig.defaultStopLossPoints] пунктов);
     * - акции — процентная ставка (0.1%), как исторически в бэктесте.
     */
    private fun executionFill(
        instrument: InstrumentsConfig.InstrumentSpec?,
        ticker: String,
        reference: BigDecimal,
        isBuy: Boolean,
        slippageMultiplier: Double,
    ): SimulatedExecution.Fill =
        if (instrument != null && instrumentsConfig.isFutures(ticker)) {
            SimulatedExecution.tickFill(reference, isBuy, slippageTicks(slippageMultiplier), instrument.priceStep)
        } else {
            SimulatedExecution.marketFill(reference, isBuy, slippageRate(slippageMultiplier))
        }

    /**
     * Учёт открытия позиции: комиссия входа списывается, номинал остаётся в кэше
     * (позиция учитывается как нереализованный PnL в [equityAt]).
     */
    private fun applyOpen(
        cash: BigDecimal,
        pos: PositionSim,
        ticker: String,
        commissionMultiplier: Double = 1.0,
    ): BigDecimal =
        cash.subtract(
            computeCommission(ticker, pos.entryPrice, pos.quantity, commissionMultiplier),
        )

    /** Оценка текущего капитала: cash + нереализованный PnL позиции (mark-to-market). */
    private fun equityAt(
        ticker: String,
        cash: BigDecimal,
        position: PositionSim?,
        marketPrice: BigDecimal,
    ): BigDecimal {
        if (position == null) return cash
        val instrument = instrumentsConfig.find(ticker)
        val lotSize = instrument?.lotSize?.toLong() ?: 1L
        val qty = position.quantity.toBigDecimal()
        val notionalMultiplier = qty.multiply(BigDecimal(lotSize))
        val unrealized =
            when (position.direction) {
                PositionDirection.LONG -> marketPrice.subtract(position.entryPrice)
                PositionDirection.SHORT -> position.entryPrice.subtract(marketPrice)
            }.multiply(notionalMultiplier)
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
        slippageMultiplier: Double = 1.0,
        history: List<Candle>,
        slPoints: Int? = null,
        tpPoints: Int? = null,
    ): PositionSim? {
        if (cash <= BigDecimal.ZERO) return null
        val instrument = instrumentsConfig.find(ticker)
        val lotSize = instrument?.lotSize ?: 1
        val direction = if (signal == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val stopPoints = resolveAtrStopPoints(history, ticker, instrument)
        val qty = sizeQuantity(ticker, instrument, direction, price, cash, slPoints ?: stopPoints)
        val lotQty = SimulatedExecution.lotRounded(qty, lotSize)
        if (lotQty <= 0) return null

        val fill = executionFill(instrument, ticker, price, direction == PositionDirection.LONG, slippageMultiplier)
        return PositionSim(
            direction = direction,
            quantity = lotQty,
            entryPrice = fill.price,
            stopLoss = stopPrice(ticker, instrument, direction, fill.price, slPercent, stopPoints, slPoints),
            takeProfit = takePrice(ticker, instrument, direction, fill.price, tpPercent, tpPoints),
            entryBars = bar,
        )
    }

    /**
     * Размер позиции — единый источник истины с live:
     * - фьючерс + [PositionSizer] → сайзинг через production-алгоритм
     *   (риск на сделку / маржинальный бюджет / лимит контрактов). GO берётся из
     *   [InstrumentsConfig] — то же значение, что использует live в non-live режиме.
     * - акции / нет sizer → fallback: бюджет `bt.capital-slice` от капитала,
     *   ограниченный сверху риск-капом на сделку (как
     *   [com.trading.bot.application.decision.StockEntryProfile]): убыток
     *   при срабатывании стопа (defaultStopLossPercent%) не может превысить
     *   riskPerTradePercent% портфеля.
     */
    private fun sizeQuantity(
        ticker: String,
        instrument: InstrumentsConfig.InstrumentSpec?,
        direction: PositionDirection,
        price: BigDecimal,
        cash: BigDecimal,
        stopPoints: Int?,
    ): Int {
        val futuresSizer = positionSizer
        if (instrument != null && instrumentsConfig.isFutures(ticker) && futuresSizer != null) {
            val size =
                futuresSizer.calculateContracts(
                    ticker = ticker,
                    portfolioMoney = cash,
                    stopLossPoints = stopPoints ?: riskConfig.defaultStopLossPoints,
                    currentGo = instrument.go,
                    entryPrice = price,
                    direction = direction,
                )
            if (size.quantity <= 0) {
                logger.debug { "Backtest $ticker: sizer rejected entry (${size.reason})" }
            }
            return size.quantity
        }
        val sliceQty =
            cash
                .multiply(BigDecimal.valueOf(backtestConfig.capitalSlice))
                .divide(price, 0, RoundingMode.DOWN)
                .toInt()
        // Риск-кап на сделку (аналог StockEntryProfile.sizePosition): потеря при
        // стопе не должна превысить riskPerTradePercent% портфеля.
        // Учитываем per-instrument SL% и commissionRub (как в live StockEntryProfile).
        val effectiveSl = instrument?.effectiveSlPercent(riskConfig.defaultStopLossPercent)
            ?: riskConfig.defaultStopLossPercent
        val commissionPerLot = instrument?.commissionRub ?: BigDecimal.ZERO
        val lotSize = instrument?.lotSize?.coerceAtLeast(1) ?: 1
        val riskAmount =
            cash
                .multiply(BigDecimal(riskConfig.riskPerTradePercent.toString()))
                .divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val lossPerShare =
            price
                .multiply(effectiveSl)
                .divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
                .add(commissionPerLot.divide(BigDecimal(lotSize), 6, RoundingMode.HALF_UP))
        val riskCapQty =
            if (lossPerShare > BigDecimal.ZERO) {
                riskAmount.divide(lossPerShare, 4, RoundingMode.DOWN).toInt()
            } else {
                Int.MAX_VALUE
            }
        return minOf(sliceQty, riskCapQty)
    }

    /**
     * Дистанция стопа фьючерса в пунктах — политика делегирована единому
     * [FuturesStopResolver] (тот же, что в live-пайплайне
     * [com.trading.bot.application.decision.FuturesEntryProfile]):
     * ATR по завершённым к моменту входа свечам (без lookahead) × multiplier.
     * null — акции или недостаток данных (тогда фиксированный дефолт).
     */
    private fun resolveAtrStopPoints(
        history: List<Candle>,
        ticker: String,
        instrument: InstrumentsConfig.InstrumentSpec?,
    ): Int? {
        if (instrument == null || !instrumentsConfig.isFutures(ticker)) return null
        val atr = Atr.calculate(history, riskConfig.futuresAtrStopPeriod)
        return futuresStopResolver.resolve(atr, instrument.priceStep, riskConfig.toFuturesAtrStopPolicy())
    }

    /**
     * Стоп-лосс: для фьючерсов — отступ в пунктах от цены входа ([stopPoints]
     * при ATR-стопе, [slPoints] при явной настройке walk-forward (BT-004), иначе
     * [RiskConfig.defaultStopLossPoints]; как live
     * [com.trading.bot.application.OrderBuilder.buildFuturesOrderParams]),
     * для акций — процент от цены входа через [ExitRules.calcSL] (тот же код,
     * что в live [com.trading.bot.application.OrderBuilder.buildSpotOrderParams]).
     * Per-instrument SL% (InstrumentsConfig.InstrumentSpec.slPercent) имеет приоритет над глобальным.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun stopPrice(
        ticker: String,
        instrument: InstrumentsConfig.InstrumentSpec?,
        direction: PositionDirection,
        fillPrice: BigDecimal,
        slPercent: Double,
        stopPoints: Int?,
        slPoints: Int? = null,
    ): BigDecimal {
        if (instrument != null && instrumentsConfig.isFutures(ticker)) {
            val points = slPoints ?: stopPoints ?: riskConfig.defaultStopLossPoints
            val offset = BigDecimal(points).multiply(instrument.priceStep)
            return when (direction) {
                PositionDirection.LONG -> fillPrice.subtract(offset)
                PositionDirection.SHORT -> fillPrice.add(offset)
            }.setScale(2, RoundingMode.HALF_UP)
        }
        val effectiveSl = instrument?.effectiveSlPercent(riskConfig.defaultStopLossPercent)
            ?: riskConfig.defaultStopLossPercent
        return ExitRules.calcSL(fillPrice, direction, effectiveSl, instrument?.priceStep ?: BigDecimal("0.01"))
    }

    /** Тейк-профит: для фьючерсов — пункты, для акций — процент (см. [stopPrice]).
     * Per-instrument TP% (InstrumentsConfig.InstrumentSpec.tpPercent) имеет приоритет над глобальным. */
    @Suppress("UNUSED_PARAMETER")
    private fun takePrice(
        ticker: String,
        instrument: InstrumentsConfig.InstrumentSpec?,
        direction: PositionDirection,
        fillPrice: BigDecimal,
        tpPercent: Double,
        tpPoints: Int? = null,
    ): BigDecimal {
        if (instrument != null && instrumentsConfig.isFutures(ticker)) {
            val offset = BigDecimal(tpPoints ?: riskConfig.defaultTakeProfitPoints).multiply(instrument.priceStep)
            return when (direction) {
                PositionDirection.LONG -> fillPrice.add(offset)
                PositionDirection.SHORT -> fillPrice.subtract(offset)
            }.setScale(2, RoundingMode.HALF_UP)
        }
        val effectiveTp = instrument?.effectiveTpPercent(riskConfig.defaultTakeProfitPercent)
            ?: riskConfig.defaultTakeProfitPercent
        return ExitRules.calcTP(fillPrice, direction, effectiveTp, instrument?.priceStep ?: BigDecimal("0.01"))
    }

    /**
     * Закрытие позиции: cash += exit*qty - commission_exit.
     * PnL сделки (с обеими комиссиями) записывается в tradeReturns.
     *
     * Точка кривой капитала НЕ добавляется здесь: кривая строится по одной точке
     * на свечу (mark-to-market в цикле [simulate]), иначе закрытие по SL/TP внутри
     * бара давало бы дубликат (нулевую доходность), искажающий Sharpe/Sortino.
     */
    private fun closePosition(
        ticker: String,
        pos: PositionSim,
        reason: String,
        price: BigDecimal,
        cash: BigDecimal,
        closeBar: Int,
        tradeReturns: MutableList<Double>,
        tradeHoldBars: MutableList<Int>,
        commissionAccumulator: MutableList<BigDecimal>,
        commissionMultiplier: Double = 1.0,
        slippageMultiplier: Double = 1.0,
    ): BigDecimal {
        val instrument = instrumentsConfig.find(ticker)
        val fill = executionFill(instrument, ticker, price, pos.direction == PositionDirection.SHORT, slippageMultiplier)
        val commissionEntry = computeCommission(ticker, pos.entryPrice, pos.quantity, commissionMultiplier)
        val commissionExit = computeCommission(ticker, fill.price, pos.quantity, commissionMultiplier)
        commissionAccumulator[0] = commissionAccumulator[0].add(commissionEntry).add(commissionExit)
        val lotSize = instrument?.lotSize?.toLong() ?: 1L
        val gross =
            when (pos.direction) {
                PositionDirection.LONG -> fill.price.subtract(pos.entryPrice)
                PositionDirection.SHORT -> pos.entryPrice.subtract(fill.price)
            }.multiply(BigDecimal(pos.quantity * lotSize))
        val pnl = gross.subtract(commissionEntry).subtract(commissionExit)

        tradeReturns.add(pnl.toDouble())
        tradeHoldBars.add((closeBar - pos.entryBars).coerceAtLeast(0))
        // Комиссия входа уже списана при открытии; здесь добавляется gross за вычетом комиссии выхода
        val newCash = cash.add(gross).subtract(commissionExit)
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
