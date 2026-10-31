package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.funding.FundingSnapshotService
import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.FuturesStopResolver
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.signal.Signal
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.LiveFrozenStrategyResolver
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.TradingAccountService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Unit-тесты [FuturesEntryProfile.buildEntryRequest]: сборка риск-снапшота входа.
 *
 * Покрываем fail-closed ветки, которые раньше не проверялись (скан-19):
 * недоступные свободные средства, недоступное ГО кандидата, недоступное ГО уже
 * открытой фьючерсной позиции, успешная сборка снапшота и дедупликация по тикеру.
 */
class FuturesEntryProfileEntryRequestTest {
    private val instrumentsConfig = InstrumentsConfig()
    private val alorFuturesClient = Mockito.mock(AlorFuturesClient::class.java)
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val fundingSnapshotService = Mockito.mock(FundingSnapshotService::class.java)
    private val futuresPositionSizer = Mockito.mock(FuturesPositionSizer::class.java)
    private val orderBuilder = Mockito.mock(OrderBuilder::class.java)
    private val adaptiveRisk = Mockito.mock(AdaptiveRiskService::class.java)
    private val leverageConfig = Mockito.mock(LeverageConfig::class.java)
    private val liveFrozenStrategyResolver = Mockito.mock(LiveFrozenStrategyResolver::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val profile =
        FuturesEntryProfile(
            futuresRiskEngine = Mockito.mock(FuturesRiskEngine::class.java),
            futuresPositionSizer = futuresPositionSizer,
            orderBuilder = orderBuilder,
            alorFuturesClient = alorFuturesClient,
            riskConfig = RiskConfig(),
            leverageConfig = leverageConfig,
            instrumentsConfig = instrumentsConfig,
            meterRegistry = meterRegistry,
            tradingAccountService = tradingAccountService,
            candleCache = candleCache,
            futuresStopResolver = FuturesStopResolver(),
            liveFrozenStrategyResolver = liveFrozenStrategyResolver,
            adaptiveRisk = adaptiveRisk,
            risk = Mockito.mock(RiskManagementService::class.java),
            fundingSnapshotService = fundingSnapshotService,
        )

    private fun signal(ticker: String = "Si"): Signal =
        Signal(
            ticker = ticker,
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("92000"),
            signalStrength = 0.7,
            reasoning = "test",
            timeframe = "MINUTE_10",
            cycleId = "cycle-1",
        )

    private fun stubAccount() {
        runBlocking {
            Mockito.`when`(tradingAccountService.portfolioOf(Mockito.nullable(Long::class.java))).thenReturn("D12345")
        }
    }

    private fun openFutures(
        ticker: String = "RI",
        direction: PositionDirection = PositionDirection.LONG,
    ) = Position(
        id = 1L,
        ticker = ticker,
        direction = direction,
        quantity = 1,
        entryPrice = BigDecimal("100"),
        instrumentType = InstrumentType.FUTURES,
    )

    @Test
    fun `missing portfolio money blocks entry`(): Unit =
        runBlocking {
            stubAccount()
            Mockito.`when`(alorFuturesClient.getPortfolioMoney("D12345")).thenReturn(null)
            Mockito.`when`(alorFuturesClient.getFuturesGO("Si", PositionDirection.LONG)).thenReturn(BigDecimal("850"))

            assertNull(profile.buildEntryRequest(signal(), BigDecimal("92000"), emptyList(), 1L))
        }

    @Test
    fun `missing candidate GO blocks entry`(): Unit =
        runBlocking {
            stubAccount()
            Mockito.`when`(alorFuturesClient.getPortfolioMoney("D12345")).thenReturn(BigDecimal("100000"))
            Mockito.`when`(alorFuturesClient.getFuturesGO("Si", PositionDirection.LONG)).thenReturn(null)

            assertNull(profile.buildEntryRequest(signal(), BigDecimal("92000"), emptyList(), 1L))
        }

    @Test
    fun `missing GO of an open futures position blocks entry fail-closed`(): Unit =
        runBlocking {
            stubAccount()
            Mockito.`when`(alorFuturesClient.getPortfolioMoney("D12345")).thenReturn(BigDecimal("100000"))
            Mockito.`when`(alorFuturesClient.getFuturesGO("Si", PositionDirection.LONG)).thenReturn(BigDecimal("850"))
            Mockito.`when`(alorFuturesClient.getFuturesGO("RI", PositionDirection.LONG)).thenReturn(null)

            assertNull(profile.buildEntryRequest(signal(), BigDecimal("92000"), listOf(openFutures()), 1L))
        }

    @Test
    fun `successful request carries snapshot, atr and frozen strategy`(): Unit =
        runBlocking {
            stubAccount()
            Mockito.`when`(alorFuturesClient.getPortfolioMoney("D12345")).thenReturn(BigDecimal("100000"))
            Mockito.`when`(alorFuturesClient.getFuturesGO("Si", PositionDirection.LONG)).thenReturn(BigDecimal("850"))
            Mockito.`when`(alorFuturesClient.getFuturesGO("RI", PositionDirection.LONG)).thenReturn(BigDecimal("900"))
            Mockito.`when`(candleCache.calculateAtr("Si", "MINUTE_10", 14)).thenReturn(BigDecimal("120"))

            val request = profile.buildEntryRequest(signal(), BigDecimal("92000"), listOf(openFutures()), 1L)

            assertNotNull(request)
            assertEquals(BigDecimal("100000"), request!!.portfolioMoney)
            assertEquals(BigDecimal("850"), request.currentGo)
            assertEquals(BigDecimal("120"), request.atr, "ATR обязателен для волатильностного гейта")
            assertEquals(1L, request.accountId)
            assertEquals(1, request.openPositions.size)
            // Один и тот же открытый тикер не должен опрашиваться дважды.
            Mockito.verify(alorFuturesClient, Mockito.times(1)).getFuturesGO("RI", PositionDirection.LONG)
        }

    @Test
    fun `stock positions are not included in futures GO snapshot`(): Unit =
        runBlocking {
            stubAccount()
            Mockito.`when`(alorFuturesClient.getPortfolioMoney("D12345")).thenReturn(BigDecimal("100000"))
            Mockito.`when`(alorFuturesClient.getFuturesGO("Si", PositionDirection.LONG)).thenReturn(BigDecimal("850"))
            val stock = openFutures(ticker = "SBER").copy(instrumentType = InstrumentType.STOCK)

            val request = profile.buildEntryRequest(signal(), BigDecimal("92000"), listOf(stock), 1L)

            assertNotNull(request)
            // Для акции ГО не запрашивается вовсе.
            Mockito.verify(alorFuturesClient, Mockito.never()).getFuturesGO("SBER", PositionDirection.LONG)
        }

    private fun request(
        ticker: String = "Si",
        frozen: com.trading.bot.backtest.FrozenStrategy? = null,
    ) = com.trading.bot.domain.risk.EntryRequest(
        ticker = ticker,
        action = StrategyAction.BUY,
        entryPrice = BigDecimal("92000"),
        direction = PositionDirection.LONG,
        portfolioMoney = BigDecimal("100000"),
        currentGo = BigDecimal("850"),
        openPositions = emptyList(),
        frozenStrategy = frozen,
    )

    private fun size(quantity: Int = 1) =
        PositionSizeResult(
            quantity = quantity,
            marginRequired = BigDecimal("850"),
            riskAmount = BigDecimal("500"),
            liquidationPrice = null,
            reason = if (quantity == 0) "ZERO_GO" else null,
        )

    @Test
    fun `sizer rejection is metered with its reason`(): Unit =
        runBlocking {
            Mockito
                .`when`(
                    futuresPositionSizer.calculateContracts(
                        "Si",
                        BigDecimal("100000"),
                        50,
                        BigDecimal("850"),
                        BigDecimal("92000"),
                        PositionDirection.LONG,
                        null,
                        null,
                    ),
                ).thenReturn(size(quantity = 0))

            val result = profile.sizePosition(signal(), BigDecimal("92000"), request())

            assertEquals(0, result.quantity)
            assertEquals(
                1.0,
                meterRegistry
                    .counter("futures.entry.rejected", "ticker", "Si", "reason", "ZERO_GO")
                    .count(),
                1e-9,
            )
        }

    @Test
    fun `frozen strategy supplies flat stop points and contract cap to the sizer`(): Unit =
        runBlocking {
            val frozen = frozen(slPoints = 300, tpPoints = 600, leverage = 2.0, riskPerTrade = 11.0, maxContracts = 33)
            Mockito
                .`when`(
                    futuresPositionSizer.calculateContracts(
                        "Si",
                        BigDecimal("100000"),
                        300,
                        BigDecimal("850"),
                        BigDecimal("92000"),
                        PositionDirection.LONG,
                        11.0,
                        33,
                    ),
                ).thenReturn(size())

            profile.sizePosition(signal(), BigDecimal("92000"), request(frozen = frozen))

            Mockito
                .verify(futuresPositionSizer)
                .calculateContracts(
                    "Si",
                    BigDecimal("100000"),
                    300,
                    BigDecimal("850"),
                    BigDecimal("92000"),
                    PositionDirection.LONG,
                    11.0,
                    33,
                )
        }

    @Test
    fun `frozen strategy overrides leverage and take profit in order params`(): Unit =
        runBlocking {
            val frozen = frozen(slPoints = 300, tpPoints = 600, leverage = 2.0, riskPerTrade = 11.0, maxContracts = 33)

            profile.buildOrderParams("Si", PositionDirection.LONG, BigDecimal("92000"), size(), request(frozen = frozen))

            Mockito
                .verify(orderBuilder)
                .buildFuturesOrderParams(
                    ticker = "Si",
                    direction = PositionDirection.LONG,
                    entryPrice = BigDecimal("92000"),
                    currentGo = BigDecimal("850"),
                    size = size(),
                    leverage = BigDecimal("2.0"),
                    stopLossPoints = 300,
                    takeProfitPointsOverride = 600,
                )
        }

    @Test
    fun `non positive frozen values fall back to config and instrument defaults`(): Unit =
        runBlocking {
            val frozen = frozen(slPoints = 0, tpPoints = 0, leverage = 0.0, riskPerTrade = null, maxContracts = null)
            Mockito.`when`(leverageConfig.effective()).thenReturn(BigDecimal.ONE)
            Mockito.`when`(candleCache.calculateAtr("Si", "MINUTE_10", 14)).thenReturn(null)

            profile.buildOrderParams("Si", PositionDirection.LONG, BigDecimal("92000"), size(), request(frozen = frozen))

            Mockito.verify(orderBuilder).buildFuturesOrderParams(
                ticker = "Si",
                direction = PositionDirection.LONG,
                entryPrice = BigDecimal("92000"),
                currentGo = BigDecimal("850"),
                size = size(),
                leverage = BigDecimal.ONE,
                stopLossPoints = 50,
                takeProfitPointsOverride = null,
            )
        }

    @Test
    fun `pre sizing rejects correlated and sector-correlated candidates`(): Unit =
        runBlocking {
            Mockito.`when`(adaptiveRisk.exceedsCorrelationLimit("Si", emptyList())).thenReturn(true)

            assertEquals("CORRELATION", profile.preSizingChecks("Si", emptyList()))

            Mockito.`when`(adaptiveRisk.exceedsCorrelationLimit("Si", emptyList())).thenReturn(false)
            Mockito.`when`(adaptiveRisk.exceedsSectorCorrelationLimit("Si", emptyList())).thenReturn(true)

            assertEquals("SECTOR_CORRELATION", profile.preSizingChecks("Si", emptyList()))
        }

    private fun frozen(
        slPoints: Int?,
        tpPoints: Int?,
        leverage: Double,
        riskPerTrade: Double?,
        maxContracts: Int?,
    ) = com.trading.bot.backtest.FrozenStrategy(
        ticker = "Si",
        strategyVersion = "v1",
        gitCommitSha = null,
        slPercent = null,
        tpPercent = null,
        slPoints = slPoints,
        tpPoints = tpPoints,
        confidenceThreshold = null,
        leverage = leverage,
        riskPerTradePercent = riskPerTrade,
        futuresMaxContractsPerPosition = maxContracts,
    )
}
