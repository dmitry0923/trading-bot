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
}
