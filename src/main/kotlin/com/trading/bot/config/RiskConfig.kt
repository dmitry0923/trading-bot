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
     * Целевая волатильность для volatility targeting, % ATR от цены.
     * multiplier = volatilityTargetPercent / atrPercent (ATR 2% -> ~2x, ATR 10% -> ~0.5x,
     * ATR 20% -> ~0.25x). Чем выше фактическая волатильность, тем меньше размер позиции.
     */
    var volatilityTargetPercent: Double = 4.0

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
     * Коэффициент деградации Kelly при просадке (0..1). Когда бот в drawdown-recovery,
     * итоговый размер умножается на этот множитель (например 0.5 = позиции ещё в 2 раза меньше).
     */
    var kellyDrawdownReduction: Double = 0.5

    /** Стоп-лосс по умолчанию в пунктах. 50 пунктов * 10 ₽ = 500 ₽ при 1 контракте. */
    var defaultStopLossPoints: Int = 50

    /** Тейк-профит по умолчанию в пунктах. R:R = 1:2. */
    var defaultTakeProfitPoints: Int = 100

    /** Если расстояние до ликвидации < X% от буфера маржи — срочное закрытие. */
    var minLiquidationDistancePercent: Double = 25.0

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
}
