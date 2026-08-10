package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Риск-конфигурация (prefix = "risk").
 *
 * Депозит: 50 000 ₽. Дневной лимит убытка: 10% = 5 000 ₽.
 * Фьючерс Si: риск на сделку 1% = 500 ₽, стоп 50 пунктов.
 */
@Component
@ConfigurationProperties(prefix = "risk")
class RiskConfig {
    var enabled: Boolean = true
    var maxPositionRub: BigDecimal = BigDecimal("50000")
    var maxDailyLossRub: BigDecimal = BigDecimal("5000")
    var maxOpenPositions: Int = 1
    var maxSectorExposure: Int = 2
    var maxVolatilityPercent: Double = 5.0
    var defaultStopLossPercent: Double = 2.0
    var defaultTakeProfitPercent: Double = 4.0
    var trailingStopEnabled: Boolean = true
    var trailingStopPercent: Double = 1.0
    var sectors: Map<String, String> = emptyMap()

    // ===== Фьючерсные guardrails (Si) =====

    /** Риск на сделку, % от депозита. 1.0 = 500 ₽ на 50k. */
    var riskPerTradePercent: Double = 1.0

    /**
     * Доля критерия Келли. Полный (Full) Kelly слишком агрессивен на реальных
     * рынках. Золотой стандарт: 0.5 (Half-Kelly) или 0.25 (Quarter-Kelly) от результата.
     * Значения: 1.0 = Full, 0.5 = Half, 0.25 = Quarter. По умолчанию Quarter-Kelly
     * (консервативно для LLM-агентов с плохо калиброванной уверенностью).
     */
    var kellyFraction: Double = 0.25

    /**
     * Z-score для Wilson lower bound win rate (шринкейдж статистики).
     * win rate с малой выборки заменяется на нижнюю границу Wilson-интервала:
     *   w = (p + z²/2n - z*sqrt((p(1-p) + z²/4n)/n)) / (1 + z²/n)
     * z = 1.0 даёт ~84% односторонний (консервативный) win rate. Защита от
     * переоценки Kelly на «галлюцинирующей» статистике из 5-15 сделок.
     */
    var kellyWilsonZ: Double = 1.0

    /**
     * Минимальное количество закрытых сделок для использования критерия Келли.
     * При выборке меньше порога Kelly статистически бессмысленен (high variance),
     * поэтому используется консервативная доля [kellyNoDataFraction].
     */
    var kellyMinTrades: Int = 15

    /**
     * Доля от maxPositionRub при отсутствии/недостатке статистики для Kelly.
     * 0.15 = 15% от 50k = 7 500 ₽. Консервативный fallback вместо 100% депозита.
     */
    var kellyNoDataFraction: Double = 0.15

    /**
     * Жёсткий кап доли Kelly от maxPositionRub (0..1). 0.50 = Half-Kelly cap.
     * Даже при идеальной статистике позиция не превысит 50% депозита на тикер.
     */
    var kellyMaxPositionFraction: Double = 0.50

    /**
     * Целевая волатильность для volatility targeting, % в ДЕНЬ (дневной горизонт).
     * multiplier = volatilityTargetPercent / dailyVolPercent, где dailyVolPercent —
     * realized volatility (stddev лог-доходностей по DAY_1 свечам) либо дневной
     * эквивалент ATR (10-мин ATR% * sqrt(свечей в дне)). dailyVol 2% -> ~2x,
     * 8% -> ~0.5x, 16% -> ~0.25x. Чем выше фактическая волатильность, тем меньше размер.
     */
    var volatilityTargetPercent: Double = 4.0

    /**
     * Глубина (в свечах DAY_1) для расчёта realized volatility. 20 торговых дней.
     */
    var volatilityLookbackDays: Int = 20

    /**
     * Количество 10-минутных свечей в торговой сессии для масштабирования
     * внутридневной волатильности к дневной: dailyVol ≈ intradayVol * sqrt(N).
     * Используется только как fallback при отсутствии дневных свечей.
     */
    var volatilityFallbackCandlesPerDay: Int = 57

    /** Нижняя граница множителя размера от волатильности (жёсткий floor при экстремальной ATR). */
    var minVolatilitySizeMultiplier: Double = 0.25

    /** Верхняя граница множителя размера от волатильности (низкая ATR не раздувает позицию без оглядки). */
    var maxVolatilitySizeMultiplier: Double = 2.0

    /**
     * Жёсткий лимит Gross Exposure, % от депозита. Сумма всех открытых позиций
     * (нотионал long + short) не может превышать этот предел. Защита от коррелированных шоков.
     */
    var maxGrossExposurePercent: Double = 150.0

    /**
     * Жёсткий лимит Net Exposure, % от депозита. Чистый directional риск
     * (сумма long - сумма short) ограничен в обе стороны. 100 = не более депозита.
     */
    var maxNetExposurePercent: Double = 100.0

    /**
     * Порог корреляции внутри сектора, при котором запрещено открывать вторую позицию
     * в том же секторе (защита от концентрированных коррелированных движений). 0.7 = 70%.
     */
    var maxSectorCorrelation: Double = 0.7

    /**
     * Коэффициент деградации Kelly при просадке (0..1). Когда бот в drawdown-recovery
     * (>=3 убыточных сделок подряд), итоговый размер умножается на этот множитель
     * (например 0.5 = позиции ещё в 2 раза меньше). Fallback для непрерывной
     * деградации по глубине просадки [drawdownScaleTiers].
     */
    var kellyDrawdownReduction: Double = 0.5

    /**
     * Непрерывная деградация дробного Kelly по ГЛУБИНЕ просадки (от пика AUM).
     * Ключ — просадка в % от пика (0.0..100.0), значение — множитель размера (0..1).
     * Берётся ближайший не превышающий просадку tier (floor). Пример:
     *   0% -> 1.0, 3% -> 0.75, 6% -> 0.5, 10% -> 0.25, 15% -> 0.0 (стоп-вход).
     */
    var drawdownScaleTiers: Map<Double, Double> =
        sortedMapOf(
            0.0 to 1.0,
            3.0 to 0.75,
            6.0 to 0.5,
            10.0 to 0.25,
            15.0 to 0.0,
        )

    /** Стоп-лосс по умолчанию в пунктах. 50 пунктов * 10 ₽ = 500 ₽ при 1 контракте. */
    var defaultStopLossPoints: Int = 50

    /** Тейк-профит по умолчанию в пунктах. R:R = 1:2. */
    var defaultTakeProfitPoints: Int = 100

    /** Если расстояние до ликвидации < X% от буфера маржи — срочное закрытие. */
    var minLiquidationDistancePercent: Double = 25.0

    /** Порог КРИТИЧЕСКОЙ близости к ликвидации, % от остаточного буфера маржи.
     * < X% → немедленный market close (должен быть строго меньше minLiquidationDistancePercent). */
    var criticalLiquidationDistancePercent: Double = 10.0

    /** Порог задействования маржи, % от депозита. > 30% → запрет входа. */
    var maxMarginUsagePercent: Double = 30.0

    /** Жёсткий лимит контрактов на позицию (Si: 1). */
    var maxContractsPerPosition: Int = 1

    /** Максимум открытых фьючерсных позиций (Si: 1). Отдельно от акций. */
    var futuresMaxOpenPositions: Int = 1

    /** Торговые часы, МСК. Вне окна — вход запрещён. */
    var tradingHoursStart: String = "10:00"
    var tradingHoursEnd: String = "18:30"

    // ===== Multi-Tier Drawdown Protection (% от AUM) =====

    /**
     * Дневной лимит убытка, % от AUM. Эффективный лимит в рублях:
     * max(AUM * maxDailyLossPercent / 100, maxDailyLossRub) — масштабируется при
     * росте/падении капитала, рублёвое значение остаётся абсолютным «полом».
     * 10.0 = нельзя терять более 10% капитала за день. <= 0 → только рублёвый лимит.
     */
    var maxDailyLossPercent: Double = 10.0

    /** Скользящий лимит убытка за 7 дней, % от AUM (защита от «смерти от тысячи порезов»). */
    var maxRollingLossPercent7d: Double = 15.0

    /** Скользящий лимит убытка за 30 дней, % от AUM. */
    var maxRollingLossPercent30d: Double = 25.0

    /**
     * Лимит серии убыточных сделок подряд. При достижении LLM-агент переводится
     * в режим Shadow/Read-only (новые входы заблокированы) до появления прибыльной
     * сделки, но не менее [shadowModeCooldownHours].
     */
    var maxConsecutiveLosses: Int = 3

    /** Включён ли Shadow/Read-only режим для LLM-агента при серии убытков. */
    var shadowModeEnabled: Boolean = true

    /** Минимальная длительность Shadow/Read-only режима, часы. */
    var shadowModeCooldownHours: Long = 24

    // ===== Volatility index filter (MOEX RVI) =====

    /** Включён ли фильтр по индексу волатильности MOEX (RVI). */
    var volatilityIndexEnabled: Boolean = true

    /** Тикер индекса волатильности на MOEX ISS. */
    var volatilityIndexTicker: String = "RVI"

    /** Уровень индекса волатильности (%), выше которого торговля ставится на паузу. */
    var maxVolatilityIndexPercent: Double = 50.0

    // ===== Portfolio Risk Engine (агрегированный портфельный риск) =====

    /** Мастер-выключатель портфельного риск-движка. */
    var portfolioRiskEnabled: Boolean = true

    /**
     * Жёсткая блокировка при превышении портфельных лимитов. false — только
     * мягкое уменьшение размера (SCALE), без запрета входа.
     */
    var portfolioRiskBlocked: Boolean = true

    /**
     * Лимит однодневного VaR95 портфеля, % от AUM. VaR95 = 1.645·σp·grossExposure.
     * Выше — вход запрещён (PORTFOLIO_VAR).
     */
    var maxPortfolioVaRPercent: Double = 5.0

    /** Warn-порог VaR95 (% AUM): выше — размер позиции начинает уменьшаться (SCALE). */
    var portfolioVarWarnPercent: Double = 3.0

    /**
     * Лимит эффективного числа НЕЗАВИСИМЫХ ставок (correlation-adjusted HHI):
     * 1 = весь портфель — одна ставка на рынок. Ниже — вход запрещён
     * (PORTFOLIO_CONCENTRATION). Например 3 коррелированные позиции с ρ=0.75 -> ~1.2.
     */
    var minEffectivePositions: Double = 1.5

    /** Warn-порог эффективного числа ставок: ниже — SCALE. */
    var portfolioEffectiveWarnPositions: Double = 2.0

    /**
     * Лимит направленной концентрации |net|/gross, %. Для long-only портфеля
     * концентрация всегда 100% — поэтому по умолчанию порог 100% (метрика
     * информационная, в report). Актуализируется для смешанных long/short книг:
     * ниже порога сигнализирует, что лонги и шорты не нетят друг друга.
     */
    var maxDirectionalConcentrationPercent: Double = 100.0

    /** Warn-порог направленной концентрации, %: выше — SCALE. */
    var portfolioConcentrationWarnPercent: Double = 100.0

    /** Нижняя граница SCALE-фактора (минимальный остаточный размер позиции). */
    var minPortfolioScaleFactor: Double = 0.25

    /** Глубина расчёта корреляций портфеля (свечей). */
    var portfolioCorrelationLookbackPeriod: Int = 50

    // ===== Volatility Engine 2.0 (Market Regime + Implied Volatility) =====

    /** Включён ли движок рыночного режима волатильности. */
    var marketRegimeEnabled: Boolean = true

    /** Глубина окна истории волатильности (календарных дней) для распределения режима. */
    var regimeLookbackDays: Int = 60

    /** Минимум исторических наблюдений для классификации (иначе режим NORMAL). */
    var regimeMinHistorySamples: Int = 20

    /** Перцентили перехода между режимами: LOW → NORMAL, NORMAL → VOLATILE, VOLATILE → STRESS. */
    var regimePercentileLow: Double = 40.0
    var regimePercentileNormal: Double = 70.0
    var regimePercentileVolatile: Double = 90.0

    /** Множитель размера позиции при режиме VOLATILE (0.5 = половина). */
    var regimeVolatileSizeMultiplier: Double = 0.5

    /** Минимальный открытый интерес (OPENPOSITION) месяца для выбора ближайшего ликвидного. */
    var regimeMinOpenPosition: Long = 1000

    /** Включён ли расчёт подразумеваемой волатильности по опционам FORTS (Black-76). */
    var impliedVolatilityEnabled: Boolean = true

    /** Тикер базового фьючерса для расчёта IV (опционы с ASSETCODE = Si). */
    var impliedVolatilityTicker: String = "Si"

    /** TTL кэша опционной таблицы / IV, минуты. */
    var impliedVolatilityCacheTtlMinutes: Long = 15
}
