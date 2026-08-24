package com.trading.bot.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.math.BigDecimal

/**
 * Verifies that InstrumentsConfig is correctly bound from application.yml.
 * Catches drift between Kotlin defaults and YAML values.
 * Uses ApplicationContextRunner — no Docker/Testcontainers needed.
 */
class InstrumentsConfigYamlTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InstrumentsConfig::class.java, RiskConfig::class.java))
            .withPropertyValues(
                "trading.tickers[0]=CNYRUB_TOM",
                "instruments.instruments[0].ticker=CNYRUB_TOM",
                "instruments.instruments[0].type=FX",
                "instruments.instruments[0].lotSize=1000",
                "instruments.instruments[0].priceStep=0.0005",
                "instruments.instruments[0].priceStepCost=0.5",
                "instruments.instruments[0].go=0",
                "instruments.instruments[0].leverage=1.0",
                "instruments.instruments[0].baseAsset=CNY",
                "instruments.instruments[0].quoteAsset=RUB",
                "instruments.instruments[0].alorTicker=CNYRUB_TOM",
                "instruments.instruments[0].slPercent=0.5",
                "instruments.instruments[0].tpPercent=1.0",
                "instruments.instruments[0].maxSpreadPercent=2.0",
                "instruments.instruments[0].maxGapPercent=5.0",
                "instruments.instruments[0].commissionRub=10.0",
            )

    @Test
    fun `CNYRUB_TOM has production trading specification from YAML binding`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            val config = context.getBean(InstrumentsConfig::class.java)
            val spec = config.find("CNYRUB_TOM")

            assertThat(spec).isNotNull
            assertThat(spec!!.type).isEqualTo("FX")
            assertThat(spec.lotSize).isEqualTo(1000)
            assertThat(spec.priceStep).isEqualByComparingTo(BigDecimal("0.0005"))
            assertThat(spec.priceStepCost).isEqualByComparingTo(BigDecimal("0.5"))
            assertThat(spec.baseAsset).isEqualTo("CNY")
            assertThat(spec.quoteAsset).isEqualTo("RUB")
            assertThat(spec.effectiveTicker()).isEqualTo("CNYRUB_TOM")
            assertThat(spec.slPercent).isEqualByComparingTo(BigDecimal("0.5"))
            assertThat(spec.tpPercent).isEqualByComparingTo(BigDecimal("1.0"))
            assertThat(spec.maxSpreadPercent).isEqualByComparingTo(BigDecimal("2.0"))
            assertThat(spec.maxGapPercent).isEqualByComparingTo(BigDecimal("5.0"))
            assertThat(spec.commissionRub).isEqualByComparingTo(BigDecimal("10.0"))
        }
    }

    @Test
    fun `CNYRUB_TOM effective SL TP use per-instrument values over global defaults`() {
        contextRunner.run { context ->
            val config = context.getBean(InstrumentsConfig::class.java)
            val riskConfig = context.getBean(RiskConfig::class.java)
            val spec = config.find("CNYRUB_TOM")!!

            assertThat(spec.effectiveSlPercent(riskConfig.defaultStopLossPercent))
                .isEqualByComparingTo(BigDecimal("0.5"))
            assertThat(spec.effectiveTpPercent(riskConfig.defaultTakeProfitPercent))
                .isEqualByComparingTo(BigDecimal("1.0"))
            assertThat(spec.effectiveMaxSpreadPercent(riskConfig.maxSpreadPercent))
                .isEqualByComparingTo(BigDecimal("2.0"))
            assertThat(spec.effectiveMaxGapPercent(riskConfig.maxGapPercent))
                .isEqualByComparingTo(BigDecimal("5.0"))
        }
    }

    @Test
    fun `notional for CNYRUB_TOM equals price times lots times lotSize`() {
        contextRunner.run { context ->
            val config = context.getBean(InstrumentsConfig::class.java)
            val spec = config.find("CNYRUB_TOM")!!
            assertThat(spec.notional(1, BigDecimal("12.5"))).isEqualByComparingTo(BigDecimal("12500"))
        }
    }

    @Test
    fun `RiskConfig has BigDecimal defaults for risk percentages`() {
        contextRunner.run { context ->
            val riskConfig = context.getBean(RiskConfig::class.java)
            assertThat(riskConfig.defaultStopLossPercent).isEqualByComparingTo(BigDecimal("2.0"))
            assertThat(riskConfig.defaultTakeProfitPercent).isEqualByComparingTo(BigDecimal("4.0"))
            assertThat(riskConfig.maxSpreadPercent).isEqualByComparingTo(BigDecimal("1.0"))
            assertThat(riskConfig.maxGapPercent).isEqualByComparingTo(BigDecimal("3.0"))
        }
    }
}
