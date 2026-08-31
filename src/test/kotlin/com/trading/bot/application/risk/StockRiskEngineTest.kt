package com.trading.bot.application.risk

import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.DailyRiskGuard
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.risk.TradingCalendar
import com.trading.bot.domain.risk.VolatilityFilter
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Fail-closed семантика ATR-гейта волатильности (VOLATILITY_GUARD) для
 * акций: если ATR/цена недоступны или невалидны, а риск включён — вход
 * блокируется (risk.volatility-fail-closed), вместо молчаливого пропуска.
 */
class StockRiskEngineTest {
    private val riskConfig = RiskConfig()
    private val leverageConfig = LeverageConfig()
    private val calendar: TradingCalendar = { true }
    private val dailyRiskGuard: DailyRiskGuard = Mockito.mock(DailyRiskGuard::class.java)
    private val volatilityFilter: VolatilityFilter = Mockito.mock(VolatilityFilter::class.java)

    private fun engine(config: RiskConfig = riskConfig): StockRiskEngine =
        StockRiskEngine(
            riskConfig = config,
            leverageConfig = leverageConfig,
            tradingCalendar = calendar,
            dailyRiskGuard = dailyRiskGuard,
            volatilityFilter = volatilityFilter,
            meterRegistry = SimpleMeterRegistry(),
        )

    private fun entryRequest(atr: BigDecimal?): EntryRequest =
        EntryRequest(
            ticker = "SBER",
            action = StrategyAction.BUY,
            entryPrice = BigDecimal("100"),
            direction = PositionDirection.LONG,
            portfolioMoney = BigDecimal("50000"),
            currentGo = BigDecimal.ZERO,
            atr = atr,
        )

    @Test
    fun `null ATR blocks entry by default (fail-closed)`() =
        runBlocking {
            val result = engine().canEnter(entryRequest(atr = null))
            assertTrue(result is RiskVerdict.Rejected)
            assertEquals("VOLATILITY_GUARD", (result as RiskVerdict.Rejected).reason)
        }

    @Test
    fun `non-positive ATR blocks entry by default (fail-closed)`() =
        runBlocking {
            val zero = engine().canEnter(entryRequest(atr = BigDecimal.ZERO))
            assertTrue(zero is RiskVerdict.Rejected)
            assertEquals("VOLATILITY_GUARD", (zero as RiskVerdict.Rejected).reason)

            val negative = engine().canEnter(entryRequest(atr = BigDecimal("-1")))
            assertTrue(negative is RiskVerdict.Rejected)
            assertEquals("VOLATILITY_GUARD", (negative as RiskVerdict.Rejected).reason)
        }

    @Test
    fun `null ATR allowed when fail-closed disabled`() =
        runBlocking {
            val config = RiskConfig().apply { volatilityFailClosed = false }
            val result = engine(config).canEnter(entryRequest(atr = null))
            assertTrue(result is RiskVerdict.Allowed)
        }

    @Test
    fun `normal ATR below limit is allowed`() =
        runBlocking {
            // ATR = 1.0 на цене 100 → ATR% = 1% < 5% (maxVolatilityPercent).
            val result = engine().canEnter(entryRequest(atr = BigDecimal("1")))
            assertTrue(result is RiskVerdict.Allowed)
        }

    @Test
    fun `ATR above limit blocks`() =
        runBlocking {
            // ATR = 10 на цене 100 → ATR% = 10% > 5% (maxVolatilityPercent).
            val result = engine().canEnter(entryRequest(atr = BigDecimal("10")))
            assertTrue(result is RiskVerdict.Rejected)
            assertEquals("VOLATILITY_GUARD", (result as RiskVerdict.Rejected).reason)
        }
}
