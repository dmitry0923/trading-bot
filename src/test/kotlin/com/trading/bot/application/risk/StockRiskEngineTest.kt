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

    private fun engine(
        config: RiskConfig,
        calendar: TradingCalendar,
        leverage: LeverageConfig,
        daily: DailyRiskGuard,
        volatility: VolatilityFilter,
    ): StockRiskEngine =
        StockRiskEngine(
            riskConfig = config,
            leverageConfig = leverage,
            tradingCalendar = calendar,
            dailyRiskGuard = daily,
            volatilityFilter = volatility,
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

    @Test
    fun `disabled risk and leverage reject before any other gate`() =
        runBlocking {
            val disabledRisk = RiskConfig().apply { enabled = false }
            assertEquals(
                RiskVerdict.Rejected("RISK_DISABLED"),
                engine(disabledRisk).canEnter(entryRequest(atr = BigDecimal("1"))),
            )

            val disabledLeverage = LeverageConfig().apply { enabled = false }
            assertEquals(
                RiskVerdict.Rejected("LEVERAGE_DISABLED"),
                engine(RiskConfig(), { true }, disabledLeverage, dailyRiskGuard, volatilityFilter)
                    .canEnter(entryRequest(atr = BigDecimal("1"))),
            )
        }

    @Test
    fun `calendar daily and volatility index guards reject`() =
        runBlocking {
            assertEquals(
                RiskVerdict.Rejected("OUTSIDE_HOURS"),
                engine(RiskConfig(), { false }, leverageConfig, dailyRiskGuard, volatilityFilter)
                    .canEnter(entryRequest(atr = BigDecimal("1"))),
            )

            Mockito.`when`(dailyRiskGuard.isDailyLossLimitReached(Mockito.nullable(Long::class.javaObjectType))).thenReturn(true)
            assertEquals(
                RiskVerdict.Rejected("DAILY_LIMIT"),
                engine().canEnter(entryRequest(atr = BigDecimal("1"))),
            )
            Mockito.`when`(dailyRiskGuard.isDailyLossLimitReached(Mockito.nullable(Long::class.javaObjectType))).thenReturn(false)

            Mockito.`when`(dailyRiskGuard.isEntryBlocked(Mockito.nullable(Long::class.javaObjectType))).thenReturn(true)
            assertEquals(
                RiskVerdict.Rejected("DRAWDOWN_PROTECTION"),
                engine().canEnter(entryRequest(atr = BigDecimal("1"))),
            )
            Mockito.`when`(dailyRiskGuard.isEntryBlocked(Mockito.nullable(Long::class.javaObjectType))).thenReturn(false)

            Mockito.`when`(volatilityFilter.isVolatilityAnomalous()).thenReturn(true)
            assertEquals(
                RiskVerdict.Rejected("VOLATILITY_INDEX"),
                engine().canEnter(entryRequest(atr = BigDecimal("1"))),
            )
            Mockito.`when`(volatilityFilter.isVolatilityAnomalous()).thenReturn(false)
        }

    @Test
    fun `invalid inputs duplicate and position cap reject`() =
        runBlocking {
            val lenient = RiskConfig().apply { volatilityFailClosed = false }
            assertEquals(
                RiskVerdict.Rejected("INVALID_INPUT"),
                engine(lenient).canEnter(entryRequest(atr = BigDecimal("1")).copy(entryPrice = BigDecimal.ZERO)),
            )
            assertEquals(
                RiskVerdict.Rejected("INVALID_INPUT"),
                engine(lenient).canEnter(entryRequest(atr = BigDecimal("1")).copy(portfolioMoney = BigDecimal.ZERO)),
            )

            val existing =
                com.trading.bot.model.entity.Position(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("100"),
                )
            assertEquals(
                RiskVerdict.Rejected("DUPLICATE_POSITION"),
                engine().canEnter(entryRequest(atr = BigDecimal("1")).copy(openPositions = listOf(existing))),
            )

            val other =
                existing.copy(ticker = "GAZP")
            assertEquals(
                RiskVerdict.Rejected("MAX_POSITIONS"),
                engine(RiskConfig().apply { maxOpenPositions = 1 })
                    .canEnter(entryRequest(atr = BigDecimal("1")).copy(openPositions = listOf(other))),
            )
            assertEquals(
                RiskVerdict.Rejected("MAX_POSITIONS"),
                engine(RiskConfig().apply { maxOpenPositions = 5 })
                    .canEnter(
                        entryRequest(atr = BigDecimal("1"))
                            .copy(openPositions = listOf(other), maxOpenPositions = 1),
                    ),
            )
        }

    @Test
    fun `sector exposure counts the same sector and unknown tickers`() =
        runBlocking {
            val config =
                RiskConfig().apply {
                    maxSectorExposure = 1
                    maxOpenPositions = 5
                    sectors = mapOf("SBER" to "BANKS", "VTBR" to "BANKS")
                }
            val sameSector =
                com.trading.bot.model.entity.Position(
                    ticker = "VTBR",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("100"),
                )
            assertEquals(
                RiskVerdict.Rejected("SECTOR_EXPOSURE"),
                engine(config).canEnter(entryRequest(atr = BigDecimal("1")).copy(openPositions = listOf(sameSector))),
            )

            val unknownSector =
                com.trading.bot.model.entity.Position(
                    ticker = "ZZZZ",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("100"),
                )
            assertEquals(
                RiskVerdict.Rejected("SECTOR_EXPOSURE"),
                engine(
                    RiskConfig().apply {
                        maxSectorExposure = 1
                        maxOpenPositions = 5
                    },
                ).canEnter(entryRequest(atr = BigDecimal("1")).copy(ticker = "YYYY", openPositions = listOf(unknownSector))),
            )
        }

    @Test
    fun `volatility check is inert when risk is disabled or fail-closed is off`() =
        runBlocking {
            assertEquals(
                RiskVerdict.Rejected("RISK_DISABLED"),
                engine(RiskConfig().apply { enabled = false }).canEnter(entryRequest(atr = null)),
            )

            val lenient = RiskConfig().apply { volatilityFailClosed = false }
            assertEquals(
                RiskVerdict.Rejected("INVALID_INPUT"),
                engine(lenient).canEnter(entryRequest(atr = BigDecimal("1")).copy(entryPrice = BigDecimal.ZERO)),
            )
        }
}
