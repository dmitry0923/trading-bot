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
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * [RiskExposureService] — вырожденные режимы снимка: нулевой AUM, неизвестный
 * инструмент, отсутствие данных о волатильности/корреляции, внутридневной
 * fallback и дубли тикера (multi-account).
 */
class RiskExposureServiceBranchesTest {
    private val riskConfig = RiskConfig()
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val correlationProvider = Mockito.mock(CorrelationMatrixProvider::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val aumProvider = Mockito.mock(AumProvider::class.java)

    private val service =
        RiskExposureService(
            riskConfig,
            InstrumentsConfig(),
            positionRepo,
            correlationProvider,
            candleCache,
            meterRegistry,
            aumProvider,
        )

    private fun position(
        ticker: String,
        price: String = "100",
        direction: PositionDirection = PositionDirection.LONG,
        instrumentType: InstrumentType = InstrumentType.STOCK,
    ): Position =
        Position(
            id = ticker.hashCode().toLong(),
            ticker = ticker,
            direction = direction,
            quantity = 1,
            entryPrice = BigDecimal(price),
            instrumentType = instrumentType,
            status = PositionStatus.OPEN,
        )

    private suspend fun stubPortfolio(
        positions: List<Position>,
        aum: BigDecimal = BigDecimal("100000"),
    ) {
        whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(positions)
        whenever(positionRepo.findByStatus(PositionStatus.RECONCILIATION_REQUIRED)).thenReturn(emptyList())
        whenever(aumProvider.currentAum()).thenReturn(aum)
    }

    @Test
    fun `zero aum keeps percentages and var at zero`(): Unit =
        runBlocking {
            stubPortfolio(listOf(position("SBER")), aum = BigDecimal.ZERO)
            whenever(correlationProvider.correlations(listOf("SBER"), "MINUTE_10", 50)).thenReturn(mapOf("SBER" to mapOf("SBER" to 1.0)))

            val report = service.buildSnapshot()

            assertEquals(0, BigDecimal.ZERO.compareTo(report.grossExposurePercent))
            assertEquals(0, BigDecimal.ZERO.compareTo(report.var95Rub))
            assertEquals(0, BigDecimal.ZERO.compareTo(report.var95Percent))
        }

    @Test
    fun `unknown instrument falls back to price times quantity`(): Unit =
        runBlocking {
            stubPortfolio(listOf(position("ZZZZ", price = "50")))
            whenever(correlationProvider.correlations(listOf("ZZZZ"), "MINUTE_10", 50)).thenReturn(emptyMap())

            val report = service.buildSnapshot()

            // 50 × 1 = 50 ₽ → 0.05% от 100 000
            assertEquals(0, BigDecimal("50").compareTo(report.grossExposureRub))
            assertEquals(0, BigDecimal("0.05").compareTo(report.grossExposurePercent))
            assertEquals("UNKNOWN", report.perSectorExposure.single().sector)
        }

    @Test
    fun `missing volatility data keeps exposure but zero var`(): Unit =
        runBlocking {
            stubPortfolio(listOf(position("SBER")))
            whenever(correlationProvider.correlations(listOf("SBER"), "MINUTE_10", 50)).thenReturn(mapOf("SBER" to mapOf("SBER" to 1.0)))

            val report = service.buildSnapshot()

            assertEquals(0, BigDecimal.ZERO.compareTo(report.var95Rub))
            assertTrue(report.grossExposureRub > BigDecimal.ZERO)
        }

    @Test
    fun `intraday volatility is scaled to a daily figure`(): Unit =
        runBlocking {
            stubPortfolio(listOf(position("SBER")))
            whenever(correlationProvider.correlations(listOf("SBER"), "MINUTE_10", 50)).thenReturn(mapOf("SBER" to mapOf("SBER" to 1.0)))
            whenever(correlationProvider.resolved(listOf("SBER"), "MINUTE_10", 50)).thenReturn(listOf(listOf(1.0)))
            whenever(candleCache.calculateRealizedVolatility("SBER", "DAY_1", 20)).thenReturn(null)
            whenever(candleCache.calculateRealizedVolatility("SBER", "MINUTE_10", 20)).thenReturn(2.0)

            val report = service.buildSnapshot()

            // 2% × sqrt(57) ≈ 15.1% дневной волатильности → VaR > 0
            assertTrue(report.var95Rub > BigDecimal.ZERO)
            assertTrue(report.var95Percent > BigDecimal.ZERO)
        }

    @Test
    fun `missing correlation pairs fall back to the diagonal`(): Unit =
        runBlocking {
            stubPortfolio(listOf(position("SBER"), position("SBER")))
            whenever(correlationProvider.correlations(listOf("SBER"), "MINUTE_10", 50)).thenReturn(emptyMap())
            whenever(correlationProvider.resolved(listOf("SBER"), "MINUTE_10", 50)).thenReturn(listOf(listOf(1.0)))

            val report = service.buildSnapshot()

            // две позиции одного тикера = одна ставка
            assertEquals(1.0, report.effectivePositions.toDouble(), 1e-9)
            assertEquals(0.0, report.maxPairCorrelation, 1e-9)
        }

    @Test
    fun `correlation matrix delegates with custom timeframe and period`() {
        whenever(correlationProvider.correlations(listOf("SBER", "GAZP"), "HOUR_1", 30))
            .thenReturn(mapOf("SBER" to mapOf("GAZP" to 0.5)))

        val matrix = service.correlationMatrix(listOf("SBER", "GAZP"), timeframe = "HOUR_1", period = 30)

        assertEquals(0.5, matrix.getValue("SBER").getValue("GAZP"))
    }
}
