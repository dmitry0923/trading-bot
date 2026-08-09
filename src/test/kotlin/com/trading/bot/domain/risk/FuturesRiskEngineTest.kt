package com.trading.bot.domain.risk

import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Проверка risk-first логики FuturesRiskEngine (только Да/Нет — canEnter).
 */
class FuturesRiskEngineTest {
    private val riskConfig = RiskConfig()
    private val leverageConfig = LeverageConfig()
    private val instrumentsConfig =
        InstrumentsConfig().apply {
            instruments =
                mutableListOf(
                    InstrumentsConfig.InstrumentSpec(
                        ticker = "Si",
                        type = "FUTURES",
                        lotSize = 1,
                        priceStep = BigDecimal("0.01"),
                        priceStepCost = BigDecimal("10.0"),
                        go = BigDecimal("15000"),
                        leverage = BigDecimal("2.0"),
                        baseAsset = "USD",
                    ),
                )
        }
    private val dailyRiskGuard: DailyRiskGuard = Mockito.mock(DailyRiskGuard::class.java)
    private val volatilityFilter: VolatilityFilter = Mockito.mock(VolatilityFilter::class.java)

    private fun engine(openGuard: Boolean = true): FuturesRiskEngine =
        FuturesRiskEngine(
            riskConfig = riskConfig,
            leverageConfig = leverageConfig,
            tradingCalendar = { openGuard },
            dailyRiskGuard = dailyRiskGuard,
            volatilityFilter = volatilityFilter,
            instrumentsConfig = instrumentsConfig,
            meterRegistry = SimpleMeterRegistry(),
        )

    private fun entryRequest(
        openPositions: List<Position> = emptyList(),
        ticker: String = "Si",
    ) = EntryRequest(
        ticker = ticker,
        action = StrategyAction.BUY,
        entryPrice = BigDecimal("92000"),
        direction = PositionDirection.LONG,
        portfolioMoney = BigDecimal("50000"),
        currentGo = BigDecimal("15000"),
        openPositions = openPositions,
    )

    @Test
    fun `entry allowed within all limits`() =
        runBlocking {
            val result = engine().canEnter(entryRequest())

            assertTrue(result is RiskVerdict.Allowed)
        }

    @Test
    fun `entry blocked after daily loss limit reached`() =
        runBlocking {
            Mockito.`when`(dailyRiskGuard.isDailyLossLimitReached()).thenReturn(true)

            val result = engine().canEnter(entryRequest())

            assertEquals(RiskVerdict.Rejected("DAILY_LIMIT"), result)
        }

    @Test
    fun `entry blocked outside trading hours`() =
        runBlocking {
            val result = engine(openGuard = false).canEnter(entryRequest())

            assertEquals(RiskVerdict.Rejected("OUTSIDE_HOURS"), result)
        }

    @Test
    fun `entry blocked when position already open`() =
        runBlocking {
            val open =
                listOf(
                    Position(
                        ticker = "Si",
                        direction = PositionDirection.LONG,
                        quantity = 1,
                        entryPrice = BigDecimal("92000"),
                        instrumentType = InstrumentType.FUTURES,
                    ),
                )

            val result = engine().canEnter(entryRequest(openPositions = open))

            assertEquals(RiskVerdict.Rejected("MAX_POSITIONS"), result)
        }

    @Test
    fun `entry blocked for unsupported instrument`() =
        runBlocking {
            val result = engine().canEnter(entryRequest(ticker = "SBER"))

            assertEquals(RiskVerdict.Rejected("UNSUPPORTED_INSTRUMENT"), result)
        }

    @Test
    fun `liquidation status thresholds`() {
        val e = engine()
        val pos =
            Position(
                ticker = "Si",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("92000"),
                currentPrice = BigDecimal("92000"),
                liquidationPrice = BigDecimal("91985"), // буфер 15
                instrumentType = InstrumentType.FUTURES,
            )

        // остаток 4/15 = 26.7% → SAFE
        assertEquals(
            FuturesRiskEngine.LiquidationStatus.SAFE,
            e.checkLiquidationDistance(pos, BigDecimal("91989")),
        )
        // остаток 3/15 = 20% → WARNING
        assertEquals(
            FuturesRiskEngine.LiquidationStatus.WARNING,
            e.checkLiquidationDistance(pos, BigDecimal("91988")),
        )
        // остаток 1/15 = 6.7% → CRITICAL
        assertEquals(
            FuturesRiskEngine.LiquidationStatus.CRITICAL,
            e.checkLiquidationDistance(pos, BigDecimal("91986")),
        )
    }

    @Test
    fun `futures trailing stop moves only in profit and never below hard stop`() {
        val pos =
            Position(
                ticker = "Si",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("92000"),
                currentPrice = BigDecimal("92000"),
                stopLoss = BigDecimal("91950"),
                trailingStopPrice = BigDecimal("91950"),
                instrumentType = InstrumentType.FUTURES,
                variationMargin = BigDecimal.ZERO,
            )

        // цена упала → вариационная маржа < 0, trailing не двигается
        ExitRules.updateFuturesTrailingStop(pos, BigDecimal("91900"), riskConfig.trailingStopPercent, BigDecimal("1000"))
        assertEquals(BigDecimal("91950"), pos.trailingStopPrice)

        // цена выросла до 94000 → trailing = 94000 * 0.99 = 93060
        ExitRules.updateFuturesTrailingStop(pos, BigDecimal("94000"), riskConfig.trailingStopPercent, BigDecimal("1000"))
        assertEquals(BigDecimal("93060.0000"), pos.trailingStopPrice)
        // вариационная маржа = (94000 - 92000) * 1000 * 1 = 2 000 000 ₽
        assertEquals(0, BigDecimal("2000000").compareTo(pos.variationMargin))
    }
}
