package com.trading.bot.application.funding

import com.trading.bot.config.FundingConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.TradingConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P1-аудит (funding per-clearing): серия снапшотов по дате клиринга; P&L вычитает
 * СУММУ значений за каждый пережитый клиринг. LIVE: авторитет только MOEX;
 * FUNDING_UNKNOWN (недоступность/нет снапшота на дату) → null (без CONFIG-подмены
 * «тихого 0.5») + метрика `funding.live.clearing_unknown`. SIM/backtest — CONFIG.
 */
class FundingSnapshotServiceTest {
    @Test
    fun `live stores MOEX snapshot under its clearing date`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            Mockito
                .`when`(moex.currentSnapshot("CNYRUBF"))
                .thenReturn(
                    FundingSnapshot(
                        ticker = "CNYRUBF",
                        clearingDate = LocalDate.of(2026, 9, 8),
                        rawValue = BigDecimal("0.00279"),
                        unit = FundingUnit.RUB_PER_BASE_ASSET_UNIT,
                        valueRubPerContractPerClearing = BigDecimal("2.79"),
                        source = FundingSource.MOEX,
                        timestamp = LocalDateTime.now(),
                    ),
                )
            val registry = SimpleMeterRegistry()
            val service = service(mode = "LIVE", moex = moex, configValue = BigDecimal("0.5"), registry = registry)

            service.refresh("CNYRUBF")
            val total = service.fundingForClearings("CNYRUBF", listOf(LocalDate.of(2026, 9, 8)))

            assertEquals(0, BigDecimal("2.79").compareTo(total!!))
            assertEquals(
                0.0,
                registry.counter("funding.live.provider_unavailable", "ticker", "CNYRUBF").count(),
            )
        }

    @Test
    fun `live MOEX unavailable yields FUNDING_UNKNOWN null with metric`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            Mockito.`when`(moex.currentSnapshot("CNYRUBF")).thenReturn(null)
            val registry = SimpleMeterRegistry()
            val service = service(mode = "LIVE", moex = moex, configValue = BigDecimal("0.5"), registry = registry)

            service.refresh("CNYRUBF")
            val total = service.fundingForClearings("CNYRUBF", listOf(LocalDate.of(2026, 9, 8)))

            assertNull(total)
            assertEquals(
                1.0,
                registry.counter("funding.live.provider_unavailable", "ticker", "CNYRUBF").count(),
            )
        }

    @Test
    fun `live missing snapshot for one clearing yields FUNDING_UNKNOWN and never substitutes config`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            Mockito
                .`when`(moex.currentSnapshot("CNYRUBF"))
                .thenReturn(
                    FundingSnapshot(
                        ticker = "CNYRUBF",
                        clearingDate = LocalDate.of(2026, 9, 8),
                        rawValue = BigDecimal("0.00279"),
                        unit = FundingUnit.RUB_PER_BASE_ASSET_UNIT,
                        valueRubPerContractPerClearing = BigDecimal("2.79"),
                        source = FundingSource.MOEX,
                        timestamp = LocalDateTime.now(),
                    ),
                )
            val registry = SimpleMeterRegistry()
            val service = service(mode = "LIVE", moex = moex, configValue = BigDecimal("0.5"), registry = registry)

            service.refresh("CNYRUBF")
            // 09-10 пережитый позицией клиринг НЕ закрыт авторитетным MOEX-снапшотом
            // (refresh на 09-10 не выполнялся/MOEX был недоступен) → FUNDING_UNKNOWN,
            // никакой подмены CONFIG 0.5 «по-тихому».
            val total = service.fundingForClearings("CNYRUBF", listOf(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 10)))

            assertNull(total)
            assertEquals(
                1.0,
                registry.counter("funding.live.clearing_unknown", "ticker", "CNYRUBF").count(),
            )
        }

    @Test
    fun `live instrument without configured funding never marks unknown`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            val registry = SimpleMeterRegistry()
            // CONFIG funding = 0 (Si/RI) → funding не начисляется, даже если MOEX недоступен.
            val service = service(mode = "LIVE", moex = moex, configValue = BigDecimal.ZERO, registry = registry)

            service.refresh("RI")
            val total = service.fundingForClearings("RI", listOf(LocalDate.of(2026, 9, 8)))

            assertEquals(BigDecimal.ZERO, total)
            assertEquals(
                0.0,
                registry.counter("funding.live.clearing_unknown", "ticker", "RI").count(),
            )
        }

    @Test
    fun `simulation uses config funding per clearing`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            val registry = SimpleMeterRegistry()
            val service = service(mode = "SIMULATION", moex = moex, configValue = BigDecimal("0.5"), registry = registry)

            service.refresh("CNYRUBF")
            val total = service.fundingForClearings("CNYRUBF", listOf(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9)))

            // 0.5 × 2 клиринга
            assertEquals(0, BigDecimal("1.0").compareTo(total))
        }

    @Test
    fun `intraday position pays no funding`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            val registry = SimpleMeterRegistry()
            val service = service(mode = "LIVE", moex = moex, configValue = BigDecimal("0.5"), registry = registry)

            // Внутридневная позиция (0 клирингов) → 0, независимо от источника.
            assertEquals(BigDecimal.ZERO, service.fundingForClearings("CNYRUBF", emptyList()))
            assertEquals(BigDecimal.ZERO, service.fundingForClearings("RI", emptyList()))
        }

    @Test
    fun `refresh skips network when today snapshot is fresh MOEX`() =
        runBlocking {
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            Mockito
                .`when`(moex.currentSnapshot("CNYRUBF"))
                .thenReturn(
                    FundingSnapshot(
                        ticker = "CNYRUBF",
                        clearingDate = LocalDate.of(2026, 9, 8),
                        rawValue = BigDecimal("0.00279"),
                        unit = FundingUnit.RUB_PER_BASE_ASSET_UNIT,
                        valueRubPerContractPerClearing = BigDecimal("2.79"),
                        source = FundingSource.MOEX,
                        timestamp = LocalDateTime.now(),
                    ),
                )
            val registry = SimpleMeterRegistry()
            val service = service(mode = "LIVE", moex = moex, configValue = BigDecimal("0.5"), registry = registry)

            service.refresh("CNYRUBF") // первый вызов — сетевой
            service.refresh("CNYRUBF") // свежий снапшот за сегодня → без повторного вызова
            Mockito.verify(moex, Mockito.times(1)).currentSnapshot("CNYRUBF")
            assertNotNull(service.fundingForClearings("CNYRUBF", listOf(LocalDate.of(2026, 9, 8))))
        }

    @Test
    fun `TTL freshness is evaluated on Europe-Moscow clock`() =
        runBlocking {
            // Фиксируем "сейчас" в MSK: 2026-09-10 12:00:00. Снапшот из прошлого клиринга
            // (11:59 MSK) — свежий (< moexTtlMs=5min) → refresh НЕ обращается к MOEX.
            val fixedClock = Clock.fixed(Instant.parse("2026-09-10T09:00:00Z"), ZoneId.of("Europe/Moscow"))
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            Mockito
                .`when`(moex.currentSnapshot("CNYRUBF"))
                .thenReturn(
                    FundingSnapshot(
                        ticker = "CNYRUBF",
                        clearingDate = LocalDate.of(2026, 9, 10),
                        rawValue = BigDecimal("0.00279"),
                        unit = FundingUnit.RUB_PER_BASE_ASSET_UNIT,
                        valueRubPerContractPerClearing = BigDecimal("2.79"),
                        source = FundingSource.MOEX,
                        timestamp = LocalDateTime.of(2026, 9, 10, 11, 59),
                    ),
                )
            val registry = SimpleMeterRegistry()
            val service =
                service(
                    mode = "LIVE",
                    moex = moex,
                    configValue = BigDecimal("0.5"),
                    registry = registry,
                    clock = fixedClock,
                )

            service.refresh("CNYRUBF") // первый вызов — сетевой
            service.refresh("CNYRUBF") // 11:59 MSK vs 12:00 MSK: 1 мин < 5 мин → без повторного вызова
            Mockito.verify(moex, Mockito.times(1)).currentSnapshot("CNYRUBF")
        }

    @Test
    fun `stale snapshot older than TTL on Moscow clock triggers re-fetch`() =
        runBlocking {
            // "Сейчас" 12:00 MSK, снапшот 11:50 MSK (10 мин назад > 5 мин TTL) → повторный вызов.
            val fixedClock = Clock.fixed(Instant.parse("2026-09-10T09:00:00Z"), ZoneId.of("Europe/Moscow"))
            val moex = Mockito.mock(MoexFundingProvider::class.java)
            Mockito
                .`when`(moex.currentSnapshot("CNYRUBF"))
                .thenReturn(
                    FundingSnapshot(
                        ticker = "CNYRUBF",
                        clearingDate = LocalDate.of(2026, 9, 10),
                        rawValue = BigDecimal("0.00279"),
                        unit = FundingUnit.RUB_PER_BASE_ASSET_UNIT,
                        valueRubPerContractPerClearing = BigDecimal("2.79"),
                        source = FundingSource.MOEX,
                        timestamp = LocalDateTime.of(2026, 9, 10, 11, 50),
                    ),
                )
            val registry = SimpleMeterRegistry()
            val service =
                service(
                    mode = "LIVE",
                    moex = moex,
                    configValue = BigDecimal("0.5"),
                    registry = registry,
                    clock = fixedClock,
                )

            service.refresh("CNYRUBF")
            service.refresh("CNYRUBF")
            Mockito.verify(moex, Mockito.times(2)).currentSnapshot("CNYRUBF")
        }

    private fun service(
        mode: String,
        moex: MoexFundingProvider,
        configValue: BigDecimal,
        registry: SimpleMeterRegistry,
        clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
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
                        InstrumentsConfig.InstrumentSpec(
                            ticker = "RI",
                            type = "FUTURES",
                            lotSize = 1,
                            priceStep = BigDecimal("1.0"),
                            priceStepCost = BigDecimal("10.0"),
                            go = BigDecimal("22000"),
                            leverage = BigDecimal("1.0"),
                            baseAsset = "RTS",
                            fundingRubPerContractPerDay = BigDecimal.ZERO,
                        ),
                    )
            }
        return FundingSnapshotService(
            fundingConfig = FundingConfig(),
            tradingConfig = tradingConfig,
            configuredFunding = ConfiguredFundingProvider(instrumentsConfig),
            moexFunding = moex,
            meterRegistry = registry,
            clock = clock,
        )
    }
}
