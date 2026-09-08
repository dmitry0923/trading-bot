package com.trading.bot.application.funding

import com.trading.bot.config.FundingConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.TradingConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * P0-аудит (funding): LIVE-источник MOEX с TTL, при недоступности MOEX — provisional
 * CONFIG fallback с метрикой (`funding.live.provider_unavailable`), при устаревшем
 * MOEX-снапшоте — CONFIG fallback с метрикой (`funding.live.snapshot_stale_config_fallback`).
 * SIM/backtest — CONFIG напрямую (фиксированное значение корректно для симуляции).
 */
class FundingSnapshotServiceTest {
    @Test
    fun `live uses MOEX snapshot value converted to RUB per contract per clearing`() =
        runBlocking {
            val configValue = BigDecimal("0.5")
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            Mockito
                .`when`(
                    moex.currentSnapshot("CNYRUBF"),
                ).thenReturn(
                    FundingSnapshot(
                        ticker = "CNYRUBF",
                        rawValue = BigDecimal("0.00279"),
                        unit = FundingUnit.RAW_UNKNOWN,
                        valueRubPerContractPerClearing = BigDecimal("2.79"),
                        source = FundingSource.MOEX,
                        timestamp = LocalDateTime.now(),
                    ),
                )
            val registry = SimpleMeterRegistry()
            val service = service(mode = "LIVE", moex = moex, configValue = configValue, registry = registry)

            service.refresh("CNYRUBF")

            assertEquals(0, BigDecimal("2.79").compareTo(service.value("CNYRUBF")))
            assertEquals(
                0.0,
                registry.counter("funding.live.provider_unavailable", "ticker", "CNYRUBF").count(),
            )
        }

    @Test
    fun `live falls back to config with metric when MOEX unavailable`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            Mockito.`when`(moex.currentSnapshot("CNYRUBF")).thenReturn(null)
            val registry = SimpleMeterRegistry()
            val service = service(mode = "LIVE", moex = moex, configValue = BigDecimal("0.5"), registry = registry)

            service.refresh("CNYRUBF")

            // Снапшот из CONFIG (provisional): источник — CONFIG, значение 0.5.
            assertEquals(0, BigDecimal("0.5").compareTo(service.value("CNYRUBF")))
            assertEquals(
                1.0,
                registry.counter("funding.live.provider_unavailable", "ticker", "CNYRUBF").count(),
            )
        }

    @Test
    fun `live rejects stale MOEX snapshot and falls back to config with metric`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            Mockito
                .`when`(
                    moex.currentSnapshot("CNYRUBF"),
                ).thenReturn(
                    // timestamp старше default TTL (5 мин) — снапшот УСТАРЕЛ.
                    FundingSnapshot(
                        ticker = "CNYRUBF",
                        rawValue = BigDecimal("0.00279"),
                        unit = FundingUnit.RAW_UNKNOWN,
                        valueRubPerContractPerClearing = BigDecimal("2.79"),
                        source = FundingSource.MOEX,
                        timestamp = LocalDateTime.now().minusMinutes(10),
                    ),
                )
            val registry = SimpleMeterRegistry()
            val service = service(mode = "LIVE", moex = moex, configValue = BigDecimal("0.5"), registry = registry)

            service.refresh("CNYRUBF")

            assertEquals(0, BigDecimal("0.5").compareTo(service.value("CNYRUBF")))
            assertEquals(
                1.0,
                registry.counter("funding.live.snapshot_stale_config_fallback", "ticker", "CNYRUBF").count(),
            )
        }

    @Test
    fun `simulation always uses config funding value`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            val registry = SimpleMeterRegistry()
            val service = service(mode = "SIMULATION", moex = moex, configValue = BigDecimal("0.5"), registry = registry)

            service.refresh("CNYRUBF")

            assertEquals(0, BigDecimal("0.5").compareTo(service.value("CNYRUBF")))
        }

    private fun service(
        mode: String,
        moex: MoexFundingProvider,
        configValue: BigDecimal,
        registry: SimpleMeterRegistry,
    ): FundingSnapshotService {
        val tradingConfig = TradingConfig().apply { this.mode = mode }
        val instrumentsConfig =
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
                            fundingRubPerContractPerDay = configValue,
                        ),
                    )
            }
        return FundingSnapshotService(
            fundingConfig = FundingConfig(),
            tradingConfig = tradingConfig,
            configuredFunding = ConfiguredFundingProvider(instrumentsConfig),
            moexFunding = moex,
            meterRegistry = registry,
        )
    }
}
