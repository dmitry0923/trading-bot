package com.trading.bot.domain.risk

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.PositionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Проверка позиционирования по спецификации:
 *   deposit = 50 000 ₽, leverage = 2.0, GO = 15 000 ₽, стоп = 50 пунктов
 *   → qty = 1, margin = 7 500 ₽, risk = 500 ₽.
 */
class FuturesPositionSizerTest {
    private val riskConfig = RiskConfig()
    private val leverageConfig = LeverageConfig()
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

    private val sizer = FuturesPositionSizer(leverageConfig, riskConfig, instrumentsConfig)

    @Test
    fun `qty is 1 for 50k deposit stop 50 points go 15000`() {
        val result =
            sizer.calculateSiContracts(
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
            )

        assertEquals(1, result.quantity)
        assertEquals(0, BigDecimal("7500").compareTo(result.marginRequired))
        assertEquals(0, BigDecimal("500").compareTo(result.riskAmount))
        assertNull(result.reason)
    }

    @Test
    fun `qty never exceeds max contracts per position`() {
        val result =
            sizer.calculateSiContracts(
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
            sizer.calculateSiContracts(
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 1000,
                currentGo = BigDecimal("15000"),
            )

        assertEquals(0, result.quantity)
        assertEquals("ZERO_RISK_SIZE", result.reason)
    }

    @Test
    fun `zero qty when margin insufficient`() {
        // риск 500 ₽ позволяет 1 контракт, но maxMarginUsagePercent = 1% → бюджет 500 ₽ < 7500 ₽ → 0
        val tightMarginConfig = RiskConfig().apply { maxMarginUsagePercent = 1.0 }
        val tightSizer = FuturesPositionSizer(leverageConfig, tightMarginConfig, instrumentsConfig)

        val result =
            tightSizer.calculateSiContracts(
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
            sizer.calculateSiContracts(
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
                entryPrice = BigDecimal("92000"),
                direction = PositionDirection.LONG,
            )

        // buffer = (7500 * 2) / 1000 = 15 ₽ → liq = 92000 - 15 = 91985
        val liq = requireNotNull(result.liquidationPrice)
        assertEquals(0, BigDecimal("91985").compareTo(liq))
    }

    @Test
    fun `liquidation price for short entry`() {
        val result =
            sizer.calculateSiContracts(
                portfolioMoney = BigDecimal("50000"),
                stopLossPoints = 50,
                currentGo = BigDecimal("15000"),
                entryPrice = BigDecimal("92000"),
                direction = PositionDirection.SHORT,
            )

        val liq = requireNotNull(result.liquidationPrice)
        assertEquals(0, BigDecimal("92015").compareTo(liq))
    }
}
