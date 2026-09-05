package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.risk.StockRiskEngine
import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.TradeRiskDecision
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.AumProvider
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.LiveFrozenStrategyResolver
import com.trading.bot.service.RiskManagementService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class StockEntryProfileTest {
    private val stockRiskEngine = mock<StockRiskEngine>()
    private val adaptiveRisk = mock<AdaptiveRiskService>()
    private val risk = mock<RiskManagementService>()
    private val candleCache = mock<CandleCacheService>()
    private val orderBuilder = mock<OrderBuilder>()
    private val riskConfig = RiskConfig()
    private val instrumentsConfig = InstrumentsConfig()
    private val agentLogRepo = mock<AgentLogRepository>()
    private val aumProvider = mock<AumProvider>()

    private val profile =
        StockEntryProfile(
            stockRiskEngine = stockRiskEngine,
            adaptiveRisk = adaptiveRisk,
            risk = risk,
            candleCache = candleCache,
            orderBuilder = orderBuilder,
            riskConfig = riskConfig,
            instrumentsConfig = instrumentsConfig,
            agentLogRepo = agentLogRepo,
            aumProvider = aumProvider,
            liveFrozenStrategyResolver = mock<LiveFrozenStrategyResolver>(),
        )

    // ── matches ─────────────────────────────────────────────

    @Test
    fun `matches returns true for CNYRUB_TOM FX instrument`() {
        assertTrue(profile.matches("CNYRUB_TOM"))
    }

    @Test
    fun `matches returns true for SBER stock instrument`() {
        assertTrue(profile.matches("SBER"))
    }

    @Test
    fun `matches returns false for Si futures instrument`() {
        assertFalse(profile.matches("Si"))
    }

    @Test
    fun `matches returns true for unknown instrument (not futures)`() {
        assertTrue(profile.matches("UNKNOWN"))
    }

    // ── instrumentType ──────────────────────────────────────

    @Test
    fun `instrumentType is STOCK for stock or FX profile`() {
        assertEquals(InstrumentType.STOCK, profile.instrumentType)
    }

    @Test
    fun `portfolioMode is ENFORCED`() {
        assertEquals(PortfolioMode.ENFORCED, profile.portfolioMode())
    }

    // ── sizePosition ────────────────────────────────────────

    @Test
    fun `sizePosition CNYRUB_TOM Kelly 0_25 of 50k gives 1 lot`() =
        runBlocking {
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("12500"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(1, size.quantity)
            assertNull(size.reason)
        }

    @Test
    fun `sizePosition allows 1 lot via MinimumLotPolicy when Kelly budget too small`() =
        runBlocking {
            // kellySizeRub = 5000 (positive → trade has expected value)
            // notionalPerLot = 12.42 * 1000 = 12420
            // 5000 >= 12420 * 0.30 = 3726 ✓
            // kellyLots = floor(5000 / 12420) = 0
            // maxLotsByRisk = floor(500 / 82.1) = 6 → risk allows 1 lot
            // expectedNetProfitPerLot = 10 > 5 (minNetProfitRub)
            // MinimumLotPolicy: all conditions met → 1 lot
            riskConfig.minimumLotPolicyEnabled = true
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("5000"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)
            Mockito
                .doReturn(BigDecimal("10"))
                .`when`(adaptiveRisk)
                .expectedNetProfitPerLot("CNYRUB_TOM", accountId = null)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(1, size.quantity)
            assertEquals("MINIMUM_LOT_OVERRIDE", size.reason)
            riskConfig.minimumLotPolicyEnabled = false
        }

    @Test
    fun `sizePosition returns KELLY_BELOW_MIN_LOT when MinimumLotPolicy disabled`() =
        runBlocking {
            // Same as above but policy disabled (default)
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("5000"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(0, size.quantity)
            assertEquals("KELLY_BELOW_MIN_LOT", size.reason)
        }

    @Test
    fun `sizePosition returns KELLY_BELOW_MIN_LOT when Kelly fraction below threshold`() =
        runBlocking {
            // kellySizeRub = 3000, notionalPerLot = 12420
            // kellyFractionOfLot = 3000/12420 = 0.24 < 0.30 (minKellyFraction)
            // Policy condition 4 fails → KELLY_BELOW_MIN_LOT
            riskConfig.minimumLotPolicyEnabled = true
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("3000"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(0, size.quantity)
            assertEquals("KELLY_BELOW_MIN_LOT", size.reason)
            riskConfig.minimumLotPolicyEnabled = false
        }

    @Test
    fun `sizePosition normal Kelly when Kelly equals notionalPerLot`() =
        runBlocking {
            // kellySizeRub = 12420 = notionalPerLot
            // kellyLots = floor(12420 / 12420) = 1 → normal Kelly path, not override
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("12420"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(1, size.quantity)
            assertNull(size.reason)
        }

    @Test
    fun `sizePosition MinimumLotPolicy at boundary Kelly just below notional`() =
        runBlocking {
            // kellySizeRub = 12419.99, notionalPerLot = 12420
            // 12419.99 >= 12420 * 0.30 = 3726 ✓
            // kellyLots = floor(12419.99/12420) = 0 → policy path
            // expectedNetProfitPerLot = 10 > 5 ✓
            riskConfig.minimumLotPolicyEnabled = true
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("12419.99"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)
            Mockito
                .doReturn(BigDecimal("10"))
                .`when`(adaptiveRisk)
                .expectedNetProfitPerLot("CNYRUB_TOM", accountId = null)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(1, size.quantity)
            assertEquals("MINIMUM_LOT_OVERRIDE", size.reason)
            riskConfig.minimumLotPolicyEnabled = false
        }

    @Test
    fun `sizePosition returns MINIMUM_LOT_NET_EV_TOO_LOW when expected profit below threshold`() =
        runBlocking {
            // kellySizeRub = 5000, kelly fraction check passes (5000 >= 3726)
            // riskAllows = 6 ≥ 1 ✓
            // But expectedNetProfitPerLot = 2 < 5 (minNetProfitRub) → blocked
            riskConfig.minimumLotPolicyEnabled = true
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("5000"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)
            Mockito
                .doReturn(BigDecimal("2"))
                .`when`(adaptiveRisk)
                .expectedNetProfitPerLot("CNYRUB_TOM", accountId = null)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(0, size.quantity)
            assertEquals("MINIMUM_LOT_NET_EV_TOO_LOW", size.reason)
            riskConfig.minimumLotPolicyEnabled = false
        }

    @Test
    fun `sizePosition returns MINIMUM_LOT_NET_EV_TOO_LOW when stats unavailable`() =
        runBlocking {
            // Kelly fraction passes, risk passes, but expectedNetProfitPerLot returns null (no stats)
            riskConfig.minimumLotPolicyEnabled = true
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("5000"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)
            Mockito
                .doReturn(null)
                .`when`(adaptiveRisk)
                .expectedNetProfitPerLot("CNYRUB_TOM", accountId = null)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(0, size.quantity)
            assertEquals("MINIMUM_LOT_NET_EV_TOO_LOW", size.reason)
            riskConfig.minimumLotPolicyEnabled = false
        }

    @Test
    fun `sizePosition normal Kelly when Kelly just above notional`() =
        runBlocking {
            // kellySizeRub = 12420.01, notionalPerLot = 12420
            // kellyLots = floor(12420.01/12420) = 1 → normal Kelly path
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("12420.01"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(1, size.quantity)
            assertNull(size.reason)
        }

    @Test
    fun `sizePosition returns ZERO_RISK_SIZE when Kelly rejects trade`() =
        runBlocking {
            // kellySizeRub = 0 → Kelly has negative expected value → no override possible
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal.ZERO)
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(0, size.quantity)
            assertEquals("ZERO_RISK_SIZE", size.reason)
        }

    @Test
    fun `sizePosition returns KELLY_BELOW_MIN_LOT when risk cap blocks 1 lot`() =
        runBlocking {
            // kellySizeRub = 5000 (positive, kellyFractionOfLot = 0.40 > 0.30)
            // riskAmount = 100 * 1.0 / 100 = 1.0, lossPerLot = 82.1
            // maxLotsByRisk = floor(1.0 / 82.1) = 0 → policy condition 5 fails
            riskConfig.minimumLotPolicyEnabled = true
            Mockito.doReturn(BigDecimal("100")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("5000"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("100"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(0, size.quantity)
            assertEquals("KELLY_BELOW_MIN_LOT", size.reason)
            riskConfig.minimumLotPolicyEnabled = false
        }

    @Test
    fun `sizePosition lossPerLot includes 2x commission`() =
        runBlocking {
            // lossPerLot = 12.42 * 0.005 * 1000 + 2 * 10 = 62.1 + 20 = 82.1
            // riskAmount = 50000 * 1.0 / 100 = 500
            // maxLotsByRisk = floor(500 / 82.1) = 6
            // kellyLots = 1 → min(1, 6) = 1
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("12500"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)

            val signal = makeSignal()
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)
            assertEquals(1, size.quantity)
        }

    @Test
    fun `sizePosition honours frozen riskPerTradePercent for risk cap`() =
        runBlocking {
            // P1-аудит: риск-кап должен использовать frozen riskPerTradePercent (1%),
            // а не runtime (10%). lossPerLot = 12.42*0.005*1000 + 2*10 = 82.1.
            // frozen riskAmount = 50000*1/100 = 500 -> maxLotsByRisk = floor(500/82.1) = 6.
            // runtime (10%) дал бы 5000 -> 60. Kelly большой (kellyLots=40), чтобы риск-кап
            // был сдерживающим: min(40, 6) = 6 при frozen vs min(40,60)=40 при runtime.
            riskConfig.riskPerTradePercent = 10.0
            Mockito.doReturn(BigDecimal("50000")).`when`(aumProvider).currentAum(null)
            Mockito
                .doReturn(BigDecimal("500000"))
                .`when`(adaptiveRisk)
                .calculateOptimalPositionSize("CNYRUB_TOM", signalStrength = 0.7)

            val signal = makeSignal()
            val frozen =
                FrozenStrategy(
                    ticker = "CNYRUB_TOM",
                    strategyVersion = "live-v2",
                    gitCommitSha = "abc",
                    slPercent = 0.5,
                    tpPercent = 2.0,
                    slPoints = null,
                    tpPoints = null,
                    confidenceThreshold = 0.6,
                    leverage = 1.0,
                    riskPerTradePercent = 1.0,
                    futuresMaxContractsPerPosition = null,
                )
            val request =
                EntryRequest(
                    ticker = "CNYRUB_TOM",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("12.42"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("50000"),
                    currentGo = BigDecimal.ZERO,
                    atr = null,
                    openPositions = emptyList(),
                    frozenStrategy = frozen,
                )

            val size = profile.sizePosition(signal, BigDecimal("12.42"), request)

            assertEquals(6, size.quantity)
        }

    @Test
    fun `postSizingChecks returns ZERO_RISK_SIZE when quantity is 0`() =
        runBlocking {
            val size = PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, null)
            val result =
                profile.postSizingChecks(
                    "CNYRUB_TOM",
                    PositionDirection.LONG,
                    BigDecimal("12.42"),
                    size,
                    emptyList(),
                )
            assertEquals("ZERO_RISK_SIZE", result)
        }

    @Test
    fun `postSizingChecks returns null when portfolio within limits`() =
        runBlocking {
            val size = PositionSizeResult(1, BigDecimal("12420"), BigDecimal("500"), null, null)
            whenever(risk.exceedsPortfolioLimits(any(), any(), any())).thenReturn(false)
            val result =
                profile.postSizingChecks(
                    "CNYRUB_TOM",
                    PositionDirection.LONG,
                    BigDecimal("12.42"),
                    size,
                    emptyList(),
                )
            assertNull(result)
        }

    @Test
    fun `postSizingChecks returns PORTFOLIO_LIMIT when exposure exceeded`() =
        runBlocking {
            val size = PositionSizeResult(1, BigDecimal("12420"), BigDecimal("500"), null, null)
            whenever(risk.exceedsPortfolioLimits(any(), any(), any())).thenReturn(true)
            val result =
                profile.postSizingChecks(
                    "CNYRUB_TOM",
                    PositionDirection.LONG,
                    BigDecimal("12.42"),
                    size,
                    emptyList(),
                )
            assertEquals("PORTFOLIO_LIMIT", result)
        }

    // ── buildPosition ───────────────────────────────────────

    @Test
    fun `buildPosition sets instrumentType from spec`() {
        val decision =
            TradeRiskDecision(
                ticker = "CNYRUB_TOM",
                cycleId = "cycle-1",
                timeframe = "MINUTE_10",
                action = StrategyAction.BUY,
                direction = PositionDirection.LONG,
                quantity = 1,
                requestedQuantity = 1,
                entryPrice = BigDecimal("12.42"),
                targetPrice = BigDecimal("12.42"),
                stopLoss = BigDecimal("12.3580"),
                takeProfit = BigDecimal("12.5440"),
                trailingStop = true,
                signalStrength = 0.7,
                reasoning = "test",
                strategyName = "CNYRUB_TOM",
                riskAmount = BigDecimal("500"),
            )
        val pos = profile.buildPosition(decision, "order-1", true, BigDecimal("12.42"), 1)

        assertEquals("CNYRUB_TOM", pos.ticker)
        assertEquals(1, pos.quantity)
        assertEquals(0, BigDecimal("12.42").compareTo(pos.entryPrice))
        assertTrue(pos.pendingEntry)
        assertEquals("cycle-1", pos.cycleId)
        assertEquals("order-1", pos.alorOrderId)
        assertEquals(0, BigDecimal("12.3580").compareTo(pos.stopLoss))
        assertEquals(0, BigDecimal("12.5440").compareTo(pos.takeProfit))
        assertEquals(0, BigDecimal("12.3580").compareTo(pos.trailingStopPrice))
    }

    @Test
    fun `buildPosition for SBER sets instrumentType STOCK`() {
        val decision =
            TradeRiskDecision(
                ticker = "SBER",
                cycleId = "cycle-2",
                timeframe = "DAY",
                action = StrategyAction.SELL,
                direction = PositionDirection.SHORT,
                quantity = 10,
                requestedQuantity = 10,
                entryPrice = BigDecimal("270"),
                targetPrice = BigDecimal("260"),
                stopLoss = BigDecimal("275.40"),
                takeProfit = BigDecimal("259.20"),
                trailingStop = false,
                signalStrength = 0.6,
                reasoning = "test",
                strategyName = "TrendFollowing",
                riskAmount = BigDecimal("500"),
            )
        val pos = profile.buildPosition(decision, null, false, BigDecimal("270"), 10)

        assertEquals("SBER", pos.ticker)
        assertEquals(10, pos.quantity)
        assertFalse(pos.pendingEntry)
        assertNull(pos.trailingStopPrice)
    }

    // ── buildOrderParams ────────────────────────────────────

    @Test
    fun `buildOrderParams delegates to OrderBuilder buildSpotOrderParams`() {
        val size = PositionSizeResult(1, BigDecimal("12420"), BigDecimal("500"), null, null)
        val request =
            EntryRequest(
                ticker = "CNYRUB_TOM",
                action = StrategyAction.BUY,
                entryPrice = BigDecimal("12.42"),
                direction = PositionDirection.LONG,
                portfolioMoney = BigDecimal("50000"),
                currentGo = BigDecimal.ZERO,
                atr = null,
                openPositions = emptyList(),
            )
        val mockParams =
            com.trading.bot.domain.order.OrderParams(
                direction = PositionDirection.LONG,
                quantity = 1,
            )
        whenever(orderBuilder.buildSpotOrderParams("CNYRUB_TOM", PositionDirection.LONG, 1, BigDecimal("12.42")))
            .thenReturn(mockParams)

        val result = profile.buildOrderParams("CNYRUB_TOM", PositionDirection.LONG, BigDecimal("12.42"), size, request)
        assertEquals(1, result.quantity)
    }

    // ── preSizingChecks ─────────────────────────────────────

    @Test
    fun `preSizingChecks returns null when no correlation limits exceeded`() =
        runBlocking {
            whenever(adaptiveRisk.exceedsCorrelationLimit(any(), any())).thenReturn(false)
            whenever(adaptiveRisk.exceedsSectorCorrelationLimit(any(), any())).thenReturn(false)
            assertNull(profile.preSizingChecks("CNYRUB_TOM", emptyList()))
        }

    @Test
    fun `preSizingChecks returns CORRELATION when global correlation exceeded`() =
        runBlocking {
            whenever(adaptiveRisk.exceedsCorrelationLimit(any(), any())).thenReturn(true)
            whenever(adaptiveRisk.exceedsSectorCorrelationLimit(any(), any())).thenReturn(false)
            assertEquals("CORRELATION", profile.preSizingChecks("CNYRUB_TOM", emptyList()))
        }

    @Test
    fun `preSizingChecks returns SECTOR_CORRELATION when sector correlation exceeded`() =
        runBlocking {
            whenever(adaptiveRisk.exceedsCorrelationLimit(any(), any())).thenReturn(false)
            whenever(adaptiveRisk.exceedsSectorCorrelationLimit(any(), any())).thenReturn(true)
            assertEquals("SECTOR_CORRELATION", profile.preSizingChecks("CNYRUB_TOM", emptyList()))
        }

    // ── helpers ─────────────────────────────────────────────

    private fun makeSignal() = Signal(
        ticker = "CNYRUB_TOM",
        action = StrategyAction.BUY,
        targetPrice = BigDecimal("12.42"),
        signalStrength = 0.7,
        reasoning = "test",
        timeframe = "MINUTE_10",
        cycleId = "test-cycle",
        strategyName = "CNYRUB_TOM",
    )
}
