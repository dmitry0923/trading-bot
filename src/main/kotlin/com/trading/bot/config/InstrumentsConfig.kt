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
 * RI — фьючерс на индекс РТС (MOEX FORTS):
 *   - срочный контракт, продаётся сериями (RIU6/RIZ6/RIH7/...), нужен ролл
 *   - MINSTEP = 10 (priceStep), STEPPRICE = 0.02 × курс USD ≈ 16.86 ₽ (priceStepCost)
 *   - pointValue = 16.86 / 10 = 1.686 ₽ на 1 пункт индекса; контракт ≈ 100 000 ₽ номинала
 *   - go = 22 000 ₽ (гарантийное обеспечение, ~x4 плечо при цене 100k)
 *
 * CNYRUBF — бессрочный фьючерс (юань/рубль, фортс):
 *   - вечный контракт (LASTDELDATE 2100) — не требует ролла на длинном горизонте
 *   - LOTVOLUME = 1000 CNY; MINSTEP = 0.001 (priceStep), STEPPRICE = 1.0 ₽ (priceStepCost)
 *   - контракт ≈ 12 800 ₽ (при цене 12.8) — дёшев, умещается в депозит 50k
 *   - ликвидность высокая (оборот > 70 млрд ₽/день); дневная волатильность ~1.9%
 *   - go = 850 ₽ (консервативная оценка гарантийного обеспечения)
 *   - max-contracts-per-position = 1 → сайзер откроет ровно 1 контракт
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
                ticker = "RI",
                type = "FUTURES",
                lotSize = 1,
                priceStep = BigDecimal("10.0"),
                priceStepCost = BigDecimal("16.86"),
                go = BigDecimal("22000"),
                leverage = BigDecimal("1.0"),
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
                ticker = "CNYRUBF",
                type = "FUTURES",
                lotSize = 1000,
                priceStep = BigDecimal("0.001"),
                priceStepCost = BigDecimal("1.0"),
                go = BigDecimal("850"),
                leverage = BigDecimal("1.0"),
                baseAsset = "CNY",
                quoteAsset = "RUB",
                brokerCommissionRub = BigDecimal("1.0"),
                exchangeFeeRub = BigDecimal("0.5"),
                slippageBps = BigDecimal("1.0"),
                fundingRubPerContractPerDay = BigDecimal("0.5"),
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
        require(instruments.isNotEmpty()) { "No trading instruments configured" }
        val duplicates =
            instruments
                .groupBy { it.ticker.uppercase() }
                .filterValues { it.size > 1 }
                .keys
        require(duplicates.isEmpty()) { "Duplicate instrument tickers: $duplicates" }
        instruments.forEach { spec ->
            require(spec.ticker.isNotBlank()) { "Instrument ticker must not be blank" }
            require(spec.type.uppercase() in setOf("STOCK", "FUTURES", "FX")) {
                "Instrument ${spec.ticker}: unknown type '${spec.type}', expected STOCK/FUTURES/FX"
            }
            require(spec.lotSize > 0) { "Instrument ${spec.ticker}: lotSize must be > 0, got ${spec.lotSize}" }
            require(spec.priceStep > BigDecimal.ZERO) { "Instrument ${spec.ticker}: priceStep must be > 0, got ${spec.priceStep}" }
            require(
                spec.priceStepCost > BigDecimal.ZERO,
            ) { "Instrument ${spec.ticker}: priceStepCost must be > 0, got ${spec.priceStepCost}" }
            require(spec.go >= BigDecimal.ZERO) { "Instrument ${spec.ticker}: go must be >= 0, got ${spec.go}" }
            require(spec.leverage > BigDecimal.ZERO) { "Instrument ${spec.ticker}: leverage must be > 0, got ${spec.leverage}" }
            spec.slPercent?.let {
                require(it > BigDecimal.ZERO) { "Instrument ${spec.ticker}: slPercent must be > 0, got $it" }
            }
            spec.tpPercent?.let {
                require(it > BigDecimal.ZERO) { "Instrument ${spec.ticker}: tpPercent must be > 0, got $it" }
            }
            spec.maxSpreadPercent?.let {
                require(it > BigDecimal.ZERO) { "Instrument ${spec.ticker}: maxSpreadPercent must be > 0, got $it" }
            }
            spec.maxGapPercent?.let {
                require(it > BigDecimal.ZERO) { "Instrument ${spec.ticker}: maxGapPercent must be > 0, got $it" }
            }
            spec.commissionRub?.let {
                require(it >= BigDecimal.ZERO) { "Instrument ${spec.ticker}: commissionRub must be >= 0, got $it" }
            }
            spec.brokerCommissionRub?.let {
                require(it >= BigDecimal.ZERO) { "Instrument ${spec.ticker}: brokerCommissionRub must be >= 0, got $it" }
            }
            spec.exchangeFeeRub?.let {
                require(it >= BigDecimal.ZERO) { "Instrument ${spec.ticker}: exchangeFeeRub must be >= 0, got $it" }
            }
            spec.slippageBps?.let {
                require(it >= BigDecimal.ZERO) { "Instrument ${spec.ticker}: slippageBps must be >= 0, got $it" }
            }
            spec.fundingRubPerContractPerDay?.let {
                require(it >= BigDecimal.ZERO) { "Instrument ${spec.ticker}: fundingRubPerContractPerDay must be >= 0, got $it" }
            }
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
        /**
         * СУММАРНАЯ (легаси) комиссия за лот/контракт за сторону в RUB — используется
         * как fallback, когда [brokerCommissionRub] не задан. Удерживается для
         * обратной совместимости (профили/тесты/не-фьючерсные инструменты).
         *
         * Ранее — provisional-оценка по тарифу Alor (CNYRUB_TOM ~12 500 ₽/лот):
         *   - "Профессионал" (0.04%): 5.00 RUB/side
         *   - "Валютный" (0.05%):     6.25 RUB/side
         *   - "Единый" (0.1%):        12.50 RUB/side
         *
         * Значения должны быть сверены с фактическим тарифом Alor ДО LIVE.
         */
        var commissionRub: BigDecimal? = null,
        /**
         * Брокерская комиссия за 1 контракт/лот за сторону (RUB). Первая компонента
         * сплита издержек (P1-аудит): [totalCommissionPerLotSide] = broker + exchange.
         * Provisional — сверяется по фактическому тарифу Alor (CNYRUBF ≈ 1 ₽/контракт).
         */
        var brokerCommissionRub: BigDecimal? = null,
        /**
         * Биржевой сбор МосБиржи (клиринговая комиссия) за 1 контракт/лот за сторону
         * (RUB). Вторая компонента сплита издержек. Provisional — сверяется по
         * фактическим выпискам (CNYRUBF ≈ 0.5 ₽/контракт).
         */
        var exchangeFeeRub: BigDecimal? = null,
        /**
         * Проскальзывание исполнения, базисных пунктов (1 bp = 1/10000 цены) на
         * сторону. Используется бэктест-симуляцией исполнения ([SlippageModel]).
         * null = legacy (тики для фьючерсов / 0.1% для акций).
         */
        var slippageBps: BigDecimal? = null,
        /**
         * Фьючерсный funding (CNYRUBF): RUB за 1 контракт за каждый клиринг (торговый
         * день) удержания позиции. null = funding не учитывается. Значение начисляется
         * только за клиринги, которые позиция пережила (см. [FundingCosts]).
         *
         * Provisional: сверяется по данным MOEX (для CNYRUBF требуется учёт лота
         * 1000 CNY) и фактическим выплатам на счёте.
         */
        var fundingRubPerContractPerDay: BigDecimal? = null,
    ) {
        /**
         * Суммарная комиссия за 1 лот/контракт за сторону (RUB): брокерская +
         * биржевой сбор; при отсутствии сплита — легаси [commissionRub]; 0, если
         * не задано ничего. Единый вход издержек для сайзинга (×2 = round-trip),
         * realised P&L (live) и бэктест-симуляции.
         */
        fun totalCommissionPerLotSide(): BigDecimal =
            brokerCommissionRub
                ?.let { broker -> broker.add(exchangeFeeRub ?: BigDecimal.ZERO) }
                ?: commissionRub
                ?: BigDecimal.ZERO

        /** Funding за 1 контракт за 1 начисление (клиринг); 0, если не задан. */
        fun fundingPerClearing(): BigDecimal = fundingRubPerContractPerDay ?: BigDecimal.ZERO

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
        fun notional(
            qty: Int,
            price: BigDecimal,
        ): BigDecimal = price.multiply(BigDecimal(qty)).multiply(BigDecimal(lotSize))

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
        val spec =
            find(ticker)
                ?: throw IllegalArgumentException("Unknown instrument for pointValue: $ticker")
        return spec.priceStepCost.divide(spec.priceStep, 6, RoundingMode.HALF_UP)
    }
}
