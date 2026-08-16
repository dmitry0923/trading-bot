package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * RiskExposureService — live-снимок портфельного риска (Correlation Engine).
 *
 * Проверяются: Gross/Net Exposure в % AUM, секторная агрегация, корреляционная
 * матрица, эффективное число ставок, VaR95 и Exposure Score. Ключевой сценарий —
 * три коррелированные позиции = «одна ставка на рынок» (высокий score).
 */
class RiskExposureServiceTest {
    private val riskConfig = RiskConfig()
    private val instrumentsConfig = InstrumentsConfig()
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val correlationProvider = Mockito.mock(CorrelationMatrixProvider::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val aumProvider = Mockito.mock(AumProvider::class.java)

    private val service =
        RiskExposureService(riskConfig, instrumentsConfig, positionRepo, correlationProvider, candleCache, meterRegistry, aumProvider)

    private suspend fun stubAum() {
        Mockito.`when`(aumProvider.currentAum()).thenReturn(riskConfig.maxPositionRub)
    }

    private fun position(
        ticker: String,
        price: BigDecimal,
        direction: PositionDirection = PositionDirection.LONG,
        instrumentType: InstrumentType = InstrumentType.STOCK,
    ): Position =
        Position(
            id = ticker.hashCode().toLong(),
            ticker = ticker,
            direction = direction,
            quantity = 1,
            entryPrice = price,
            instrumentType = instrumentType,
        )

    private suspend fun stubPositions(positions: List<Position>) {
        Mockito.`when`(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(positions)
    }

    private fun stubCorrelations(
        tickers: List<String>,
        corr: Double?,
    ) {
        Mockito.`when`(correlationProvider.correlations(tickers, "MINUTE_10", 50)).thenReturn(
            tickers.associateWith { a ->
                tickers.associateWith { b -> if (a == b) 1.0 else corr }
            },
        )
        Mockito.`when`(correlationProvider.resolved(tickers, "MINUTE_10", 50)).thenReturn(
            tickers.map { a ->
                tickers.map { b -> if (a == b) 1.0 else (corr ?: 0.0) }
            },
        )
    }

    private fun stubVols(tickers: List<String>) {
        tickers.forEach { t ->
            Mockito.`when`(candleCache.calculateRealizedVolatility(t, "DAY_1", 20)).thenReturn(1.0)
        }
    }

    @Test
    fun `empty portfolio has zero score and zero exposure`() =
        runBlocking {
            stubPositions(emptyList())
            stubAum()

            val report = service.buildSnapshot()

            assertEquals(0, report.exposureScore)
            assertEquals(0.0, report.grossExposureRub.toDouble(), 1e-9)
            assertEquals(0.0, report.netExposureRub.toDouble(), 1e-9)
            assertEquals(0.0, report.effectivePositions.toDouble(), 1e-9)
            assertTrue(report.perSectorExposure.isEmpty())
            assertTrue(report.correlationMatrix.isEmpty())
        }

    @Test
    fun `correlated cluster is one market bet with elevated score`() =
        runBlocking {
            riskConfig.sectors = mapOf("SBER" to "FINANCE", "VTBR" to "FINANCE", "GAZP" to "ENERGY")
            stubPositions(
                listOf(
                    position("SBER", BigDecimal("100")),
                    position("VTBR", BigDecimal("100")),
                    position("GAZP", BigDecimal("100")),
                ),
            )
            stubAum()
            stubCorrelations(listOf("SBER", "VTBR", "GAZP"), 0.75)
            stubVols(listOf("SBER", "VTBR", "GAZP"))

            val report = service.buildSnapshot()

            assertEquals(300.0, report.grossExposureRub.toDouble(), 1e-9)
            assertEquals(0.6, report.grossExposurePercent.toDouble(), 0.001)
            assertEquals(1.2, report.effectivePositions.toDouble(), 0.02)
            assertEquals(0.75, report.maxPairCorrelation, 1e-9)
            assertTrue(report.exposureScore in 40..60, "cluster should be elevated: ${report.exposureScore}")

            val finance = report.perSectorExposure.first { it.sector == "FINANCE" }
            assertEquals(2, finance.positionCount)
            assertEquals(0.4, finance.grossPercentAum.toDouble(), 0.001)
            val energy = report.perSectorExposure.first { it.sector == "ENERGY" }
            assertEquals(1, energy.positionCount)
            assertEquals(0.2, energy.grossPercentAum.toDouble(), 0.001)
        }

    @Test
    fun `hedged long short book nets out and lowers score`() =
        runBlocking {
            stubPositions(
                listOf(
                    position("SBER", BigDecimal("100")),
                    position("GAZP", BigDecimal("100"), PositionDirection.SHORT),
                ),
            )
            stubAum()
            stubCorrelations(listOf("SBER", "GAZP"), 0.5)
            stubVols(listOf("SBER", "GAZP"))

            val report = service.buildSnapshot()

            assertEquals(0.0, report.netExposureRub.toDouble(), 1e-9)
            assertEquals(0.0, report.netExposurePercent.toDouble(), 1e-9)
            assertEquals(200.0, report.grossExposureRub.toDouble(), 1e-9)
            assertTrue(report.exposureScore < 30, "hedged book should be low risk: ${report.exposureScore}")
        }

    @Test
    fun `single position is a fully directional baseline`() =
        runBlocking {
            riskConfig.sectors = mapOf("SBER" to "FINANCE")
            stubPositions(listOf(position("SBER", BigDecimal("100"))))
            stubAum()
            stubCorrelations(listOf("SBER"), 1.0)
            stubVols(listOf("SBER"))

            val report = service.buildSnapshot()

            assertEquals(1.0, report.effectivePositions.toDouble(), 1e-9)
            assertEquals(0.2, report.grossExposurePercent.toDouble(), 0.001)
            assertEquals(0.2, report.netExposurePercent.toDouble(), 0.001)
            assertTrue(report.exposureScore in 0..100)
            assertEquals(1, report.perPositionExposure.size)
            assertEquals("FINANCE", report.perPositionExposure.first().sector)
            assertEquals("FINANCE", report.perSectorExposure.first().sector)
        }

    @Test
    fun `futures notional is scaled by point value (RISK-OPEN-2)`() =
        runBlocking {
            stubPositions(listOf(position("Si", BigDecimal("70000"), instrumentType = InstrumentType.FUTURES)))
            stubAum()
            stubCorrelations(listOf("Si"), 1.0)
            stubVols(listOf("Si"))

            val report = service.buildSnapshot()

            // Si: pointValue = priceStepCost/priceStep = 10/0.01 = 1000 ₽ на 1.0 цены
            // → нотионал 70000 × 1 × 1000, а не «пункты как рубли» (70000).
            assertEquals(BigDecimal("70000000.00"), report.grossExposureRub)
            assertEquals(BigDecimal("70000000.00"), report.perPositionExposure.single().notionalRub)
        }
}
