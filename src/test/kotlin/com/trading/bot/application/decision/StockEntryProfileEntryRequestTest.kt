package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.risk.StockRiskEngine
import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.signal.Signal
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Unit-тесты [StockEntryProfile.buildEntryRequest]: сборка запроса на вход для акций/FX.
 *
 * Непокрытые ветки (скан-19): AUM недоступен → **fail-closed** (вход блокируется, депозит
 * не подменяется конфигом), успешная сборка с ATR, `currentGo = 0` для акций и
 * проброс frozen-стратегии.
 */
class StockEntryProfileEntryRequestTest {
    private val aumProvider = Mockito.mock(AumProvider::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val frozenResolver = Mockito.mock(LiveFrozenStrategyResolver::class.java)

    private val profile =
        StockEntryProfile(
            stockRiskEngine = Mockito.mock(StockRiskEngine::class.java),
            adaptiveRisk = Mockito.mock(AdaptiveRiskService::class.java),
            risk = Mockito.mock(RiskManagementService::class.java),
            candleCache = candleCache,
            orderBuilder = Mockito.mock(OrderBuilder::class.java),
            riskConfig = RiskConfig(),
            instrumentsConfig = InstrumentsConfig(),
            agentLogRepo = Mockito.mock(AgentLogRepository::class.java),
            aumProvider = aumProvider,
            liveFrozenStrategyResolver = frozenResolver,
        )

    private fun signal(ticker: String = "SBER"): Signal =
        Signal(
            ticker = ticker,
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("250"),
            signalStrength = 0.7,
            reasoning = "test",
            timeframe = "MINUTE_10",
            cycleId = "cycle-1",
        )

    private fun frozen() =
        FrozenStrategy(
            ticker = "SBER",
            strategyVersion = "v1",
            gitCommitSha = null,
            slPercent = 0.03,
            tpPercent = 0.09,
            slPoints = null,
            tpPoints = null,
            confidenceThreshold = 0.6,
            leverage = 1.0,
            riskPerTradePercent = 1.0,
            futuresMaxContractsPerPosition = null,
        )

    @Test
    fun `unavailable AUM blocks entry fail-closed`(): Unit =
        runBlocking {
            Mockito
                .`when`(aumProvider.currentAumChecked(Mockito.nullable(Long::class.java)))
                .thenReturn(AumProvider.AumResult.Unavailable)

            assertNull(profile.buildEntryRequest(signal(), BigDecimal("250"), emptyList(), 1L))
        }

    @Test
    fun `available AUM builds request with atr and zero go`(): Unit =
        runBlocking {
            Mockito
                .`when`(aumProvider.currentAumChecked(1L))
                .thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), ageMs = 1000))
            Mockito.`when`(candleCache.calculateAtr("SBER", "MINUTE_10", 14)).thenReturn(BigDecimal("3.5"))
            Mockito.`when`(frozenResolver.resolveActive("SBER")).thenReturn(null)

            val request = profile.buildEntryRequest(signal(), BigDecimal("250"), emptyList(), 1L)

            assertNotNull(request)
            assertEquals(BigDecimal("50000"), request!!.portfolioMoney)
            assertEquals(BigDecimal.ZERO, request.currentGo, "для акций ГО не используется")
            assertEquals(BigDecimal("3.5"), request.atr)
            assertEquals(PositionDirection.LONG, request.direction)
            assertEquals(1L, request.accountId)
        }

    @Test
    fun `frozen strategy is propagated into the request`(): Unit =
        runBlocking {
            Mockito
                .`when`(aumProvider.currentAumChecked(1L))
                .thenReturn(AumProvider.AumResult.Available(BigDecimal("50000"), ageMs = 1000))
            Mockito.`when`(candleCache.calculateAtr("SBER", "MINUTE_10", 14)).thenReturn(BigDecimal("3.5"))
            val frozen = frozen()
            Mockito.`when`(frozenResolver.resolveActive("SBER")).thenReturn(frozen)

            val request = profile.buildEntryRequest(signal(), BigDecimal("250"), emptyList(), 1L)

            assertEquals(frozen, request?.frozenStrategy)
        }
}
