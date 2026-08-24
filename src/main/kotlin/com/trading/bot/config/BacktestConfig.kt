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
 * @property liveRiskGates включает полную цепочку risk gates из LIVE
 *   (BacktestRiskSimulator): daily loss, drawdown, Kelly sizing, NET EV,
 *   portfolio concentration, sector/max positions. При false — прежнее
 *   поведение (capitalSlice + risk cap). По умолчанию false (обратная совместимость).
 */
@Component
@ConfigurationProperties(prefix = "bt")
class BacktestConfig {
    var initialCapital: BigDecimal = BigDecimal("100000")
    var days: Int = 365
    var timeframe: String = "MINUTE_10"
    var minBarsForSignal: Int = 30
    var slPercent: Double = 2.0
    var tpPercent: Double = 4.0
    var capitalSlice: Double = 0.20
    var mlFilterEnabled: Boolean = false
    var mtfFilterEnabled: Boolean = false
    var monteCarloSimulations: Int = 1000
    var monteCarloSeed: Long = 42
    var liveRiskGates: Boolean = false
}
