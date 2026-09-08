package com.trading.bot.domain.risk

import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.PositionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Проверка позиционирования по спецификации:
 *   deposit = 50 000 ₽, GO = 15 000 ₽, стоп = 50 пунктов
 *   → qty = 1, margin = 15 000 ₽ (полное GO биржи), risk = 500 ₽.
 */
class FuturesPositionSizerTest {
    private val riskConfig = RiskConfig()
    private val instrumentsConfig =
        InstrumentsConfig().apply {
            instruments =
                mutableListOf(
                    InstrumentsConfig.InstrumentSpec(
                        ticker = "Si",
                        type = "FUTURES",
                        lotSize = 1,
                        priceStep = BigDecimal("0.01"),
                        priceStepCost = BigDecimal("10.0"),
                        go = BigDecimal("15000"),
                        leverage = BigDecimal("2.0"),
                        baseAsset = "USD",
                    ),
                )
        }

    private val sizer = FuturesPositionSizer(riskConfig, instrumentsConfig)

    @Test
    fun `qty is 1 for 50k deposit stop 50 points go 15000`() {
        val result =
            sizer.calculateContracts(
                ticker = "Si",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
            )

        assertEquals(1, result.quantity)
        // маржа = полное GO биржи (плечо не уменьшает требуемую брокером маржу)
        assertEquals(0, BigDecimal("15000").compareTo(result.marginRequired))
        assertEquals(0, BigDecimal("500").compareTo(result.riskAmount))
        assertNull(result.reason)
    }

    @Test
    fun `qty never exceeds max contracts per position`() {
        val result =
            sizer.calculateContracts(
                ticker = "Si",
                portfolioMoney = BigDecimal("200000"),
                stopLossPoints = 10,
                currentGo = BigDecimal("15000"),
            )

        assertEquals(1, result.quantity)
    }

    @Test
    fun `zero qty when risk per trade too low`() {
        // стоп 1000 пунктов = 10 000 ₽ > риск 500 ₽ → 0 контрактов
        val result =
            sizer.calculateContracts(
                ticker = "Si",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 1000,
                currentGo = BigDecimal("15000"),
            )

        assertEquals(0, result.quantity)
        assertEquals("ZERO_RISK_SIZE", result.reason)
    }

    @Test
    fun `zero qty when margin insufficient`() {
        // риск 500 ₽ позволяет 1 контракт, но maxMarginUsagePercent = 1% → бюджет 500 ₽ < 15000 ₽ → 0
        val tightMarginConfig = RiskConfig().apply { maxMarginUsagePercent = 1.0 }
        val tightSizer = FuturesPositionSizer(tightMarginConfig, instrumentsConfig)

        val result =
            tightSizer.calculateContracts(
                ticker = "Si",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
            )

        assertEquals(0, result.quantity)
        assertEquals("INSUFFICIENT_MARGIN", result.reason)
    }

    @Test
    fun `liquidation price for long entry`() {
        val result =
            sizer.calculateContracts(
                ticker = "Si",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
                entryPrice = BigDecimal("92000"),
                direction = PositionDirection.LONG,
            )

        // buffer = GO / pointValue = 15000 / 1000 = 15 ₽ → liq = 92000 - 15 = 91985
        val liq = requireNotNull(result.liquidationPrice)
        assertEquals(0, BigDecimal("91985").compareTo(liq))
    }

    @Test
    fun `liquidation price for short entry`() {
        val result =
            sizer.calculateContracts(
                ticker = "Si",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
                entryPrice = BigDecimal("92000"),
                direction = PositionDirection.SHORT,
            )

        val liq = requireNotNull(result.liquidationPrice)
        assertEquals(0, BigDecimal("92015").compareTo(liq))
    }

    @Test
    fun `liquidation buffer is estimated from full GO only`() {
        // Дистанция до ликвидации = GO / pointValue; плечо в расчёте не участвует
        // (раньше формула (go/leverage)*leverage/pointValue сокращала leverage).
        val liq =
            sizer
                .calculateContracts(
                    "Si",
                    BigDecimal("50000"),
                    50,
                    BigDecimal("15000"),
                    BigDecimal("92000"),
                    PositionDirection.LONG,
                ).liquidationPrice
        requireNotNull(liq)
        assertEquals(0, BigDecimal("91985").compareTo(liq)) // буфер 15 = 15000 / 1000

        // Удвоенный GO → удвоенный буфер: оценка масштабируется вместе с маржой.
        // Депозит 100000, чтобы маржинальный бюджет 30% (30000) покрывал увеличенный GO.
        val liqBigGo =
            sizer
                .calculateContracts(
                    "Si",
                    BigDecimal("100000"),
                    50,
                    BigDecimal("30000"),
                    BigDecimal("92000"),
                    PositionDirection.LONG,
                ).liquidationPrice
        requireNotNull(liqBigGo)
        assertEquals(0, BigDecimal("91970").compareTo(liqBigGo)) // буфер 30
    }

    @Test
    fun `commission reduces max contracts by risk`() {
        val commInstrument =
            InstrumentsConfig().apply {
                instruments =
                    mutableListOf(
                        InstrumentsConfig.InstrumentSpec(
                            ticker = "CNYRUB_TOM",
                            type = "STOCK",
                            lotSize = 10000,
                            priceStep = BigDecimal("0.0001"),
                            priceStepCost = BigDecimal("1.0"),
                            go = BigDecimal("1000"),
                            leverage = BigDecimal("1.0"),
                            baseAsset = "CNY",
                            commissionRub = BigDecimal("10.0"),
                        ),
                    )
            }
        // stopLossPoints=50, lossPerContract = 50 * 1.0 = 50 RUB, commission = 10 RUB (2x = 20)
        // effectiveRiskPerContract = 70; riskAmount = 500; maxContractsByRisk = floor(500/70) = 7
        // marginBudget = 50000 * 30% = 15000; marginPerContract=1000; maxContractsByMargin = 15
        // final = min(7, 15, 1) = 1  → need to raise maxContractsPerPosition
        val riskConfig2 = RiskConfig().apply { maxContractsPerPosition = 100 }
        val sizer2 = FuturesPositionSizer(riskConfig2, commInstrument)
        val result =
            sizer2.calculateContracts(
                ticker = "CNYRUB_TOM",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("1000"),
            )
        assertEquals(7, result.quantity)
    }

    @Test
    fun `commission can reduce to zero`() {
        val commInstrument =
            InstrumentsConfig().apply {
                instruments =
                    mutableListOf(
                        InstrumentsConfig.InstrumentSpec(
                            ticker = "CNYRUB_TOM",
                            type = "STOCK",
                            lotSize = 10000,
                            priceStep = BigDecimal("0.0001"),
                            priceStepCost = BigDecimal("1.0"),
                            go = BigDecimal("1000"),
                            leverage = BigDecimal("1.0"),
                            baseAsset = "CNY",
                            commissionRub = BigDecimal("100.0"),
                        ),
                    )
            }
        val riskConfig2 = RiskConfig().apply { maxContractsPerPosition = 100 }
        val sizer = FuturesPositionSizer(riskConfig2, commInstrument)
        // effectiveRiskPerContract = 50 + 100*2 = 250; riskAmount = 500; floor(500/250) = 2
        val result =
            sizer.calculateContracts(
                ticker = "CNYRUB_TOM",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("1000"),
            )
        assertEquals(2, result.quantity)
    }

    @Test
    fun `zero commission behaves same as before`() {
        val noCommInstrument =
            InstrumentsConfig().apply {
                instruments =
                    mutableListOf(
                        InstrumentsConfig.InstrumentSpec(
                            ticker = "Si",
                            type = "FUTURES",
                            lotSize = 1,
                            priceStep = BigDecimal("0.01"),
                            priceStepCost = BigDecimal("10.0"),
                            go = BigDecimal("15000"),
                            leverage = BigDecimal("2.0"),
                            baseAsset = "USD",
                            commissionRub = null,
                        ),
                    )
            }
        val sizer = FuturesPositionSizer(riskConfig, noCommInstrument)
        val result =
            sizer.calculateContracts(
                ticker = "Si",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
            )
        assertEquals(1, result.quantity)
    }

    @Test
    fun `risk per trade override scales quantity`() {
        // дефолтный риск 1% = 500 ₽; стоп 50 пунктов = 500 ₽/контракт → qty=1
        val baseline =
            sizer.calculateContracts(
                ticker = "Si",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
                entryPrice = BigDecimal("70"),
                direction = PositionDirection.LONG,
            )
        assertEquals(1, baseline.quantity)

        // риск 3% = 1 500 ₽; тот же стоп → 3 контракта (лимиты: риск, маржа 30k/15k=2, кап=50)
        val result =
            sizer.calculateContracts(
                ticker = "Si",
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
                entryPrice = BigDecimal("70"),
                direction = PositionDirection.LONG,
                riskPerTradePercent = 3.0,
                maxContractsPerPosition = 50,
            )
        assertEquals(2, result.quantity)
        assertEquals(0, BigDecimal("1500").compareTo(result.riskAmount))
    }

    @Test
    fun `slippage reduces max contracts by risk`() {
        // P1-5: slippage (вход+выход) включается в риск-бюджет. CNYRUBF: pointValue 1000,
        // stop 150 пунктов = 150 ₽, slippage 10 bps = 0.1% × notional 12 800 ₽ = 12.8 ₽/сторона.
        // Без slippage: 500/150 = 3; со slippage: 500/(150 + 12.8×2) = floor(500/175.6) = 2.
        val noSlipInstrument =
            InstrumentsConfig().apply {
                instruments =
                    mutableListOf(
                        InstrumentsConfig.InstrumentSpec(
                            ticker = "CNYRUBF",
                            type = "FUTURES",
                            lotSize = 1000,
                            priceStep = BigDecimal("0.001"),
                            priceStepCost = BigDecimal("1.0"),
                            go = BigDecimal("850"),
                            leverage = BigDecimal("1.0"),
                            baseAsset = "CNY",
                        ),
                    )
            }
        val slipInstrument =
            InstrumentsConfig().apply {
                instruments =
                    mutableListOf(
                        noSlipInstrument.find("CNYRUBF")!!.copy(slippageBps = BigDecimal("10.0")),
                    )
            }
        val riskConfig2 = RiskConfig().apply { maxContractsPerPosition = 100 }
        val without =
            FuturesPositionSizer(riskConfig2, noSlipInstrument).calculateContracts(
                "CNYRUBF",
                BigDecimal("50000"),
                150,
                BigDecimal("850"),
                BigDecimal("12.80"),
                PositionDirection.LONG,
                1.0,
                100,
            )
        assertEquals(3, without.quantity)

        val withSlippage =
            FuturesPositionSizer(riskConfig2, slipInstrument).calculateContracts(
                "CNYRUBF",
                BigDecimal("50000"),
                150,
                BigDecimal("850"),
                BigDecimal("12.80"),
                PositionDirection.LONG,
                1.0,
                100,
            )
        assertEquals(2, withSlippage.quantity)
    }

    @Test
    fun `slippage is floored at one tick value`() {
        // P1-5: floor slippage = 1 тик контракта (точность не дешевле шага цены),
        // pointValue для CNYRUBF = 1000 → тик = 0.001 × 1000 = 1 ₽/сторона.
        // stop 500 пунктов = 500 ₽/контракт; риск 500 ₽.
        // С точным (дешёвым) slippage 0.0001 bps ≈ 0 ₽ → 1 контракт (риск 500/500).
        // С floor в 1 тик × 2 = 2 ₽ → 500/502 → floor = 0 контрактов (ZERO_RISK_SIZE):
        // тик-ограничение реально влияет на решение у границы.
        val instrument =
            InstrumentsConfig().apply {
                instruments =
                    mutableListOf(
                        InstrumentsConfig.InstrumentSpec(
                            ticker = "CNYRUBF",
                            type = "FUTURES",
                            lotSize = 1000,
                            priceStep = BigDecimal("0.001"),
                            priceStepCost = BigDecimal("1.0"),
                            go = BigDecimal("850"),
                            leverage = BigDecimal("1.0"),
                            baseAsset = "CNY",
                            slippageBps = BigDecimal("0.0001"),
                        ),
                    )
            }
        val riskConfig2 = RiskConfig().apply { maxContractsPerPosition = 100 }
        val result =
            FuturesPositionSizer(riskConfig2, instrument).calculateContracts(
                "CNYRUBF",
                BigDecimal("50000"),
                500,
                BigDecimal("850"),
                BigDecimal("12.80"),
                PositionDirection.LONG,
                1.0,
                100,
            )

        assertEquals(0, result.quantity)
    }
}
