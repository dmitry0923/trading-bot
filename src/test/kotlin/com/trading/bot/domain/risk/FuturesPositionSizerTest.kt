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
        val commInstrument = InstrumentsConfig().apply {
            instruments = mutableListOf(
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
        // stopLossPoints=50, lossPerContract = 50 * 1.0 = 50 RUB, commission = 10 RUB
        // effectiveRiskPerContract = 60; riskAmount = 500; maxContractsByRisk = floor(500/60) = 8
        // marginBudget = 50000 * 30% = 15000; marginPerContract=1000; maxContractsByMargin = 15
        // final = min(8, 15, 1) = 1  → need to raise maxContractsPerPosition
        val riskConfig2 = RiskConfig().apply { maxContractsPerPosition = 100 }
        val sizer2 = FuturesPositionSizer(riskConfig2, commInstrument)
        val result = sizer2.calculateContracts(
            ticker = "CNYRUB_TOM",
            portfolioMoney = BigDecimal("50000"),
            stopLossPoints = 50,
            currentGo = BigDecimal("1000"),
        )
        assertEquals(8, result.quantity)
    }

    @Test
    fun `commission can reduce to zero`() {
        val commInstrument = InstrumentsConfig().apply {
            instruments = mutableListOf(
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
        // effectiveRiskPerContract = 50 + 100 = 150; riskAmount = 500; floor(500/150) = 3
        val result = sizer.calculateContracts(
            ticker = "CNYRUB_TOM",
            portfolioMoney = BigDecimal("50000"),
            stopLossPoints = 50,
            currentGo = BigDecimal("1000"),
        )
        assertEquals(3, result.quantity)
    }

    @Test
    fun `zero commission behaves same as before`() {
        val noCommInstrument = InstrumentsConfig().apply {
            instruments = mutableListOf(
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
        val result = sizer.calculateContracts(
            ticker = "Si",
            portfolioMoney = BigDecimal("50000"),
            stopLossPoints = 50,
            currentGo = BigDecimal("15000"),
        )
        assertEquals(1, result.quantity)
    }
}
