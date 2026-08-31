package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Пороговые сценарии [RiskManagementService] (roadmap 13.17, P0):
 * - [RiskManagementService.isVolatilityTooHigh] — ATR% больше лимита → запрет;
 *   на границе — пропуск; при недоступности ATR — fail-closed (БЛОК, default);
 *   при выключенном конфиге — пропуск.
 * - [RiskManagementService.exceedsPortfolioLimits] — Gross/Net Exposure лимиты
 *   (порог, направленность long/short, пустой кандидат, метрики блокировок).
 */
class RiskManagementServiceThresholdTest {
    private val aumProvider = Mockito.mock(AumProvider::class.java)

    private fun service(
        config: RiskConfig = RiskConfig(),
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): RiskManagementService =
        RiskManagementService(
            config,
            InstrumentsConfig(),
            Mockito.mock(DrawdownProtectionService::class.java),
            registry,
            aumProvider,
        )

    @Test
    fun `volatility above limit blocks`() {
        assertTrue(service().isVolatilityTooHigh(BigDecimal("6"), BigDecimal("100")))
    }

    @Test
    fun `volatility below limit is allowed`() {
        assertFalse(service().isVolatilityTooHigh(BigDecimal("4"), BigDecimal("100")))
    }

    @Test
    fun `volatility exactly at limit is allowed`() {
        assertFalse(service().isVolatilityTooHigh(BigDecimal("5"), BigDecimal("100")))
    }

    @Test
    fun `unavailable ATR blocks by default (fail-closed)`() {
        val s = service()
        assertTrue(s.isVolatilityTooHigh(null, BigDecimal("100")))
        assertTrue(s.isVolatilityTooHigh(BigDecimal.ZERO, BigDecimal("100")))
        assertTrue(s.isVolatilityTooHigh(BigDecimal("6"), BigDecimal.ZERO))
        assertTrue(s.isVolatilityTooHigh(BigDecimal("6"), BigDecimal("-1")))
    }

    @Test
    fun `unavailable ATR allowed when fail-closed disabled`() {
        val config = RiskConfig().apply { volatilityFailClosed = false }
        val s = service(config)
        assertFalse(s.isVolatilityTooHigh(null, BigDecimal("100")))
        assertFalse(s.isVolatilityTooHigh(BigDecimal.ZERO, BigDecimal("100")))
        assertFalse(s.isVolatilityTooHigh(BigDecimal("6"), BigDecimal.ZERO))
        assertFalse(s.isVolatilityTooHigh(BigDecimal("6"), BigDecimal("-1")))
    }

    @Test
    fun `volatility check skipped when risk disabled`() {
        val config = RiskConfig().apply { enabled = false }
        assertFalse(service(config).isVolatilityTooHigh(BigDecimal("60"), BigDecimal("100")))
        assertFalse(service(config).isVolatilityTooHigh(null, BigDecimal("100")))
    }

    @Test
    fun `gross exposure exceeded blocks and records metric`() {
        val registry = SimpleMeterRegistry()
        Mockito.`when`(aumProvider.latestAum()).thenReturn(BigDecimal("50000"))
        val s = service(registry = registry)
        val open =
            listOf(
                Position(ticker = "A", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal("40000")),
                Position(ticker = "B", direction = PositionDirection.SHORT, quantity = 1, entryPrice = BigDecimal("40000")),
            )

        val blocked = s.exceedsPortfolioLimits(BigDecimal("10000"), PositionDirection.LONG, open)

        assertTrue(blocked)
        assertEquals(1.0, registry.counter("risk.portfolio.gross_exposure.blocked").count())
        assertEquals(0.0, registry.counter("risk.portfolio.net_exposure.blocked").count())
    }

    @Test
    fun `gross exposure at boundary is allowed`() {
        // Pin gross limit to 150% so boundary test is independent of config defaults.
        // grossBefore = 55k (SHORT), candidate = 20k (LONG) → grossAfter = 75k = 50k × 150%.
        val config = RiskConfig().apply { maxGrossExposurePercent = 150.0 }
        Mockito.`when`(aumProvider.latestAum()).thenReturn(BigDecimal("50000"))
        val s = service(config = config)
        val open = listOf(Position(ticker = "A", direction = PositionDirection.SHORT, quantity = 1, entryPrice = BigDecimal("55000")))

        assertFalse(s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.LONG, open))
    }

    @Test
    fun `net long exposure exceeded blocks`() {
        // Pin gross to 150% (so gross check passes), net to 50% (so net check blocks).
        // grossAfter = 60k < 75k (150% of 50k) → gross passes.
        // netAfter = 40k + 20k = 60k > 25k (50% of 50k) → net blocks.
        val config =
            RiskConfig().apply {
                maxGrossExposurePercent = 150.0
                maxNetExposurePercent = 50.0
            }
        val registry = SimpleMeterRegistry()
        Mockito.`when`(aumProvider.latestAum()).thenReturn(BigDecimal("50000"))
        val s = service(config = config, registry = registry)
        val open = listOf(Position(ticker = "A", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal("40000")))

        val blocked = s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.LONG, open)

        assertTrue(blocked)
        assertEquals(0.0, registry.counter("risk.portfolio.gross_exposure.blocked").count())
        assertEquals(1.0, registry.counter("risk.portfolio.net_exposure.blocked").count())
    }

    @Test
    fun `net short exposure beyond negative limit blocks`() {
        Mockito.`when`(aumProvider.latestAum()).thenReturn(BigDecimal("50000"))
        val s = service()
        val open = listOf(Position(ticker = "A", direction = PositionDirection.SHORT, quantity = 1, entryPrice = BigDecimal("40000")))

        assertTrue(s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.SHORT, open))
    }

    @Test
    fun `net exposure within limits is allowed`() {
        Mockito.`when`(aumProvider.latestAum()).thenReturn(BigDecimal("50000"))
        val s = service()
        val open = listOf(Position(ticker = "A", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal("30000")))

        assertFalse(s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.LONG, open))
    }

    @Test
    fun `long and short offset each other in net exposure`() {
        Mockito.`when`(aumProvider.latestAum()).thenReturn(BigDecimal("50000"))
        val s = service()
        val open =
            listOf(
                Position(ticker = "A", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal("10000")),
                Position(ticker = "B", direction = PositionDirection.SHORT, quantity = 1, entryPrice = BigDecimal("10000")),
            )

        assertFalse(s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.LONG, open))
    }

    @Test
    fun `non-positive candidate notional is allowed`() {
        Mockito.`when`(aumProvider.latestAum()).thenReturn(BigDecimal("50000"))
        val s = service()
        val open = emptyList<Position>()

        assertFalse(s.exceedsPortfolioLimits(BigDecimal.ZERO, PositionDirection.LONG, open))
        assertFalse(s.exceedsPortfolioLimits(BigDecimal("-100"), PositionDirection.LONG, open))
    }
}
