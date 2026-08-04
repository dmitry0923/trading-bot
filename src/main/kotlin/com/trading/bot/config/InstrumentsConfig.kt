package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Справочник торговых инструментов (prefix = "instruments").
 *
 * Для бота валиден только фьючерс Si (доллар/рубль):
 *   - priceStep = 0.01 (1 копейка)
 *   - priceStepCost = 10.0 (1 пункт = 10 ₽; контракт = 1000 USD)
 *   - go = 15 000 ₽ (гарантийное обеспечение)
 *   - leverage берётся из LeverageConfig (placeholder `${leverage.user-leverage}`)
 *
 * Производные величины:
 *   pointValue = priceStepCost / priceStep  // 10 / 0.01 = 1000 ₽ на 1.0 цены
 *   marginPerContract = go / leverage       // 15000 / 2 = 7500 ₽
 */
@Component
@ConfigurationProperties(prefix = "instruments")
class InstrumentsConfig {

    var instruments: List<InstrumentSpec> = mutableListOf(InstrumentSpec())

    data class InstrumentSpec(
        var ticker: String = "Si",
        var type: String = "FUTURES",
        var lotSize: Int = 1,
        var priceStep: BigDecimal = BigDecimal("0.01"),
        var priceStepCost: BigDecimal = BigDecimal("10.0"),
        var go: BigDecimal = BigDecimal("15000"),
        var leverage: BigDecimal = BigDecimal("2.0"),
        var baseAsset: String = "USD"
    )

    fun find(ticker: String): InstrumentSpec? =
        instruments.firstOrNull { it.ticker.equals(ticker, ignoreCase = true) }

    fun isFutures(ticker: String): Boolean = find(ticker)?.type == "FUTURES"

    /**
     * Стоимость 1.0 единицы цены в рублях (priceStepCost / priceStep).
     * Для Si: 10 / 0.01 = 1000 ₽ — это размер контракта (1000 USD).
     */
    fun pointValue(ticker: String): BigDecimal {
        val spec = find(ticker) ?: return BigDecimal("1000")
        return spec.priceStepCost.divide(spec.priceStep, 6, RoundingMode.HALF_UP)
    }
}
