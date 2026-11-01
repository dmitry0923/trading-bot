package com.trading.bot.application.funding

import com.trading.bot.config.InstrumentsConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit-тест [ConfiguredFundingProvider]: CONFIG-ставка берётся из спецификации
 * инструмента, а для неизвестного тикера возвращается ноль (fail-open только для
 * SIM/backtest — в LIVE CONFIG не подставляется, см. `FundingSnapshotService`).
 */
class ConfiguredFundingProviderTest {
    private val instrumentsConfig =
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
                        fundingRubPerContractPerDay = BigDecimal("0.5"),
                    ),
                )
        }

    private val provider = ConfiguredFundingProvider(instrumentsConfig)

    @Test
    fun `configured instrument exposes its funding rate`() {
        assertTrue(provider.value("CNYRUBF") > BigDecimal.ZERO)
    }

    @Test
    fun `unknown ticker yields a zero config rate`(): Unit =
        runBlocking {
            assertEquals(0, BigDecimal.ZERO.compareTo(provider.value("ZZZZ")))

            val snapshot = provider.currentSnapshot("ZZZZ")

            assertEquals("ZZZZ", snapshot.ticker)
            assertEquals(0, BigDecimal.ZERO.compareTo(snapshot.valueRubPerContractPerClearing))
            assertEquals(FundingSource.CONFIG, snapshot.source)
        }
}
