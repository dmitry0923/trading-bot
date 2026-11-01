package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * [RiskManagementService] — волатильность (fail-closed/fail-open, порог ATR%),
 * scope-проверка портфеля, недоступный AUM, gross/net лимиты и fallback нотионала
 * для неизвестного инструмента.
 */
class RiskManagementServiceBranchesTest {
    private val riskConfig =
        RiskConfig().apply {
            enabled = true
            volatilityFailClosed = true
            maxVolatilityPercent = 5.0
            maxGrossExposurePercent = 100.0
            maxNetExposurePercent = 100.0
        }
    private val instruments = InstrumentsConfig()
    private val drawdown = mock<DrawdownProtectionService>()
    private val registry = SimpleMeterRegistry()
    private val aumProvider = mock<AumProvider>()
    private val futuresClient = mock<AlorFuturesClient>()
    private val service =
        RiskManagementService(
            riskConfig,
            instruments,
            drawdown,
            registry,
            aumProvider,
            futuresClient,
        )

    private fun position(
        ticker: String = "SBER",
        quantity: Int = 10,
        entryPrice: String = "100",
        direction: PositionDirection = PositionDirection.LONG,
        accountId: Long? = null,
    ): Position =
        Position(
            ticker = ticker,
            direction = direction,
            quantity = quantity,
            entryPrice = BigDecimal(entryPrice),
            accountId = accountId,
        )

    @Test
    fun `margin utilization rejects non positive inputs and blocks a breach`() {
        assertFalse(service.exceedsMarginUtilization(BigDecimal.ZERO, BigDecimal("100"), BigDecimal("10")))
        assertFalse(service.exceedsMarginUtilization(BigDecimal("1000"), BigDecimal("0"), BigDecimal("10")))

        assertTrue(service.exceedsMarginUtilization(BigDecimal("1000"), BigDecimal("700"), BigDecimal("10")))
        assertFalse(service.exceedsMarginUtilization(BigDecimal("1000"), BigDecimal("100"), BigDecimal("10")))
    }

    @Test
    fun `volatility blocks missing atr when fail closed and passes when fail open`() {
        assertTrue(service.isVolatilityTooHigh(null, BigDecimal("100")))
        assertTrue(service.isVolatilityTooHigh(BigDecimal.ZERO, BigDecimal("100")))

        riskConfig.volatilityFailClosed = false
        try {
            assertFalse(service.isVolatilityTooHigh(null, BigDecimal("100")))
            assertFalse(service.isVolatilityTooHigh(BigDecimal.ONE, BigDecimal.ZERO))
        } finally {
            riskConfig.volatilityFailClosed = true
        }
    }

    @Test
    fun `volatility compares atr percent with the limit`() {
        assertTrue(service.isVolatilityTooHigh(BigDecimal("6"), BigDecimal("100")))
        assertFalse(service.isVolatilityTooHigh(BigDecimal("1"), BigDecimal("100")))
    }

    @Test
    fun `portfolio limits deny on an account scope mismatch`() {
        val foreign = position(accountId = 1L)

        assertTrue(
            service.exceedsPortfolioLimits(
                BigDecimal("10"),
                PositionDirection.LONG,
                listOf(foreign),
                accountId = 2L,
            ),
        )
    }

    @Test
    fun `portfolio limits deny when the aum is unavailable`() {
        whenever(aumProvider.latestAumResult(null)).thenReturn(AumProvider.AumResult.Unavailable)

        assertTrue(
            service.exceedsPortfolioLimits(
                BigDecimal("10"),
                PositionDirection.LONG,
                emptyList(),
                accountId = null,
            ),
        )
        assertEquals(1.0, registry.counter("risk.portfolio.aum_unavailable.blocked").count())
    }

    @Test
    fun `portfolio limits block a gross breach`() {
        whenever(aumProvider.latestAumResult(null))
            .thenReturn(AumProvider.AumResult.Available(BigDecimal("1000"), 0))

        assertTrue(
            service.exceedsPortfolioLimits(
                BigDecimal("2000"),
                PositionDirection.LONG,
                emptyList(),
                accountId = null,
            ),
        )
        assertEquals(1.0, registry.counter("risk.portfolio.gross_exposure.blocked").count())
    }

    @Test
    fun `portfolio limits block a net breach when gross still fits`() {
        val previousGross = riskConfig.maxGrossExposurePercent
        riskConfig.maxGrossExposurePercent = 300.0
        try {
            whenever(aumProvider.latestAumResult(null))
                .thenReturn(AumProvider.AumResult.Available(BigDecimal("1000"), 0))

            assertTrue(
                service.exceedsPortfolioLimits(
                    BigDecimal("1500"),
                    PositionDirection.SHORT,
                    emptyList(),
                    accountId = null,
                ),
            )
            assertEquals(1.0, registry.counter("risk.portfolio.net_exposure.blocked").count())
        } finally {
            riskConfig.maxGrossExposurePercent = previousGross
        }
    }

    @Test
    fun `unknown instrument notional falls back to price times quantity`() {
        whenever(aumProvider.latestAumResult(null))
            .thenReturn(AumProvider.AumResult.Available(BigDecimal("1000"), 0))

        assertTrue(
            service.exceedsPortfolioLimits(
                BigDecimal("100"),
                PositionDirection.LONG,
                listOf(position(ticker = "NOSUCH", quantity = 10, entryPrice = "100")),
                accountId = null,
            ),
        )
    }

    @Test
    fun `zero candidate notional is never blocked`() {
        assertFalse(
            service.exceedsPortfolioLimits(
                BigDecimal.ZERO,
                PositionDirection.LONG,
                emptyList(),
                accountId = null,
            ),
        )
    }
}
