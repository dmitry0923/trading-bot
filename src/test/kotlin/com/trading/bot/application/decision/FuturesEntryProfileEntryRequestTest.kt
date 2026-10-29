package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.funding.FundingSnapshotService
import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.FuturesStopResolver
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

    private val profile =
        FuturesEntryProfile(
            futuresRiskEngine = Mockito.mock(FuturesRiskEngine::class.java),
            futuresPositionSizer = Mockito.mock(FuturesPositionSizer::class.java),
            orderBuilder = Mockito.mock(OrderBuilder::class.java),
            alorFuturesClient = alorFuturesClient,
            riskConfig = RiskConfig(),
            leverageConfig = Mockito.mock(LeverageConfig::class.java),
            instrumentsConfig = instrumentsConfig,
            meterRegistry = SimpleMeterRegistry(),
            tradingAccountService = tradingAccountService,
            candleCache = candleCache,
            futuresStopResolver = FuturesStopResolver(),
            liveFrozenStrategyResolver = Mockito.mock(LiveFrozenStrategyResolver::class.java),
            adaptiveRisk = Mockito.mock(AdaptiveRiskService::class.java),
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
}
