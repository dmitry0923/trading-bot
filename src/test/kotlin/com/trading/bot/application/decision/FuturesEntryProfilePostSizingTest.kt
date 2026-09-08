package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.FuturesStopResolver
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.LiveFrozenStrategyResolver
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.TradingAccountService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * P0/P1-аудит FuturesEntryProfile.postSizingChecks:
 * - отсутствующий InstrumentSpec — FAIL-CLOSED (INSTRUMENT_SPEC_MISSING), а не fallback
 *   price × qty (математически неверен для фьючерса: забывает lotSize);
 * - маржинальный гейт вызывается СО СТРЕСС-ЗАПАСОМ: candidate × stressedMarginMultiplier;
 * - недоступность фактического ГО открытых позиций — FAIL-CLOSED (PORTFOLIO_MARGIN_DATA_UNAVAILABLE).
 */
class FuturesEntryProfilePostSizingTest {
    private val instrumentsConfig = InstrumentsConfig()

    private fun profile(risk: RiskManagementService): FuturesEntryProfile =
        FuturesEntryProfile(
            futuresRiskEngine = Mockito.mock(FuturesRiskEngine::class.java),
            futuresPositionSizer = Mockito.mock(FuturesPositionSizer::class.java),
            orderBuilder = Mockito.mock(OrderBuilder::class.java),
            alorFuturesClient = Mockito.mock(AlorFuturesClient::class.java),
            riskConfig = RiskConfig(),
            leverageConfig = Mockito.mock(LeverageConfig::class.java),
            instrumentsConfig = instrumentsConfig,
            meterRegistry = SimpleMeterRegistry(),
            tradingAccountService = Mockito.mock(TradingAccountService::class.java),
            candleCache = Mockito.mock(CandleCacheService::class.java),
            futuresStopResolver = FuturesStopResolver(),
            liveFrozenStrategyResolver = Mockito.mock(LiveFrozenStrategyResolver::class.java),
            adaptiveRisk = Mockito.mock(AdaptiveRiskService::class.java),
            risk = risk,
        )

    private fun request(ticker: String): EntryRequest =
        EntryRequest(
            ticker = ticker,
            action = StrategyAction.BUY,
            entryPrice = BigDecimal("12.80"),
            direction = PositionDirection.LONG,
            portfolioMoney = BigDecimal("100000"),
            currentGo = BigDecimal("850"),
            openPositions = emptyList(),
            accountId = 1L,
        )

    private fun size(marginRequired: BigDecimal): PositionSizeResult =
        PositionSizeResult(
            quantity = 1,
            marginRequired = marginRequired,
            riskAmount = BigDecimal("500"),
            liquidationPrice = null,
            reason = null,
        )

    @Test
    fun `missing instrument spec blocks entry fail-closed`() =
        runBlocking {
            val risk = Mockito.mock(RiskManagementService::class.java)
            val result = profile(risk).postSizingChecks(request("UNKNOWN"), PositionDirection.LONG, size(BigDecimal("1000")), emptyList())

            assertEquals("INSTRUMENT_SPEC_MISSING", result)
            Mockito.verifyNoInteractions(risk)
        }

    @Test
    fun `zero size maps to ZERO_RISK_SIZE before spec lookup`() =
        runBlocking {
            val risk = Mockito.mock(RiskManagementService::class.java)
            val zero =
                PositionSizeResult(
                    quantity = 0,
                    marginRequired = BigDecimal.ZERO,
                    riskAmount = BigDecimal.ZERO,
                    liquidationPrice = null,
                    reason = "RISK_CAP",
                )
            val result = profile(risk).postSizingChecks(request("CNYRUBF"), PositionDirection.LONG, zero, emptyList())

            assertEquals("ZERO_RISK_SIZE", result)
            Mockito.verifyNoInteractions(risk)
        }

    @Test
    fun `margin gate receives candidate GO times stressed margin multiplier`() =
        runBlocking {
            val risk = Mockito.mock(RiskManagementService::class.java)
            // exceedsMarginUtilization — false (лимит не превышен), exceedsPortfolioLimits — false.
            val captured = mutableListOf<BigDecimal>()
            Mockito
                .`when`(risk.exceedsMarginUtilization(anyBigDecimal(), anyBigDecimal(), anyBigDecimal()))
                .thenAnswer { inv ->
                    captured += inv.getArgument<BigDecimal>(2)
                    false
                }
            // ГО существующих позиций известно (0 — позиций нет): гарантирует проход к гейту.
            runBlocking {
                Mockito.`when`(risk.freshMarginOfPositions(anyList())).thenReturn(BigDecimal.ZERO)
            }
            val profile = profile(risk)

            val result = profile.postSizingChecks(request("CNYRUBF"), PositionDirection.LONG, size(BigDecimal("850")), emptyList())

            assertNull(result)
            // 850 × 1.5 = 1275 — стресс-запас для маржинального гейта.
            assertEquals(1, captured.size)
            assertEquals(0, BigDecimal("1275").compareTo(captured.single()))
        }

    @Test
    fun `unavailable margin data of open positions blocks entry fail-closed`() =
        runBlocking {
            val risk = Mockito.mock(RiskManagementService::class.java)
            // Данные о ГО существующих позиций недоступны (API down + устаревший кэш /
            // marginUsed не записан) → вход блокируется до вызова маржинального гейта.
            runBlocking {
                Mockito.`when`(risk.freshMarginOfPositions(anyList())).thenReturn(null)
            }

            val result = profile(risk).postSizingChecks(request("CNYRUBF"), PositionDirection.LONG, size(BigDecimal("850")), emptyList())

            assertEquals("PORTFOLIO_MARGIN_DATA_UNAVAILABLE", result)
            Mockito.verify(risk, Mockito.never()).exceedsMarginUtilization(anyBigDecimal(), anyBigDecimal(), anyBigDecimal())
        }

    @Suppress("UNCHECKED_CAST")
    private fun anyList(): List<com.trading.bot.model.entity.Position> {
        Mockito.any(List::class.java)
        return emptyList()
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }
}
