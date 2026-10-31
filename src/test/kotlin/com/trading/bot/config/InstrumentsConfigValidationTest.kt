package com.trading.bot.config

import com.trading.bot.config.InstrumentsConfig.InstrumentSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [InstrumentsConfig] — валидация справочника (fail-fast на битых спеках) и
 * производные величины (комиссия, pointValue, поиск тикера). Ошибка в спеке
 * перпетуала уже дважды искажала комиссию/funding на порядки, поэтому каждая
 * проверка фиксируется отдельным кейсом.
 */
class InstrumentsConfigValidationTest {
    private fun spec(
        ticker: String = "TEST",
        type: String = "STOCK",
    ): InstrumentSpec =
        InstrumentSpec(
            ticker = ticker,
            type = type,
            lotSize = 1,
            priceStep = BigDecimal("0.01"),
            priceStepCost = BigDecimal("1.0"),
            go = BigDecimal.ZERO,
            leverage = BigDecimal.ONE,
        )

    private fun config(vararg specs: InstrumentSpec): InstrumentsConfig =
        InstrumentsConfig().apply {
            instruments = specs.toList()
        }

    private fun assertRejected(
        spec: InstrumentSpec,
        messagePart: String,
    ) {
        val ex = assertThrows(IllegalArgumentException::class.java) { config(spec).validateSpecs() }
        assertTrue(ex.message!!.contains(messagePart), "unexpected message: ${ex.message}")
    }

    @Test
    fun `default production catalogue passes validation`() {
        InstrumentsConfig().validateSpecs()
    }

    @Test
    fun `empty catalogue is rejected`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { config().validateSpecs() }
        assertTrue(ex.message!!.contains("No trading instruments"))
    }

    @Test
    fun `duplicate tickers are rejected case insensitively`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                config(spec("SBER"), spec("sber")).validateSpecs()
            }
        assertTrue(ex.message!!.contains("Duplicate instrument tickers"))
    }

    @Test
    fun `blank ticker is rejected`() {
        assertRejected(spec(ticker = "  "), "ticker must not be blank")
    }

    @Test
    fun `unknown instrument type is rejected`() {
        assertRejected(spec(type = "OPTION"), "unknown type")
    }

    @Test
    fun `non positive lot size is rejected`() {
        assertRejected(spec().apply { lotSize = 0 }, "lotSize must be > 0")
    }

    @Test
    fun `non positive price step is rejected`() {
        assertRejected(spec().apply { priceStep = BigDecimal.ZERO }, "priceStep must be > 0")
    }

    @Test
    fun `non positive price step cost is rejected`() {
        assertRejected(spec().apply { priceStepCost = BigDecimal.ZERO }, "priceStepCost must be > 0")
    }

    @Test
    fun `negative guarantee is rejected`() {
        assertRejected(spec().apply { go = BigDecimal("-1") }, "go must be >= 0")
    }

    @Test
    fun `non positive leverage is rejected`() {
        assertRejected(spec().apply { leverage = BigDecimal.ZERO }, "leverage must be > 0")
    }

    @Test
    fun `non positive per instrument percents are rejected`() {
        assertRejected(spec().apply { slPercent = BigDecimal.ZERO }, "slPercent must be > 0")
        assertRejected(spec().apply { tpPercent = BigDecimal.ZERO }, "tpPercent must be > 0")
        assertRejected(spec().apply { maxSpreadPercent = BigDecimal.ZERO }, "maxSpreadPercent must be > 0")
        assertRejected(spec().apply { maxGapPercent = BigDecimal.ZERO }, "maxGapPercent must be > 0")
    }

    @Test
    fun `negative commission and fees are rejected`() {
        assertRejected(spec().apply { commissionRub = BigDecimal("-1") }, "commissionRub must be >= 0")
        assertRejected(spec().apply { brokerCommissionRub = BigDecimal("-1") }, "brokerCommissionRub must be >= 0")
        assertRejected(spec().apply { exchangeFeeRub = BigDecimal("-1") }, "exchangeFeeRub must be >= 0")
    }

    @Test
    fun `negative slippage and funding are rejected`() {
        assertRejected(spec().apply { slippageBps = BigDecimal("-1") }, "slippageBps must be >= 0")
        assertRejected(
            spec().apply { fundingRubPerContractPerDay = BigDecimal("-1") },
            "fundingRubPerContractPerDay must be >= 0",
        )
    }

    @Test
    fun `total commission prefers broker plus exchange split`() {
        val split =
            spec().apply {
                brokerCommissionRub = BigDecimal("1.0")
                exchangeFeeRub = BigDecimal("0.5")
            }

        assertEquals(0, BigDecimal("1.5").compareTo(split.totalCommissionPerLotSide()))
    }

    @Test
    fun `total commission falls back through broker only, legacy and zero`() {
        val brokerOnly = spec().apply { brokerCommissionRub = BigDecimal("2.0") }
        assertEquals(0, BigDecimal("2.0").compareTo(brokerOnly.totalCommissionPerLotSide()))

        val legacy = spec().apply { commissionRub = BigDecimal("10.0") }
        assertEquals(0, BigDecimal("10.0").compareTo(legacy.totalCommissionPerLotSide()))

        val none = spec()
        assertEquals(0, BigDecimal.ZERO.compareTo(none.totalCommissionPerLotSide()))
        assertEquals(0, BigDecimal.ZERO.compareTo(none.fundingPerClearing()))
    }

    @Test
    fun `effective overrides fall back to global defaults`() {
        val plain = spec()

        assertEquals(0, BigDecimal("2").compareTo(plain.effectiveSlPercent(BigDecimal("2"))))
        assertEquals(0, BigDecimal("4").compareTo(plain.effectiveTpPercent(BigDecimal("4"))))
        assertEquals(0, BigDecimal("1").compareTo(plain.effectiveMaxSpreadPercent(BigDecimal("1"))))
        assertEquals(0, BigDecimal("3").compareTo(plain.effectiveMaxGapPercent(BigDecimal("3"))))
    }

    @Test
    fun `point value and futures lookup use the catalogue`() {
        val config =
            config(
                spec("Si", type = "FUTURES").apply {
                    priceStepCost = BigDecimal("10")
                    priceStep = BigDecimal("0.01")
                },
            )

        assertEquals(0, BigDecimal("1000").compareTo(config.pointValue("si")))
        assertTrue(config.isFutures("Si"))
        assertFalse(config.isFutures("SBER"))
        assertEquals("Si", config.find("si")?.ticker)
    }

    @Test
    fun `point value of an unknown ticker fails closed`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { config(spec("SBER")).pointValue("UNKNOWN") }
        assertTrue(ex.message!!.contains("Unknown instrument for pointValue"))
    }
}
