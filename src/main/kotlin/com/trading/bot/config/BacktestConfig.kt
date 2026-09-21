package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Конфигурация бэктеста (prefix = "bt").
 *
 * Параметры прогона по умолчанию для REST-бэктеста (`/api/v1/backtest/{ticker}`,
 * `/api/v1/backtest/{ticker}/validate`). Каждый вызов может переопределить их
 * query-параметрами; значения здесь используются, когда параметр не указан.
 *
 * @property initialCapital стартовый капитал прогона (руб).
 * @property days глубина истории в днях по умолчанию.
 * @property timeframe таймфрейм свечей по умолчанию.
 * @property minBarsForSignal минимальное число баров для сигнала (warm-up).
 * @property slPercent стоп-лосс в процентах от цены входа (например 2.0 = 2%).
 * @property tpPercent тейк-профит в процентах от цены входа (например 4.0 = 4%).
 * @property capitalSlice доля текущего капитала на одну позицию (0.20 = 20%).
 *   Используется ТОЛЬКО как fallback для не-фьючерсных тикеров (или при отсутствии
 *   PositionSizer) и ограничивается сверху риск-капом на сделку
 *   (risk.risk-per-trade-percent% портфеля против убытка на risk.default-stop-loss-percent%).
 *   Для фьючерсных тикеров размер позиции считает production-сайзер
 *   [com.trading.bot.domain.risk.PositionSizer] (единый алгоритм с live).
 * @property mlFilterEnabled применять ML-фильтр входа (раздел 13.11.6) в бэктесте.
 *   При `true` бэктест прогоняет модель на входе каждого бара (требуется
 *   доступная модель, иначе — fail-closed: входы блокируются). Не влияет на
 *   live-гейт (`ml.filter.enabled`).
 * @property mtfFilterEnabled применять multi-timeframe фильтр тренда старшего ТФ
 *   (раздел 13.9.1) в бэктесте. При `true` вход гейтится трендом ресемплированных
 *   в `mtf.filter.higher-timeframe` свечей (point-in-time на момент бара;
 *   недостаток баров старшего ТФ — fail-closed: вход блокируется). Не влияет на
 *   live-гейт (`mtf.filter.enabled`).
 * @property monteCarloSimulations число bootstrap-симуляций для Monte Carlo
 *   (раздел 13.7.8), по умолчанию 1000.
 * @property monteCarloSeed seed генератора для детерминированных прогонов
 *   (воспроизводимость в тестах и CI).
 * @property mcSeedCount число независимых seeds, по которым усредняется Monte Carlo
 *   (B2): убирает зависимость вывода устойчивости от ОДНОГО seed. При 1 — прежнее
 *   поведение (детерминированный прогон с [monteCarloSeed]).
 * @property liveRiskGates включает полную цепочку risk gates из LIVE
 *   (BacktestRiskSimulator): daily loss, drawdown, Kelly sizing, NET EV,
 *   portfolio concentration, sector/max positions. При false — прежнее
 *   поведение (capitalSlice + risk cap). По умолчанию true (паритет LIVE).
 *   Отдельное свойство `liveStrategyBacktestSignalGenerator` (bt.agent.live-strategies)
 *   включает те же стратегии что и live.
 * @property realisticExecution реалистичное исполнение: спред для market-ордеров
 *   акций оценивается из диапазона свечи (high-low)/4
 *   ([com.trading.bot.backtest.SimulatedExecution.estimateHalfSpread]), плоская
 *   свеча — fallback 0.1%/2; фьючерсы — 1 тик. При true стоп/таргет исполняются
 *   внутри свечи по цене стопа/таргета БЕЗ двойного спреда поверх (intrabar
 *   parity с live-защитными ордерами). При false — прежняя фиксированная ставка
 *   0.1% для всех исполнений, включая стопы (legacy-режим).
 * @property fundingVetoEnabled применять funding-veto входной гейт (зеркало
 *   live-гейта [com.trading.bot.application.decision.FundingVetoGate]) в бэктесте.
 *   Вход (LONG/SHORT) блокируется исходя из фактического SWAPRATE на дату бара
 *   ([fundingHistory]) по порогам fundingVetoLongThresholdRub/fundingVetoShortThresholdRub.
 *   Ставка funding на дату входа отсутствует и fundingVetoBlockOnUnknown=true →
 *   fail-closed: вход блокируется. research-инструмент для WFA-калибровки порогов;
 *   по умолчанию false (не влияет на live, `trading.funding-veto.enabled`).
 * @property fundingVetoLongThresholdRub порог LONG funding-veto в бэктесте
 *   (руб/контракт/клиринг): LONG блокируется при funding > порога.
 * @property fundingVetoShortThresholdRub порог SHORT funding-veto в бэктесте
 *   (руб/контракт/клиринг): SHORT блокируется при funding < –порога.
 * @property fundingVetoBlockOnUnknown в бэктесте блокировать вход при отсутствии
 *   ставки funding на дату бара (fail-closed, паритет live `trading.funding-veto.block-on-unknown`).
 * @property maxHoldBars принудительный выход по времени удержания в барах
 *   (research, паттерн funding-veto; null/0 = выключено — позиция держится до SL/TP).
 *   Задаётся в `bt.max-hold-bars` (env `BT_MAX_HOLD_BARS`) или query-оверрайдом
 *   `maxHoldBars` на /backtest /validate /robustness /holdout /deployment-gate.
 * @property futuresLiquidationSimulation симулировать принудительную ликвидацию
 *   фьючерсной позиции на уровне ликвидационной цены (паритет live-monitorу
 *   [com.trading.bot.application.FuturesPositionMonitor]). При true позиция,
 *   чей внутрисвечной диапазон преодолел уровень ликвидации, закрывается по цене
 *   ликвидации (LIQUIDATION) с наивысшим приоритетом — до SL/TP. При false —
 *   прежнее поведение без ликвидации (overestimates удержания).
 */
@Component
@ConfigurationProperties(prefix = "bt")
class BacktestConfig {
    var initialCapital: BigDecimal = BigDecimal("100000")
    var days: Int = 365
    var timeframe: String = "MINUTE_10"
    var minBarsForSignal: Int = 30
    var slPercent: Double = 2.0
    var tpPercent: Double = 15.0
    var capitalSlice: Double = 0.50
    var mlFilterEnabled: Boolean = false
    var mtfFilterEnabled: Boolean = false
    var monteCarloSimulations: Int = 1000
    var monteCarloSeed: Long = 42
    var mcSeedCount: Int = 5
    var liveRiskGates: Boolean = true
    var realisticExecution: Boolean = true
    var regimeDetectionEnabled: Boolean = true
    var adaptiveConfidenceThreshold: Double = 0.60
    var futuresLiquidationSimulation: Boolean = true

    /** Funding-veto research-фильтр: false по умолчанию (live управляется trading.funding-veto.enabled). */
    var fundingVetoEnabled: Boolean = false
    var fundingVetoLongThresholdRub: Double = 2.0
    var fundingVetoShortThresholdRub: Double = 2.0
    var fundingVetoBlockOnUnknown: Boolean = true

    /** Max-hold research (выход по времени удержания в барах): 0 = выключено (держать до SL/TP). */
    var maxHoldBars: Int = 0

    /** Доля КОНЦА истории, резервируемая под финальный независимый holdout (0..1). */
    var holdoutFraction: Double = 0.20

    /** Метод Monte Carlo: "iid" | "stationary" | "block". */
    var mcMethod: String = "stationary"

    /** Средняя длина блока для stationary bootstrap (>= 1). */
    var mcAvgBlockLength: Double = 5.0

    /** Фиксированная длина блока для block bootstrap (>= 1). */
    var mcBlockLength: Int = 5

    /** Онлайн-логистическая регрессия направления (research, дефолт off).
     *  При true в live-генератор сигналов добавляется
     *  OnlineMlDirectionStrategy (см. docs/17, этап 4b) как фильтр НАПРАВЛЕНИЯ:
     *  обучается на каждом баре (без lookahead), veto при противоречии направления
     *  победителю-стратегии; веса сбрасываются на каждый simulate по cycleId.
     *  НЕ влияет на live-входы: работает только в бэктесте/валидации. */
    var mlDirectionEnabled: Boolean = false

    /** Горизонт метки онлайн-LR: через сколько баров оценивается направление. */
    var mlDirectionHorizonBars: Int = 6

    /** Минимальный модуль доходности за [mlDirectionHorizonBars] для обучения (%). */
    var mlDirectionMinReturnPercent: Double = 0.05

    /** Скорость обучения SGD. */
    var mlDirectionLearningRate: Double = 0.05

    /** L2-регуляризация SGD. */
    var mlDirectionL2: Double = 0.001

    /** Порог уверенности P(up) относительно 0.5 для сигнала (0..1). */
    var mlDirectionSignalMargin: Double = 0.05

    /** Fail-closed для ML-фильтра направления: при ML HOLD (нехватка данных /
     *  warmup / без уверенности) БЛОКИРОВАТЬ вход (true) или пропускать (false). */
    var mlDirectionBlockOnUnknown: Boolean = false

    /** Session-фильтр входа (research, pt.2): вход разрешён только когда время
     *  бара попадает в окно [sessionFilterStartMinutes, sessionFilterEndMinutes]
     *  (минуты с полуночи). Исключает «первый час после открытия» и «финал сессии»,
     *  где ликвидность/направление хуже. research-инструмент, по умолчанию off
     *  (на live-входы не влияет). */
    var sessionFilterEnabled: Boolean = false

    /** Начало окна входов session-фильтра (минуты с полуночи, 600 = 10:00). */
    var sessionFilterStartMinutes: Int = 600

    /** Конец окна входов session-фильтра (минуты с полуночи, 1080 = 18:00). */
    var sessionFilterEndMinutes: Int = 1080

    /** Pullback-фильтр входа (research, pt.2): блокирует «погоню за ценой» —
     *  вход разрешён только когда цена в полосе отката от EMA: модуль отклонения
     *  (close − EMA)/EMA ≤ pullbackMaxDeviationPercent%. При недостатке баров для
     *  EMA и pullbackBlockOnUnknown=true — fail-closed блок. research-инструмент,
     *  по умолчанию off (на live-входы не влияет). */
    var pullbackFilterEnabled: Boolean = false

    /** Период EMA для pullback-фильтра. */
    var pullbackEmaPeriod: Int = 20

    /** Максимальное отклонение цены от EMA (%), при котором вход ещё разрешён. */
    var pullbackMaxDeviationPercent: Double = 1.0

    /** Fail-closed для pullback-фильтра: при нехватке баров для EMA БЛОКИРОВАТЬ
     *  вход (true) или пропускать (false). */
    var pullbackBlockOnUnknown: Boolean = false
}
