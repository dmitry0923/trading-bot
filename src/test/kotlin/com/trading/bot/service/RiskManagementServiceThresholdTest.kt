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
import org.mockito.kotlin.anyOrNull
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
            Mockito.mock(com.trading.bot.infrastructure.alor.AlorFuturesClient::class.java),
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
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service(registry = registry)
        val open =
            listOf(
                Position(ticker = "A", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal("40000")),
                Position(ticker = "B", direction = PositionDirection.SHORT, quantity = 1, entryPrice = BigDecimal("40000")),
            )

        val blocked = s.exceedsPortfolioLimits(BigDecimal("10000"), PositionDirection.LONG, open, null)

        assertTrue(blocked)
        assertEquals(1.0, registry.counter("risk.portfolio.gross_exposure.blocked").count())
        assertEquals(0.0, registry.counter("risk.portfolio.net_exposure.blocked").count())
    }

    @Test
    fun `gross exposure at boundary is allowed`() {
        // Pin gross limit to 150% so boundary test is independent of config defaults.
        // grossBefore = 55k (SHORT), candidate = 20k (LONG) → grossAfter = 75k = 50k × 150%.
        val config = RiskConfig().apply { maxGrossExposurePercent = 150.0 }
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service(config = config)
        val open = listOf(Position(ticker = "A", direction = PositionDirection.SHORT, quantity = 1, entryPrice = BigDecimal("55000")))

        assertFalse(s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.LONG, open, null))
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
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service(config = config, registry = registry)
        val open = listOf(Position(ticker = "A", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal("40000")))

        val blocked = s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.LONG, open, null)

        assertTrue(blocked)
        assertEquals(0.0, registry.counter("risk.portfolio.gross_exposure.blocked").count())
        assertEquals(1.0, registry.counter("risk.portfolio.net_exposure.blocked").count())
    }

    @Test
    fun `net short exposure beyond negative limit blocks`() {
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service()
        val open = listOf(Position(ticker = "A", direction = PositionDirection.SHORT, quantity = 1, entryPrice = BigDecimal("40000")))

        assertTrue(s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.SHORT, open, null))
    }

    @Test
    fun `net exposure within limits is allowed`() {
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service()
        val open = listOf(Position(ticker = "A", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal("30000")))

        assertFalse(s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.LONG, open, null))
    }

    @Test
    fun `long and short offset each other in net exposure`() {
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service()
        val open =
            listOf(
                Position(ticker = "A", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal("10000")),
                Position(ticker = "B", direction = PositionDirection.SHORT, quantity = 1, entryPrice = BigDecimal("10000")),
            )

        assertFalse(s.exceedsPortfolioLimits(BigDecimal("20000"), PositionDirection.LONG, open, null))
    }

    @Test
    fun `non-positive candidate notional is allowed`() {
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service()
        val open = emptyList<Position>()

        assertFalse(s.exceedsPortfolioLimits(BigDecimal.ZERO, PositionDirection.LONG, open, null))
        assertFalse(s.exceedsPortfolioLimits(BigDecimal("-100"), PositionDirection.LONG, open, null))
    }

    @Test
    fun `gross exposure uses per-account AUM from open positions`() {
        // P2/P1-регрессия: лимиты exposure считаются от AUM ЯВНО переданного accountId,
        // а не от глобального AUM пула. account AUM 30k → grossAfter 50k > 30k → BLOCK;
        // при глобальном AUM 50k лимит 50k → пропуск (баг).
        Mockito.`when`(aumProvider.latestAumResult(5L)).thenReturn(AumProvider.AumResult.Available(BigDecimal("30000"), 0))
        val s = service()
        val open =
            listOf(
                Position(
                    ticker = "A",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("40000"),
                    accountId = 5L,
                ),
            )

        val blocked = s.exceedsPortfolioLimits(BigDecimal("10000"), PositionDirection.LONG, open, 5L)

        assertTrue(blocked)
    }

    @Test
    fun `positions from another account DENY entry (scope check P1)`() {
        // P1-регрессия: если в openPositions попали позиции НЕ того аккаунта, что
        // передан accountId, — fail-closed DENY (нельзя мерить кандидата B по AUM A).
        Mockito.`when`(aumProvider.latestAumResult(5L)).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service()
        val open =
            listOf(
                Position(
                    ticker = "A",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("40000"),
                    accountId = 7L,
                ),
            )

        val blocked = s.exceedsPortfolioLimits(BigDecimal("10000"), PositionDirection.LONG, open, 5L)

        assertTrue(blocked)
    }

    @Test
    fun `unavailable AUM denies exposure check (fail-closed P1)`() {
        // P1-регрессия: в LIVE без реального AUM (ошибка API / нет кэша) лимиты
        // exposure не считаются от конфигурационного депозита — DENY.
        val registry = SimpleMeterRegistry()
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Unavailable)
        val s = service(registry = registry)
        val open = emptyList<Position>()

        val blocked = s.exceedsPortfolioLimits(BigDecimal("10000"), PositionDirection.LONG, open, null)

        assertTrue(blocked)
        assertEquals(1.0, registry.counter("risk.portfolio.aum_unavailable.blocked").count())
    }

    @Test
    fun `futures market exposure is measured by market notional in portfolio gates`() {
        // P0-аудит: портфельные лимиты GROSS/NET оперируют РЫНОЧНОЙ экспозицией
        // (market notional), а не ГО. Кандидат Si со значение 15k (внутри 100%-лимита
        // от AUM 50k) — ALLOW, полный номинал/большая экспозиция 92k — DENY.
        val config = RiskConfig().apply { maxGrossExposurePercent = 100.0 }
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service(config = config)
        val open = emptyList<Position>()

        // market exposure 15000 < 50000 (100%) → allowed.
        assertFalse(s.exceedsPortfolioLimits(BigDecimal("15000"), PositionDirection.LONG, open, null))

        // market exposure 92000 > 50000 (100%) → DENY.
        assertTrue(s.exceedsPortfolioLimits(BigDecimal("92000"), PositionDirection.LONG, open, null))
    }

    @Test
    fun `margin utilization gate blocks when GO exceeds margin budget`() {
        // P0-аудит: ОТДЕЛЬНЫЙ маржинальный гейт контролирует залоговую загрузку
        // (GO × qty) против maxMarginUsagePercent% от депозита. Не зависит от
        // directional/market exposure.
        val registry = SimpleMeterRegistry()
        val config = RiskConfig().apply { maxMarginUsagePercent = 60.0 }
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service(config = config, registry = registry)

        // существующие фьючерсы 0, кандидат GO 30k → 60% от 50k = 30k → на границе → allowed.
        assertFalse(s.exceedsMarginUtilization(BigDecimal("50000"), BigDecimal.ZERO, BigDecimal("30000")))
        // кандидат GO 31k при существующих 0 → 31k > 30k (60%) → BLOCK.
        assertTrue(s.exceedsMarginUtilization(BigDecimal("50000"), BigDecimal.ZERO, BigDecimal("31000")))
        assertEquals(1.0, registry.counter("risk.portfolio.margin_utilization.blocked").count())
    }

    @Test
    fun `margin utilization gate accounts for existing open futures margin`() {
        val registry = SimpleMeterRegistry()
        val config = RiskConfig().apply { maxMarginUsagePercent = 60.0 }
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service(config = config, registry = registry)

        // существующие фьючерсы уже держат 25k ГО; кандидат 10k → сумма 35k > 30k (60% 50k) → BLOCK.
        assertTrue(s.exceedsMarginUtilization(BigDecimal("50000"), BigDecimal("25000"), BigDecimal("10000")))
        // существующие 20k + кандидат 10k = 30k → на границе → allowed.
        assertFalse(s.exceedsMarginUtilization(BigDecimal("50000"), BigDecimal("20000"), BigDecimal("10000")))
    }

    @Test
    fun `marginOfPosition returns marginUsed for futures and zero for stocks`() {
        val config = RiskConfig()
        Mockito.`when`(aumProvider.latestAumResult(anyOrNull())).thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), 0))
        val s = service(config = config)

        val futuresWithMargin =
            Position(
                ticker = "CNYRUBF",
                direction = PositionDirection.LONG,
                quantity = 2,
                entryPrice = BigDecimal("12.787"),
                marginUsed = BigDecimal("2000"),
            )
        assertEquals(BigDecimal("2000"), s.marginOfPosition(futuresWithMargin))

        val futuresFallbackStaticGo =
            Position(
                ticker = "CNYRUBF",
                direction = PositionDirection.LONG,
                quantity = 2,
                entryPrice = BigDecimal("12.787"),
                marginUsed = null,
            )
        // fallback = статический spec.go (850) × qty 2 = 1700.
        assertEquals(BigDecimal("1700"), s.marginOfPosition(futuresFallbackStaticGo))

        val stock =
            Position(
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("300"),
            )
        assertEquals(BigDecimal.ZERO, s.marginOfPosition(stock))
    }
}
