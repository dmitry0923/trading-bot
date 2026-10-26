package com.trading.bot.backtest

import com.trading.bot.application.FundingCosts
import com.trading.bot.application.MoexHolidayCalendar
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
import com.trading.bot.repository.FundingHistoryRepository
import com.trading.bot.service.HigherTfTrendFilter
import com.trading.bot.service.MlEntryFilter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.NavigableMap
import java.util.TreeMap
import java.util.UUID
import kotlin.math.sqrt

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
    private val riskSimulator: BacktestRiskSimulator? = null,
    private val fundingHistoryRepository: FundingHistoryRepository? = null,
    private val moexHolidayCalendar: MoexHolidayCalendar? = null,
) {
    private val logger = KotlinLogging.logger {}

    data class PositionSim(
        val direction: PositionDirection,
        val quantity: Int,
        val entryPrice: BigDecimal,
        val stopLoss: BigDecimal?,
        val takeProfit: BigDecimal?,
        val entryBars: Int,
        val liquidationPrice: BigDecimal? = null,
        val entryTime: LocalDateTime? = null,
        val hourOfDay: Int? = null,
        val dayOfWeek: String? = null,
        val atrPercent: Double? = null,
        val volatilityPercent: Double? = null,
    )

    /** Результат определения размера позиции: кол-во и (для фьючерсов) liq-цена. */
    data class SizedPosition(
        val quantity: Int,
        val liquidationPrice: BigDecimal? = null,
    )

    /**
     * Прогон бэктеста по тикеру за N дней.
     *
     * @param ticker тикер инструмента
     * @param passiveFillEnabled research (PLAN-001 §5.1, a2): пассивное (maker)
     *   исполнение входов/рыночных выходов; null → `bt.execution-passive-fill-enabled`.
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
        leverage: Double = 1.0,
        capitalSlice: Double = backtestConfig.capitalSlice,
        riskPerTradePercent: Double? = null,
        futuresMaxContractsPerPosition: Int? = null,
        signalGeneratorOverride: BacktestSignalGenerator? = null,
        fundingVetoEnabled: Boolean? = null,
        fundingVetoLongThresholdRub: Double? = null,
        fundingVetoShortThresholdRub: Double? = null,
        fundingVetoBlockOnUnknown: Boolean? = null,
        maxHoldBars: Int? = null,
        passiveFillEnabled: Boolean? = null,
        /**
         * Готовые свечи вместо чтения из БД (research-эндпоинты с ресемплингом старшего ТФ):
         * иначе `/backtest?timeframe=HOUR_1` читал нативный ТФ и молча терял конфигурацию
         * (скан-6 P1).
         */
        candlesOverride: List<Candle>? = null,
    ): BacktestResult {
        val from = LocalDateTime.now().minusDays(days.toLong())
        val candles = candlesOverride ?: candleRepo.findByTickerAndTimeframeAndTimeBetween(ticker, timeframe, from, LocalDateTime.now())
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
                leverage,
                capitalSlice,
                riskPerTradePercent,
                futuresMaxContractsPerPosition,
                signalGeneratorOverride,
                fundingVetoEnabled,
                fundingVetoLongThresholdRub,
                fundingVetoShortThresholdRub,
                fundingVetoBlockOnUnknown,
                maxHoldBars,
                passiveFillEnabled,
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
                // Артефакт обязан нести версию движка и research-флаги: иначе прогоны
                // с разными конфигами неотличимы (скан-2 P2).
                "engineVersion" to BACKTEST_ENGINE_VERSION,
                "fundingVetoEnabled" to backtestConfig.fundingVetoEnabled,
                "fundingDirectionAware" to backtestConfig.fundingDirectionAware,
                "maxHoldBars" to backtestConfig.maxHoldBars,
                "capitalSlice" to backtestConfig.capitalSlice,
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
        leverage: Double = 1.0,
        capitalSlice: Double? = null,
        riskPerTradePercent: Double? = null,
        futuresMaxContractsPerPosition: Int? = null,
        signalGeneratorOverride: BacktestSignalGenerator? = null,
        /** research funding-veto override: null → bt.* (калибровка порогов без перезапуска). */
        fundingVetoEnabled: Boolean? = null,
        fundingVetoLongThresholdRub: Double? = null,
        fundingVetoShortThresholdRub: Double? = null,
        fundingVetoBlockOnUnknown: Boolean? = null,
        maxHoldBars: Int? = null,
        passiveFillEnabled: Boolean? = null,
    ): BacktestResult {
        val effectiveCapitalSlice = capitalSlice ?: backtestConfig.capitalSlice
        val effectiveSignalGenerator = signalGeneratorOverride ?: signalGenerator
        // Research a2: пассивное (maker) исполнение входов и рыночных выходов.
        // null → bt.execution-passive-fill-enabled; защитные SL/TP/ликвидация
        // остаются taker (передаётся passiveEligible=false).
        val effectivePassiveFill = passiveFillEnabled ?: backtestConfig.executionPassiveFillEnabled
        var cash = initialCapital
        val equityCurve = ArrayList<BigDecimal>()
        val equityTimestamps = ArrayList<LocalDateTime>()
        val tradeReturns = ArrayList<Double>()
        val tradeHoldBars = ArrayList<Int>()
        val tradeLog = ArrayList<BacktestTradeRecord>()
        val commissionAccumulator = mutableListOf(BigDecimal.ZERO)
        val cycleId = "backtest-$ticker-${UUID.randomUUID()}"
        var mlBlockedCount = 0
        var mtfBlockedCount = 0
        var riskBlockedCount = 0
        var fundingVetoBlockedCount = 0

        // Live risk gates: initialize in-memory state machine
        val useRiskGates = backtestConfig.liveRiskGates && riskSimulator != null
        if (useRiskGates) {
            riskSimulator!!.initialize(initialCapital)
            riskSimulator.cycleId = cycleId
        }

        var position: PositionSim? = null
        val sorted = candles.sortedBy { it.time }

        // Historical per-clearing funding (research P1, funding_history): если для
        // тикера в БД есть ряд SWAPRATE, P&L использует ФАКТИЧЕСКИЕ значения по
        // датам клирингов вместо конфигурационного fundingRubPerContractPerDay.
        val fundingHistory: Map<LocalDate, BigDecimal> =
            try {
                val repo = fundingHistoryRepository
                when {
                    repo == null -> {
                        emptyMap()
                    }

                    // Пустой срез (фолд без свечей) раньше ронял sorted.first() с
                    // NoSuchElementException, который ловился здесь и логировался как
                    // "funding history unavailable" - то есть реальная причина (нет данных)
                    // маскировалась под funding. Теперь причина называется прямо.
                    sorted.isEmpty() -> {
                        logger.warn {
                            "Backtest $ticker: empty candle slice (${candles.size} candles) - " +
                                "прогон без сделок, результат не информативен"
                        }
                        emptyMap()
                    }

                    else -> {
                        repo.findValuesBetween(
                            ticker,
                            sorted.first().time.toLocalDate(),
                            sorted.last().time.toLocalDate(),
                        )
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Backtest $ticker: funding history unavailable, fallback to configured rate" }
                emptyMap()
            }

        // Research P4 (docs/27): расписание цели funding-window строится по последнему
        // клирингу СТРОГО раньше даты бара (lowerEntry — без lookahead). null → фича off.
        val fundingWindowDates: NavigableMap<LocalDate, BigDecimal>? =
            if (backtestConfig.fundingWindowEnabled) TreeMap(fundingHistory) else null

        // Funding-veto (research): ставка клиринга 18:45 известна только по его
        // завершении, поэтому решение в день D обязано использовать ПОСЛЕДНИЙ клиринг
        // строго раньше D — то же point-in-time правило, что у funding-window.
        // Раньше брался same-day (`fundingHistory[дата бара]`) → lookahead на 1 день.
        val fundingVetoDates: NavigableMap<LocalDate, BigDecimal>? =
            if (fundingVetoEnabled ?: backtestConfig.fundingVetoEnabled) TreeMap(fundingHistory) else null

        fun recordEquity(
            price: BigDecimal,
            time: LocalDateTime,
        ) {
            equityCurve.add(equityAt(ticker, cash, position, price))
            equityTimestamps.add(time)
        }

        // Защитные выходы (ликвидация → SL/TP) по внутрисвечному диапазону бара.
        // Вызывается в начале бара для уже открытой позиции И сразу после входа:
        // позиция, открытая по open бара i, обязана проверяться по high/low ЭТОГО ЖЕ
        // бара. Раньше проверка начиналась только со следующего бара — стоп, пробитый
        // в баре входа, «не срабатывал» (систематический оптимизм, docs/28 §2 №10).
        // Проскальзывание защитных выходов теперь такое же, как у рыночных (раньше
        // при realisticExecution=true его не было вовсе — docs/28 §2 №18).
        fun handleProtectiveExits(
            barIndex: Int,
            bar: Candle,
        ) {
            val liqPos = position
            if (backtestConfig.futuresLiquidationSimulation && liqPos != null && liqPos.liquidationPrice != null) {
                val liq = liqPos.liquidationPrice!!
                val liquidated =
                    when (liqPos.direction) {
                        PositionDirection.LONG -> bar.lowPrice.compareTo(liq) <= 0
                        PositionDirection.SHORT -> bar.highPrice.compareTo(liq) >= 0
                    }
                if (liquidated) {
                    cash =
                        closePosition(
                            ticker,
                            liqPos,
                            "LIQUIDATION",
                            liq,
                            cash,
                            barIndex,
                            tradeReturns,
                            tradeHoldBars,
                            commissionAccumulator,
                            commissionMultiplier,
                            slippageMultiplier,
                            bar,
                            passiveEligible = false,
                            fundingHistory = fundingHistory,
                            tradeLog = tradeLog,
                        )
                    recordRiskSimClose(ticker, liqPos, "LIQUIDATION", liq, barIndex, sorted, cash)
                    position = null
                }
            }

            val pos0 = position ?: return
            val stop = pos0.stopLoss ?: return
            val target = pos0.takeProfit ?: return
            when (SimulatedExecution.hitStopOrTarget(bar, stop, target, pos0.direction)) {
                SimulatedExecution.StopTpHit.STOP -> {
                    cash =
                        closePosition(
                            ticker,
                            pos0,
                            "STOP_LOSS",
                            stop,
                            cash,
                            barIndex,
                            tradeReturns,
                            tradeHoldBars,
                            commissionAccumulator,
                            commissionMultiplier,
                            slippageMultiplier,
                            bar,
                            passiveEligible = false,
                            fundingHistory = fundingHistory,
                            tradeLog = tradeLog,
                        )
                    recordRiskSimClose(ticker, pos0, "STOP_LOSS", stop, barIndex, sorted, cash)
                    position = null
                }

                SimulatedExecution.StopTpHit.TARGET -> {
                    cash =
                        closePosition(
                            ticker,
                            pos0,
                            "TAKE_PROFIT",
                            target,
                            cash,
                            barIndex,
                            tradeReturns,
                            tradeHoldBars,
                            commissionAccumulator,
                            commissionMultiplier,
                            slippageMultiplier,
                            bar,
                            passiveEligible = false,
                            fundingHistory = fundingHistory,
                            tradeLog = tradeLog,
                        )
                    recordRiskSimClose(ticker, pos0, "TAKE_PROFIT", target, barIndex, sorted, cash)
                    position = null
                }

                null -> {}
            }
        }

        // Кривая equity начинается со стартового капитала на баре 0, иначе доходность
        // считается не от начального капитала и первая точка теряется (docs/28 §2 №11).
        if (sorted.isNotEmpty()) recordEquity(sorted[0].closePrice, sorted[0].time)

        for (i in 1 until sorted.size) {
            val current = sorted[i]
            // Позиция, удерживаемая с прошлых баров: сначала защитные выходы.
            val hadPositionAtBarStart = position != null
            handleProtectiveExits(i, current)
            if (hadPositionAtBarStart && position == null) {
                // Защитный выход случился внутри этого бара: вход по open этого бара —
                // это цена ДО срабатывания стопа (lookahead) + повторная проверка
                // high/low бара задним числом. Вход в таком баре запрещён (скан-2 P1).
                recordEquity(current.closePrice, current.time)
                continue
            }

            // Max-hold (research, 2026-09-21): принудительный выход по времени
            // удержания в барах. Позиция без SL/TP-срабатывания закрывается по цене
            // закрытия текущей свечи (MKT), если она держится >= maxHoldBars баров.
            // Паттерн funding-veto: null → bt.*, query-оверрайд на /backtest /validate.
            val maxHoldPosition = position
            if (maxHoldBars != null && maxHoldBars > 0 && maxHoldPosition != null &&
                (i - maxHoldPosition.entryBars) >= maxHoldBars
            ) {
                cash =
                    closePosition(
                        ticker,
                        maxHoldPosition,
                        "MAX_HOLD",
                        current.closePrice,
                        cash,
                        i,
                        tradeReturns,
                        tradeHoldBars,
                        commissionAccumulator,
                        commissionMultiplier,
                        slippageMultiplier,
                        current,
                        fundingHistory = fundingHistory,
                        tradeLog = tradeLog,
                        passiveFillEnabled = effectivePassiveFill,
                    )
                recordRiskSimClose(ticker, maxHoldPosition, "MAX_HOLD", current.closePrice, i, sorted, cash)
                position = null
                // Выход по max-hold: вход в этом же баре запрещён — цена open была
                // бы «до» выхода (скан-3 P2).
                recordEquity(current.closePrice, current.time)
                continue
            }

            // Research P4 (docs/27-funding-window-capture): funding-window capture.
            // Сторона выбирается ПО СТАВКЕ (последний клиринг строго раньше даты бара),
            // а не по цене; позиция удерживается через клиринги, при слабой ставке — FLAT.
            // Детерминированный генератор сигналов и входные фильтры при этом обходятся.
            if (fundingWindowDates != null) {
                val (windowTarget, windowUnknown) =
                    fundingWindowTarget(
                        ticker,
                        current.time,
                        fundingWindowDates,
                        backtestConfig.fundingWindowLongThresholdRub,
                        backtestConfig.fundingWindowShortThresholdRub,
                    )
                if (windowUnknown) {
                    meterRegistry?.counter("bt_funding_window_unknown_total", "ticker", ticker)?.increment()
                }
                // fail-closed: неизвестная ставка → FLAT; при block-on-unknown=false — позицию не трогаем.
                if (!windowUnknown || backtestConfig.fundingWindowBlockOnUnknown) {
                    val windowPosition = position
                    when {
                        windowTarget == null && windowPosition != null -> {
                            cash =
                                closePosition(
                                    ticker,
                                    windowPosition,
                                    "FUNDING_WINDOW_EXIT",
                                    current.openPrice,
                                    cash,
                                    i,
                                    tradeReturns,
                                    tradeHoldBars,
                                    commissionAccumulator,
                                    commissionMultiplier,
                                    slippageMultiplier,
                                    current,
                                    fundingHistory = fundingHistory,
                                    tradeLog = tradeLog,
                                    passiveFillEnabled = effectivePassiveFill,
                                )
                            recordRiskSimClose(ticker, windowPosition, "FUNDING_WINDOW_EXIT", current.openPrice, i, sorted, cash)
                            position = null
                            meterRegistry?.counter("bt_funding_window_exit_total", "ticker", ticker)?.increment()
                        }

                        windowTarget != null && windowPosition?.direction != windowTarget -> {
                            if (windowPosition != null) {
                                cash =
                                    closePosition(
                                        ticker,
                                        windowPosition,
                                        "FUNDING_WINDOW_FLIP",
                                        current.openPrice,
                                        cash,
                                        i,
                                        tradeReturns,
                                        tradeHoldBars,
                                        commissionAccumulator,
                                        commissionMultiplier,
                                        slippageMultiplier,
                                        current,
                                        fundingHistory = fundingHistory,
                                        tradeLog = tradeLog,
                                        passiveFillEnabled = effectivePassiveFill,
                                    )
                                recordRiskSimClose(ticker, windowPosition, "FUNDING_WINDOW_FLIP", current.openPrice, i, sorted, cash)
                                position = null
                            }
                            val windowAction =
                                if (windowTarget == PositionDirection.LONG) StrategyAction.BUY else StrategyAction.SELL
                            val windowEntry =
                                openPosition(
                                    ticker,
                                    windowAction,
                                    current.openPrice,
                                    cash,
                                    i,
                                    slPercent,
                                    tpPercent,
                                    slippageMultiplier,
                                    sorted.subList(0, i),
                                    slPoints,
                                    tpPoints,
                                    current,
                                    leverage,
                                    effectiveCapitalSlice,
                                    riskPerTradePercent,
                                    futuresMaxContractsPerPosition,
                                    passiveFillEnabled = effectivePassiveFill,
                                )
                            if (windowEntry != null) {
                                position = windowEntry
                                cash = applyOpen(cash, windowEntry, ticker, commissionMultiplier)
                                recordRiskSimOpen(ticker, windowEntry, sorted)
                                meterRegistry?.counter("bt_funding_window_entry_total", "ticker", ticker)?.increment()
                                // Вход по open этого бара → проверяем high/low этого же бара.
                                handleProtectiveExits(i, current)
                            }
                        }
                    }
                }
                recordEquity(current.closePrice, current.time)
                continue
            }

            val signal = effectiveSignalGenerator.signal(ticker, sorted, i - 1, minBarsForSignal, cycleId)
            if (signal == StrategyAction.HOLD || signal == StrategyAction.CLOSE) {
                // Удержание: фиксируем equity по текущей цене закрытия
                recordEquity(current.closePrice, current.time)
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
                                current,
                                fundingHistory = fundingHistory,
                                tradeLog = tradeLog,
                                passiveFillEnabled = effectivePassiveFill,
                            )
                        recordRiskSimClose(ticker, curPos, "ML_FILTER_REVERSAL", current.openPrice, i, sorted, cash)
                        position = null
                    }
                    recordEquity(current.closePrice, current.time)
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
                                current,
                                fundingHistory = fundingHistory,
                                tradeLog = tradeLog,
                                passiveFillEnabled = effectivePassiveFill,
                            )
                        recordRiskSimClose(ticker, curPos, "MTF_FILTER_REVERSAL", current.openPrice, i, sorted, cash)
                        position = null
                    }
                    recordEquity(current.closePrice, current.time)
                    continue
                }
            }

            // Funding-veto research-фильтр входа (зеркало live FundingVetoGate):
            // вход гейтится фактическим SWAPRATE на дату бара. При отсутствии ряда
            // и block-on-unknown=true — fail-closed; LONG блокируется при funding >
            // long-порога, SHORT — при funding < −short-порога. Параметры из query
            // (fundingVeto* override) приоритетнее bt.* — калибровка порогов без
            // перезапуска приложения.
            val vetoEnabled = fundingVetoEnabled ?: backtestConfig.fundingVetoEnabled
            val vetoLongThreshold = fundingVetoLongThresholdRub ?: backtestConfig.fundingVetoLongThresholdRub
            val vetoShortThreshold = fundingVetoShortThresholdRub ?: backtestConfig.fundingVetoShortThresholdRub
            val vetoBlockUnknown = fundingVetoBlockOnUnknown ?: backtestConfig.fundingVetoBlockOnUnknown
            if (entering && vetoEnabled) {
                val direction = if (signal == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
                // Последний клиринг строго раньше даты бара (см. fundingVetoDates выше).
                val fundingRub = fundingVetoDates?.lowerEntry(current.time.toLocalDate())?.value
                val vetoBlocked =
                    when {
                        fundingRub == null && vetoBlockUnknown -> true

                        direction == PositionDirection.LONG &&
                            fundingRub != null &&
                            fundingRub.compareTo(BigDecimal(vetoLongThreshold)) > 0 -> true

                        direction == PositionDirection.SHORT &&
                            fundingRub != null &&
                            fundingRub.compareTo(BigDecimal(vetoShortThreshold).negate()) < 0 -> true

                        else -> false
                    }
                if (vetoBlocked) {
                    fundingVetoBlockedCount++
                    logger.debug { "Backtest $ticker: funding-veto blocked entry at ${current.time}: funding=$fundingRub" }
                    meterRegistry?.counter("bt_funding_veto_blocked_total", "ticker", ticker)?.increment()
                    if (curPos != null) {
                        // Сигнал инверсии, но встречный вход отклонён фильтром → закрыть текущую позицию
                        cash =
                            closePosition(
                                ticker,
                                curPos,
                                "FUNDING_VETO_REVERSAL",
                                current.openPrice,
                                cash,
                                i,
                                tradeReturns,
                                tradeHoldBars,
                                commissionAccumulator,
                                commissionMultiplier,
                                slippageMultiplier,
                                current,
                                fundingHistory = fundingHistory,
                                tradeLog = tradeLog,
                                passiveFillEnabled = effectivePassiveFill,
                            )
                        recordRiskSimClose(ticker, curPos, "FUNDING_VETO_REVERSAL", current.openPrice, i, sorted, cash)
                        position = null
                    }
                    recordEquity(current.closePrice, current.time)
                    continue
                }
            }

            // Live risk gates: check full chain before entry (DecisionEngine parity)
            var riskBlocked = false
            if (useRiskGates && entering) {
                val gateResult =
                    riskSimulator!!.checkEntry(
                        ticker = ticker,
                        signal = signal,
                        entryPrice = current.openPrice,
                        cash = cash,
                        candle = current,
                        history = sorted.subList(0, i),
                        currentTime = current.time,
                        fundingHistory = fundingHistory,
                    )
                if (!gateResult.allowed) {
                    riskBlockedCount++
                    riskBlocked = true
                    logger.debug { "Backtest $ticker: risk gate blocked at ${current.time}: ${gateResult.reason}" }
                    meterRegistry?.counter("bt_risk_blocked_total", "ticker", ticker, "reason", gateResult.reason ?: "UNKNOWN")?.increment()
                    recordEquity(current.closePrice, current.time)
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
                            current,
                            fundingHistory = fundingHistory,
                            tradeLog = tradeLog,
                            passiveFillEnabled = effectivePassiveFill,
                        )
                    recordRiskSimClose(ticker, curPos, "REVERSAL", current.openPrice, i, sorted, cash)
                    val reversed =
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
                            current,
                            leverage,
                            effectiveCapitalSlice,
                            riskPerTradePercent,
                            futuresMaxContractsPerPosition,
                            passiveFillEnabled = effectivePassiveFill,
                        )
                    position = reversed
                    if (reversed != null) {
                        cash = applyOpen(cash, reversed, ticker, commissionMultiplier)
                        recordRiskSimOpen(ticker, reversed, sorted)
                        // Вход по open этого бара → сразу проверяем high/low этого же бара.
                        handleProtectiveExits(i, current)
                    }
                }
                recordEquity(current.closePrice, current.time)
                continue
            }

            // Открытие новой позиции на открытии текущей свечи
            val opened =
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
                    current,
                    leverage,
                    effectiveCapitalSlice,
                    riskPerTradePercent,
                    futuresMaxContractsPerPosition,
                    passiveFillEnabled = effectivePassiveFill,
                )
            position = opened
            if (opened != null) {
                cash = applyOpen(cash, opened, ticker, commissionMultiplier)
                recordRiskSimOpen(ticker, opened, sorted)
                // Вход по open этого бара → сразу проверяем high/low этого же бара.
                handleProtectiveExits(i, current)
            }
            recordEquity(current.closePrice, current.time)
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
                    sorted.last(),
                    fundingHistory = fundingHistory,
                    tradeLog = tradeLog,
                    passiveFillEnabled = effectivePassiveFill,
                )
            recordRiskSimClose(ticker, pos, "END_OF_PERIOD", sorted.last().closePrice, sorted.lastIndex, sorted, cash)
        }
        // Финальная точка — капитал после закрытия EOP-позиции. Перезаписываем
        // последнюю точку (а не добавляем вторую с той же меткой): иначе длина
        // кривой не совпадает с числом баров, а в доходности появляется лишний ноль
        // (docs/28 §2 №11).
        if (equityCurve.isNotEmpty()) {
            equityCurve[equityCurve.lastIndex] = cash
        } else {
            equityCurve.add(cash)
            if (sorted.isNotEmpty()) equityTimestamps.add(sorted.last().time)
        }

        val metricsResult =
            BacktestMetrics.compute(
                ticker,
                equityCurve,
                equityTimestamps,
                tradeReturns,
                tradeHoldBars,
                commissionAccumulator[0],
                instrumentsConfig.isFutures(ticker),
            )
        val result = metricsResult.copy(trades = tradeLog)
        logger.info {
            "Backtest $ticker: return=${String.format("%.2f%%", result.totalReturn * 100)}, " +
                "Sharpe=${String.format("%.2f", result.sharpeRatio)}, Sortino=${String.format("%.2f", result.sortinoRatio)}, " +
                "MDD=${String.format("%.2f%%", result.maxDrawdown * 100)}, PF=${String.format("%.2f", result.profitFactor)}, " +
                "win=${String.format("%.2f%%", result.winRate * 100)}, expectancy=${String.format("%.2f", result.expectancy)}, " +
                "W/L=${String.format("%.2f", result.winLossRatio)}, trades=${result.totalTrades}, " +
                "mlBlocked=$mlBlockedCount, mtfBlocked=$mtfBlockedCount, " +
                "fundingVetoBlocked=$fundingVetoBlockedCount " +
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
     *
     * @param quantity количество лотов
     */
    internal fun computeCommission(
        ticker: String,
        price: BigDecimal,
        quantity: Int,
        commissionMultiplier: Double = 1.0,
    ): BigDecimal {
        val spec = instrumentsConfig.find(ticker)
        val fixedPerLot = spec?.totalCommissionPerLotSide() ?: BigDecimal.ZERO
        if (fixedPerLot > BigDecimal.ZERO) {
            if (quantity < 1) {
                val lotSize = spec?.lotSize?.coerceAtLeast(1) ?: 1
                return SimulatedExecution.commissionOn(price, quantity * lotSize, commissionRate(commissionMultiplier))
            }
            return SimulatedExecution.commissionFixed(
                // Дробный множитель (1.5/0.5 в стресс-сценариях) нельзя усекать до Long:
                // 1.5 → 1 превращал стресс в baseline, 0.5 → 0 обнулял комиссию фьючерса.
                fixedPerLot.multiply(BigDecimal.valueOf(commissionMultiplier)),
                quantity,
            )
        }
        val lotSize = spec?.lotSize?.coerceAtLeast(1) ?: 1
        return SimulatedExecution.commissionOn(price, quantity * lotSize, commissionRate(commissionMultiplier))
    }

    /**
     * Цена исполнения market-ордера с проскальзыванием.
     *
     * Пассивный (maker) режим research (PLAN-001 §5.1, a2,
     * [BacktestConfig.executionPassiveFillEnabled]): покупка по bid = mid − halfSpread,
     * продажа по ask = mid + halfSpread ([SimulatedExecution.passiveFill]), где
     * `halfSpread = |takerFill − reference|` — **зеркало taker-филла** относительно
     * reference. Единая база спреда/проскальзывания для обеих сторон обязательна:
     * если фьючерсный taker идёт по bps/тикам, а maker — по диапазону свечи
     * (`estimateHalfSpread`), выгода maker завышается в разы (CNYRUBF: bps ≈ 0.00125 ₽
     * против диапазона/4 ≈ 0.00275 ₽ на сторону). Применяется к входам и рыночным
     * выходам (не к защитным SL/TP/ликвидации — их вызовы передают `passiveEligible=false`).
     *
     * Taker-филл ([takerFill]):
     * - фьючерсы — в ТИКАХ (пунктах), как при исполнении на бирже; процентная
     *   ставка от цены фьючерса непропорционально велика (0.1% Si ≈ 92 пункта >
     *   стоп в [com.trading.bot.config.RiskConfig.defaultStopLossPoints] пунктов);
     * - акции при `bt.realistic-execution=true` — спред из диапазона свечи
     *   ([SimulatedExecution.estimateHalfSpread]): покупка по mid + halfSpread,
     *   продажа по mid − halfSpread;
     * - иначе (флаг выключен или свеча недоступна) — фиксированная ставка 0.1%.
     */
    internal fun executionFill(
        instrument: InstrumentsConfig.InstrumentSpec?,
        ticker: String,
        reference: BigDecimal,
        isBuy: Boolean,
        slippageMultiplier: Double,
        candle: Candle? = null,
        passiveEligible: Boolean = true,
        passiveFillEnabled: Boolean? = null,
    ): SimulatedExecution.Fill {
        val taker = takerFill(instrument, ticker, reference, isBuy, slippageMultiplier, candle)
        if (!((passiveFillEnabled ?: backtestConfig.executionPassiveFillEnabled) && passiveEligible)) {
            return taker
        }
        val halfSpread = taker.price.subtract(reference).abs()
        return SimulatedExecution.passiveFill(reference, isBuy, halfSpread)
    }

    /** Taker-филл market-ордера (без passive-ветки — см. [executionFill]). */
    private fun takerFill(
        instrument: InstrumentsConfig.InstrumentSpec?,
        ticker: String,
        reference: BigDecimal,
        isBuy: Boolean,
        slippageMultiplier: Double,
        candle: Candle?,
    ): SimulatedExecution.Fill =
        if (instrument != null && instrumentsConfig.isFutures(ticker)) {
            val bps = instrument.slippageBps
            if (bps != null && bps > BigDecimal.ZERO) {
                // SlippageModel: отраслевая конфигурация в bps (1 bp = 1/10000 цены);
                // консервативно — не меньше legacy-сдвига в 1 тик.
                val bpsSlip =
                    reference
                        .multiply(bps)
                        .multiply(BigDecimal(slippageMultiplier))
                        .divide(BigDecimal("10000"), 8, RoundingMode.HALF_UP)
                val tickSlip = instrument.priceStep.multiply(BigDecimal(slippageTicks(slippageMultiplier)))
                val slip = if (bpsSlip > tickSlip) bpsSlip else tickSlip
                SimulatedExecution.Fill(if (isBuy) reference.add(slip) else reference.subtract(slip))
            } else {
                SimulatedExecution.tickFill(reference, isBuy, slippageTicks(slippageMultiplier), instrument.priceStep)
            }
        } else if (backtestConfig.realisticExecution && candle != null) {
            val halfSpread = SimulatedExecution.estimateHalfSpread(candle, reference)
            SimulatedExecution.realisticFill(reference, isBuy, halfSpread)
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
        val qty = position.quantity.toBigDecimal()
        val unrealized =
            when (position.direction) {
                PositionDirection.LONG -> marketPrice.subtract(position.entryPrice)
                PositionDirection.SHORT -> position.entryPrice.subtract(marketPrice)
            }.multiply(profitMultiplierPerLot(ticker, instrument)).multiply(qty)
        return cash.add(unrealized)
    }

    /**
     * Множитель P&L на один лот/контракт при движении цены на 1.0.
     * - фьючерсы: pointValue = priceStepCost / priceStep (Si: 1000 ₽ на 1₽ цены,
     *   RI: 1.686 ₽ на 1 пункт индекса, CNYRUBF: 1000 ₽ на 1₽ цены) — единый
     *   источник истины с live ([com.trading.bot.application.OrderExecutionEngine.PnlCalculator.futures]).
     * - акции/FX: lotSize (цена × акции в лоте ≡ номинал лота).
     */
    private fun profitMultiplierPerLot(
        ticker: String,
        instrument: InstrumentsConfig.InstrumentSpec?,
    ): BigDecimal {
        if (instrument != null && instrumentsConfig.isFutures(ticker)) {
            return instrumentsConfig.pointValue(ticker)
        }
        return instrument?.lotSize?.toBigDecimal() ?: BigDecimal.ONE
    }

    /**
     * Целевая сторона funding-window (research P4, docs/27-funding-window-capture.md):
     * последняя ИЗВЕСТНАЯ ставка `funding_history` со датой клиринга СТРОГО раньше даты
     * бара [barTime] (без lookahead). SHORT при `r >= +shortThresholdRub`, LONG при
     * `r <= -longThresholdRub`, иначе FLAT (null).
     *
     * При `fundingWindowRandomSide=true` (placebo §7.2) набор «дней в рынке» тот же
     * (по |r| >= порога), но сторона — детерминированный псевдослучайный поток по
     * (ticker, дата бара), а не знак ставки. Контроль отделяет доход самой ставки от
     * ценового шума пребывания в рынке.
     *
     * @return целевая сторона (null = FLAT) и признак «ставка неизвестна» (fail-closed)
     */
    private fun fundingWindowTarget(
        ticker: String,
        barTime: LocalDateTime,
        fundingByDate: NavigableMap<LocalDate, BigDecimal>,
        longThresholdRub: Double,
        shortThresholdRub: Double,
    ): Pair<PositionDirection?, Boolean> {
        val lookup = fundingByDate.lowerEntry(barTime.toLocalDate()) ?: return null to true
        val rate = lookup.value
        val inMarket =
            rate.compareTo(BigDecimal(shortThresholdRub)) >= 0 ||
                rate.compareTo(BigDecimal(longThresholdRub).negate()) <= 0
        if (!inMarket) {
            return null to false
        }
        if (backtestConfig.fundingWindowRandomSide) {
            val parity = ((ticker.hashCode().toLong() + barTime.toLocalDate().toEpochDay()) % 2L + 2L) % 2L
            val randomSide = if (parity == 0L) PositionDirection.LONG else PositionDirection.SHORT
            return randomSide to false
        }
        val target =
            when {
                rate.compareTo(BigDecimal(shortThresholdRub)) >= 0 -> PositionDirection.SHORT
                rate.compareTo(BigDecimal(longThresholdRub).negate()) <= 0 -> PositionDirection.LONG
                else -> null
            }
        return target to false
    }

    /**
     * Сумма funding за перечисленные клиринги.
     *
     * Известные даты берутся из исторического ряда (`funding_history`), пропуски —
     * по конфигурационной ставке инструмента. Раньше пропуск давал 0 (fail-open) и
     * молча занижал издержки; количество пропусков возвращается вторым элементом,
     * чтобы вызывающий код писал метрику (docs/28 §2 №14).
     *
     * `internal` — намеренно, для регресс-теста.
     */
    internal fun fundingSumForDates(
        dates: List<LocalDate>,
        fundingHistory: Map<LocalDate, BigDecimal>,
        fallbackPerClearing: BigDecimal,
    ): Pair<BigDecimal, Int> {
        var missing = 0
        val sum =
            dates.fold(BigDecimal.ZERO) { acc, date ->
                val rate = fundingHistory[date]
                if (rate == null) {
                    missing++
                    acc.add(fallbackPerClearing)
                } else {
                    acc.add(rate)
                }
            }
        return sum to missing
    }

    /**
     * Знак ставки funding в P&L (research P4, docs/27): направленная модель — LONG платит
     * положительную ставку (+1), SHORT её получает (−1). При
     * `bt.funding-direction-aware=false` — историческая direction-agnostic модель (+1,
     * ставка списывается обеим сторонам; воспроизводимость прежних артефактов).
     */
    private fun fundingChargeSign(direction: PositionDirection): BigDecimal =
        if (backtestConfig.fundingDirectionAware && direction == PositionDirection.SHORT) {
            BigDecimal.ONE.negate()
        } else {
            BigDecimal.ONE
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
        candle: Candle? = null,
        leverage: Double = 1.0,
        capitalSlice: Double = backtestConfig.capitalSlice,
        riskPerTradePercent: Double? = null,
        futuresMaxContractsPerPosition: Int? = null,
        passiveFillEnabled: Boolean? = null,
    ): PositionSim? {
        if (cash <= BigDecimal.ZERO) return null
        val instrument = instrumentsConfig.find(ticker)
        val direction = if (signal == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val stopPoints = resolveAtrStopPoints(history, ticker, instrument)
        val sized =
            sizeQuantity(
                ticker,
                instrument,
                direction,
                price,
                cash,
                slPoints ?: stopPoints,
                leverage,
                capitalSlice,
                riskPerTradePercent,
                futuresMaxContractsPerPosition,
                percentSlOverride = slPercent * 100.0,
            )
        if (sized.quantity <= 0) return null
        logger.debug {
            "Backtest $ticker OPEN qty=${sized.quantity} stopPoints=${slPoints ?: stopPoints} riskPct=${riskPerTradePercent ?: "default"} maxC=${futuresMaxContractsPerPosition ?: "default"} price=$price cash=$cash"
        }

        val fill =
            executionFill(
                instrument,
                ticker,
                price,
                direction == PositionDirection.LONG,
                slippageMultiplier,
                candle,
                passiveEligible = true,
                passiveFillEnabled = passiveFillEnabled,
            )
        val atr = Atr.calculate(history, riskConfig.futuresAtrStopPeriod)
        val atrPercent =
            if (atr != null && price > BigDecimal.ZERO) {
                atr.divide(price, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100)).toDouble()
            } else {
                null
            }
        val volatilityPercent = volatility20(history)
        // Уровни стопа/тейка выводятся из ЦЕНЫ РЕШЕНИЯ (`price`), а не из цены филла:
        // исполнение (taker/passive) не должно менять решения стратегии, иначе A/B по
        // стоимости исполнения некорректен (лучший вход сдвигал бы и стоп, оставляя
        // R-мультипл неизменным — эффект мейкера исчезал бы). `entryPrice` — фактический
        // филл, по нему считается P&L.
        return PositionSim(
            direction = direction,
            quantity = sized.quantity,
            entryPrice = fill.price,
            stopLoss = stopPrice(ticker, instrument, direction, price, slPercent, stopPoints, slPoints),
            takeProfit = takePrice(ticker, instrument, direction, price, tpPercent, tpPoints),
            entryBars = bar,
            liquidationPrice = sized.liquidationPrice,
            entryTime = candle?.time,
            hourOfDay = candle?.time?.hour,
            dayOfWeek = candle?.time?.dayOfWeek?.name,
            atrPercent = atrPercent,
            volatilityPercent = volatilityPercent,
        )
    }

    /** Волатильность по closing-returns последних 20 баров (%, стандартное отклонение). */
    private fun volatility20(history: List<Candle>): Double? {
        if (history.size < 21) return null
        val closes = history.takeLast(20).map { it.closePrice.toDouble() }
        var prev = closes[0]
        val returns = ArrayList<Double>(closes.size - 1)
        for (c in closes.drop(1)) {
            val r = (c - prev) / prev
            returns.add(r)
            prev = c
        }
        val mean = returns.average()
        val variance = returns.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance)
        return std * 100
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
        leverage: Double = 1.0,
        capitalSlice: Double = backtestConfig.capitalSlice,
        riskPerTradePercentOverride: Double? = null,
        futuresMaxContractsPerPosition: Int? = null,
        percentSlOverride: Double? = null,
    ): SizedPosition {
        val futuresSizer = positionSizer
        if (instrument != null && instrumentsConfig.isFutures(ticker) && futuresSizer != null) {
            val stopPointsResolved = stopPoints ?: riskConfig.defaultStopLossPoints
            val size =
                if (riskPerTradePercentOverride == null && futuresMaxContractsPerPosition == null) {
                    futuresSizer.calculateContracts(
                        ticker = ticker,
                        portfolioMoney = cash,
                        stopLossPoints = stopPointsResolved,
                        currentGo = instrument.go,
                        entryPrice = price,
                        direction = direction,
                    )
                } else {
                    futuresSizer.calculateContracts(
                        ticker = ticker,
                        portfolioMoney = cash,
                        stopLossPoints = stopPointsResolved,
                        currentGo = instrument.go,
                        entryPrice = price,
                        direction = direction,
                        riskPerTradePercent = riskPerTradePercentOverride,
                        maxContractsPerPosition = futuresMaxContractsPerPosition,
                    )
                }
            if (size.quantity <= 0) {
                logger.debug { "Backtest $ticker: sizer rejected entry (${size.reason})" }
            }
            logger.debug {
                "Backtest $ticker SIZING qty=${size.quantity} stopPoints=$stopPointsResolved riskPct=${riskPerTradePercentOverride ?: "default"} maxC=${futuresMaxContractsPerPosition ?: "default"} money=$cash"
            }
            return SizedPosition(size.quantity, size.liquidationPrice)
        }
        val lotSize = instrument?.lotSize?.coerceAtLeast(1) ?: 1
        val notionalPerLot = price.multiply(BigDecimal(lotSize))
        val leveragedCash = cash.multiply(BigDecimal.valueOf(leverage))
        val sliceLots =
            if (notionalPerLot > BigDecimal.ZERO) {
                leveragedCash
                    .multiply(BigDecimal.valueOf(capitalSlice))
                    .divide(notionalPerLot, 0, RoundingMode.DOWN)
                    .toInt()
            } else {
                0
            }
        val effectiveSl =
            instrument?.slPercent
                ?: percentSlOverride?.let { BigDecimal.valueOf(it) }
                ?: riskConfig.defaultStopLossPercent
        val commissionPerLot = instrument?.totalCommissionPerLotSide() ?: BigDecimal.ZERO
        val effectiveRiskPercent = riskPerTradePercentOverride ?: riskConfig.riskPerTradePercent.toDouble()
        val riskAmount =
            leveragedCash
                .multiply(BigDecimal(effectiveRiskPercent))
                .divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val lossPerLot =
            price
                .multiply(effectiveSl)
                .divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(lotSize))
                .add(commissionPerLot.multiply(BigDecimal(2)))
        val maxLotsByRisk =
            if (lossPerLot > BigDecimal.ZERO) {
                riskAmount.divide(lossPerLot, 0, RoundingMode.DOWN).toInt()
            } else {
                Int.MAX_VALUE
            }
        return SizedPosition(minOf(sliceLots, maxLotsByRisk))
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
     * Параметр [slPercent] (из API/конфига) используется как fallback перед RiskConfig.
     */
    internal fun stopPrice(
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
            val raw =
                when (direction) {
                    PositionDirection.LONG -> fillPrice.subtract(offset)
                    PositionDirection.SHORT -> fillPrice.add(offset)
                }
            // Уровень обязан лежать на сетке шага цены (как live `ExitRules.calcSL`):
            // округление до 2 знаков давало off-tick стоп, дистанция которого отличается
            // от заданной на ±priceStep/2 (для CNYRUBF 0.001 — до ±5 пт от узкого стопа) —
            // скан-5 P2.
            val rounding =
                when (direction) {
                    PositionDirection.LONG -> RoundingMode.FLOOR
                    PositionDirection.SHORT -> RoundingMode.CEILING
                }
            return ExitRules.alignToGrid(raw, instrument.priceStep, rounding)
        }
        val effectiveSl = instrument?.slPercent ?: BigDecimal(slPercent * 100.0)
        return ExitRules.calcSL(fillPrice, direction, effectiveSl, instrument?.priceStep ?: BigDecimal("0.01"))
    }

    /** Тейк-профит: для фьючерсов — пункты, для акций — процент (см. [stopPrice]).
     * Per-instrument TP% (InstrumentsConfig.InstrumentSpec.tpPercent) имеет приоритет над глобальным.
     * Параметр [tpPercent] (из API/конфига) используется как fallback перед RiskConfig. */
    internal fun takePrice(
        ticker: String,
        instrument: InstrumentsConfig.InstrumentSpec?,
        direction: PositionDirection,
        fillPrice: BigDecimal,
        tpPercent: Double,
        tpPoints: Int? = null,
    ): BigDecimal {
        if (instrument != null && instrumentsConfig.isFutures(ticker)) {
            val offset = BigDecimal(tpPoints ?: riskConfig.defaultTakeProfitPoints).multiply(instrument.priceStep)
            val raw =
                when (direction) {
                    PositionDirection.LONG -> fillPrice.add(offset)
                    PositionDirection.SHORT -> fillPrice.subtract(offset)
                }
            // Сетка шага цены (как live `ExitRules.calcTP`) — скан-5 P2.
            val rounding =
                when (direction) {
                    PositionDirection.LONG -> RoundingMode.CEILING
                    PositionDirection.SHORT -> RoundingMode.FLOOR
                }
            return ExitRules.alignToGrid(raw, instrument.priceStep, rounding)
        }
        val effectiveTp = instrument?.tpPercent ?: BigDecimal(tpPercent * 100.0)
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
        candle: Candle? = null,
        applySlippage: Boolean = true,
        passiveEligible: Boolean = true,
        fundingHistory: Map<LocalDate, BigDecimal> = emptyMap(),
        tradeLog: MutableList<BacktestTradeRecord>? = null,
        passiveFillEnabled: Boolean? = null,
    ): BigDecimal {
        val instrument = instrumentsConfig.find(ticker)
        val fill =
            if (applySlippage) {
                executionFill(
                    instrument,
                    ticker,
                    price,
                    pos.direction == PositionDirection.SHORT,
                    slippageMultiplier,
                    candle,
                    passiveEligible,
                    passiveFillEnabled,
                )
            } else {
                SimulatedExecution.Fill(price)
            }
        val commissionEntry = computeCommission(ticker, pos.entryPrice, pos.quantity, commissionMultiplier)
        val commissionExit = computeCommission(ticker, fill.price, pos.quantity, commissionMultiplier)
        commissionAccumulator[0] = commissionAccumulator[0].add(commissionEntry).add(commissionExit)
        val gross =
            when (pos.direction) {
                PositionDirection.LONG -> fill.price.subtract(pos.entryPrice)
                PositionDirection.SHORT -> pos.entryPrice.subtract(fill.price)
            }.multiply(profitMultiplierPerLot(ticker, instrument)).multiply(BigDecimal(pos.quantity))
        // Funding (CNYRUBF): списывается за каждый клиринг, который позиция пережила
        // между открытием и закрытием (паритет live PnlCalculator.futures).
        // Если доступен исторический ряд (funding_history) — per-date значения;
        // иначе fallback на конфигурационное значение.
        // Research P4 (docs/27): при `bt.funding-direction-aware=true` ставка направленная —
        // LONG платит положительную, SHORT её получает (знак [fundingChargeSign]).
        val funding =
            if (instrument != null && instrumentsConfig.isFutures(ticker) &&
                pos.entryTime != null && candle != null
            ) {
                val dates =
                    FundingCosts.clearingDates(
                        pos.entryTime,
                        candle.time,
                        (moexHolidayCalendar ?: MoexHolidayCalendar())::isTradingDay,
                    )
                val fundingSign = fundingChargeSign(pos.direction)
                if (dates.isEmpty()) {
                    BigDecimal.ZERO
                } else if (fundingHistory.isNotEmpty()) {
                    // Дырка в ряду — НЕ ноль: 0 занижал бы издержки (fail-open).
                    // Fallback — конфигурационная ставка инструмента, факт дырки виден
                    // в метрике `bt_funding_missing_total` (docs/28 §2 №14).
                    val (fundingSum, missing) =
                        fundingSumForDates(dates, fundingHistory, instrument.fundingPerClearing())
                    if (missing > 0) {
                        meterRegistry?.counter("bt_funding_missing_total", "ticker", ticker)?.increment(missing.toDouble())
                    }
                    fundingSign.multiply(fundingSum).multiply(BigDecimal(pos.quantity))
                } else if (instrument.fundingPerClearing() > BigDecimal.ZERO) {
                    fundingSign.multiply(instrument.fundingPerClearing()).multiply(
                        BigDecimal(pos.quantity).multiply(BigDecimal(dates.size)),
                    )
                } else {
                    BigDecimal.ZERO
                }
            } else {
                BigDecimal.ZERO
            }
        val pnl = gross.subtract(commissionEntry).subtract(commissionExit).subtract(funding)

        tradeReturns.add(pnl.toDouble())
        tradeHoldBars.add((closeBar - pos.entryBars).coerceAtLeast(0))
        tradeLog?.add(
            BacktestTradeRecord(
                ticker = ticker,
                direction = pos.direction,
                quantity = pos.quantity,
                entryPrice = pos.entryPrice,
                exitPrice = fill.price,
                entryTime = pos.entryTime,
                exitTime = candle?.time,
                closeReason = reason,
                pnl = pnl.toDouble(),
                commission = commissionEntry.add(commissionExit).toDouble(),
                funding = funding.toDouble(),
                holdBars = (closeBar - pos.entryBars).coerceAtLeast(0),
                hourOfDay = pos.hourOfDay,
                dayOfWeek = pos.dayOfWeek,
                atrPercent = pos.atrPercent,
                volatilityPercent = pos.volatilityPercent,
            ),
        )
        // Комиссия входа уже списана при открытии; здесь добавляется gross за вычетом
        // комиссии выхода и funding.
        val newCash = cash.add(gross).subtract(commissionExit).subtract(funding)
        logger.debug { "Backtest close $ticker $reason pnl=$pnl" }
        return newCash
    }

    /**
     * Record close event in the risk simulator for live-risk-gates mode.
     * Called after every closePosition to feed the in-memory trade history.
     */
    private fun recordRiskSimClose(
        ticker: String,
        pos: PositionSim,
        reason: String,
        exitPrice: BigDecimal,
        exitBar: Int,
        sorted: List<Candle>,
        cash: BigDecimal,
    ) {
        if (!backtestConfig.liveRiskGates || riskSimulator == null) return
        val entryTime = if (pos.entryBars in sorted.indices) sorted[pos.entryBars].time else LocalDateTime.MIN
        val exitTime = if (exitBar in sorted.indices) sorted[exitBar].time else LocalDateTime.MAX
        riskSimulator.recordClose(
            ticker = ticker,
            direction = pos.direction,
            entryPrice = pos.entryPrice,
            exitPrice = exitPrice,
            quantity = pos.quantity,
            entryTime = entryTime,
            exitTime = exitTime,
            closeReason = reason,
            commission =
                computeCommission(ticker, pos.entryPrice, pos.quantity) +
                    computeCommission(ticker, exitPrice, pos.quantity),
            cash = cash,
        )
    }

    /**
     * Record open event in the risk simulator.
     */
    private fun recordRiskSimOpen(
        ticker: String,
        pos: PositionSim,
        sorted: List<Candle>,
    ) {
        if (!backtestConfig.liveRiskGates || riskSimulator == null) return
        val lotSize = instrumentsConfig.find(ticker)?.lotSize?.coerceAtLeast(1) ?: 1
        riskSimulator.recordOpen(
            ticker = ticker,
            direction = pos.direction,
            quantity = pos.quantity,
            entryPrice = pos.entryPrice,
            lotSize = lotSize,
            entryTime = if (pos.entryBars in sorted.indices) sorted[pos.entryBars].time else LocalDateTime.MIN,
        )
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
            isFutures = instrumentsConfig.isFutures(ticker),
            equityCurve = emptyList(),
            monthlyReturns = emptyMap(),
        )
}
