package com.trading.bot.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Справочник торговых инструментов (prefix = "instruments").
 *
 * Si — фьючерс (доллар/рубль):
 *   - priceStep = 0.01 (1 копейка)
 *   - priceStepCost = 10.0 (1 пункт = 10 ₽; контракт = 1000 USD)
 *   - go = 15 000 ₽ (гарантийное обеспечение)
 *   - leverage берётся из LeverageConfig (placeholder `${leverage.user-leverage}`)
 *
 * CNYRUB_TOM — кросс-курс юань/рубль MOEX (FX spot):
 *   - lotSize = 1 000 юаней (CETS)
 *   - priceStep = 0.0005 (0.05 копейки)
 *   - priceStepCost = 0.5 ₽
 *
 * Остальные тикеры — акции MOEX (SBER, GAZP, LKOH, ...). Их futures-поля
 * (go, leverage) не используются: для акций применяется Kelly-сайзинг.
 *
 * Производные величины:
 *   pointValue = priceStepCost / priceStep  // Si: 10 / 0.01 = 1000 ₽ на 1.0 цены
 *   marginPerContract = go / leverage       // 15000 / 2 = 7500 ₽
 */
@Component
@ConfigurationProperties(prefix = "instruments")
class InstrumentsConfig {
    var instruments: List<InstrumentSpec> =
        mutableListOf(
            InstrumentSpec(
                ticker = "Si",
                type = "FUTURES",
                lotSize = 1,
                priceStep = BigDecimal("0.01"),
                priceStepCost = BigDecimal("10.0"),
                go = BigDecimal("15000"),
                leverage = BigDecimal("2.0"),
                baseAsset = "USD",
            ),
            InstrumentSpec(
                ticker = "SBER",
                type = "STOCK",
                lotSize = 10,
                priceStep = BigDecimal("0.01"),
                priceStepCost = BigDecimal("0.1"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "RUB",
            ),
            InstrumentSpec(
                ticker = "GAZP",
                type = "STOCK",
                lotSize = 10,
                priceStep = BigDecimal("0.05"),
                priceStepCost = BigDecimal("0.5"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "RUB",
            ),
            InstrumentSpec(
                ticker = "LKOH",
                type = "STOCK",
                lotSize = 1,
                priceStep = BigDecimal("1.0"),
                priceStepCost = BigDecimal("1.0"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "RUB",
            ),
            InstrumentSpec(
                ticker = "VTBR",
                type = "STOCK",
                lotSize = 1000,
                priceStep = BigDecimal("0.0001"),
                priceStepCost = BigDecimal("0.1"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "RUB",
            ),
            InstrumentSpec(
                ticker = "ROSN",
                type = "STOCK",
                lotSize = 1,
                priceStep = BigDecimal("0.05"),
                priceStepCost = BigDecimal("0.05"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "RUB",
            ),
            InstrumentSpec(
                ticker = "NVTK",
                type = "STOCK",
                lotSize = 1,
                priceStep = BigDecimal("1.0"),
                priceStepCost = BigDecimal("1.0"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "RUB",
            ),
            InstrumentSpec(
                ticker = "PLZL",
                type = "STOCK",
                lotSize = 1,
                priceStep = BigDecimal("1.0"),
                priceStepCost = BigDecimal("1.0"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "RUB",
            ),
            InstrumentSpec(
                ticker = "MGNT",
                type = "STOCK",
                lotSize = 1,
                priceStep = BigDecimal("1.0"),
                priceStepCost = BigDecimal("1.0"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "RUB",
            ),
            InstrumentSpec(
                ticker = "TATN",
                type = "STOCK",
                lotSize = 1,
                priceStep = BigDecimal("0.05"),
                priceStepCost = BigDecimal("0.05"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "RUB",
            ),
            InstrumentSpec(
                ticker = "CNYRUB_TOM",
                type = "FX",
                lotSize = 1000,
                priceStep = BigDecimal("0.0005"),
                priceStepCost = BigDecimal("0.5"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal("1.0"),
                baseAsset = "CNY",
                quoteAsset = "RUB",
                alorTicker = "CNYRUB_TOM",
                slPercent = BigDecimal("0.5"),
                tpPercent = BigDecimal("1.0"),
                maxSpreadPercent = BigDecimal("2.0"),
                maxGapPercent = BigDecimal("5.0"),
                commissionRub = BigDecimal("10.0"),
            ),
        )

    @PostConstruct
    fun validateSpecs() {
        instruments.forEach { spec ->
            require(spec.ticker.isNotBlank()) { "Instrument ticker must not be blank" }
            require(spec.lotSize > 0) { "Instrument ${spec.ticker}: lotSize must be > 0, got ${spec.lotSize}" }
            require(spec.priceStep > BigDecimal.ZERO) { "Instrument ${spec.ticker}: priceStep must be > 0, got ${spec.priceStep}" }
            if (spec.slPercent != null) require(spec.slPercent!! > BigDecimal.ZERO) { "Instrument ${spec.ticker}: slPercent must be > 0, got ${spec.slPercent}" }
            if (spec.tpPercent != null) require(spec.tpPercent!! > BigDecimal.ZERO) { "Instrument ${spec.ticker}: tpPercent must be > 0, got ${spec.tpPercent}" }
            if (spec.commissionRub != null) require(spec.commissionRub!! >= BigDecimal.ZERO) { "Instrument ${spec.ticker}: commissionRub must be >= 0, got ${spec.commissionRub}" }
        }
    }

    data class InstrumentSpec(
        var ticker: String = "Si",
        var type: String = "FUTURES",
        var lotSize: Int = 1,
        var priceStep: BigDecimal = BigDecimal("0.01"),
        var priceStepCost: BigDecimal = BigDecimal("10.0"),
        var go: BigDecimal = BigDecimal("15000"),
        var leverage: BigDecimal = BigDecimal("2.0"),
        var baseAsset: String = "USD",
        /** Алёрный тикер (если отличается от внутреннего ticker). */
        var alorTicker: String? = null,
        /** Валюта котировки (RUB, USD, ...) — для FX нужна для расчёта notional. */
        var quoteAsset: String = "RUB",
        /** Per-instrument SL% — overrides RiskConfig.defaultStopLossPercent when non-null. */
        var slPercent: BigDecimal? = null,
        /** Per-instrument TP% — overrides RiskConfig.defaultTakeProfitPercent when non-null. */
        var tpPercent: BigDecimal? = null,
        /** Per-instrument max spread % — overrides RiskConfig.maxSpreadPercent when non-null. */
        var maxSpreadPercent: BigDecimal? = null,
        /** Per-instrument max gap % — overrides RiskConfig.maxGapPercent when non-null. */
        var maxGapPercent: BigDecimal? = null,
        /** Commission per lot per side in RUB. Used by risk sizing (×2 for round-trip) and backtest. */
        var commissionRub: BigDecimal? = null,
    ) {
        /** Effective SL%: per-instrument override or global default. */
        fun effectiveSlPercent(globalDefault: BigDecimal): BigDecimal = slPercent ?: globalDefault
        /** Effective TP%: per-instrument override or global default. */
        fun effectiveTpPercent(globalDefault: BigDecimal): BigDecimal = tpPercent ?: globalDefault
        /** Effective max spread %: per-instrument override or global default. */
        fun effectiveMaxSpreadPercent(globalDefault: BigDecimal): BigDecimal = maxSpreadPercent ?: globalDefault
        /** Effective max gap %: per-instrument override or global default. */
        fun effectiveMaxGapPercent(globalDefault: BigDecimal): BigDecimal = maxGapPercent ?: globalDefault

        /**
         * Notional в котировочной валюте (RUB для FX).
         * qty — число лотов; price — цена за единицу базового актива.
         * notional = price × qty × lotSize.
         *
         * Примеры:
         *   CNYRUB_TOM: 1 лот × 1000 CNY × 12.5 RUB/CNY = 12 500 RUB
         *   SBER:       10 лотов × 300 RUB × 10 shares/лот = 30 000 RUB
         *
         * Формула одинакова для STOCK и FX — qty всегда число лотов.
         */
        fun notional(qty: Int, price: BigDecimal): BigDecimal =
            price.multiply(BigDecimal(qty)).multiply(BigDecimal(lotSize))

        /** Alor-тикер для API-вызовов (alorTicker или ticker). */
        fun effectiveTicker(): String = alorTicker ?: ticker
    }

    fun find(ticker: String): InstrumentSpec? = instruments.firstOrNull { it.ticker.equals(ticker, ignoreCase = true) }

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
