package com.trading.bot.domain.risk

import com.trading.bot.application.TradingHoursGuard
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.repository.DailyRiskSnapshotRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Проверка risk-first логики FuturesRiskEngine.
 */
class FuturesRiskEngineTest {

    private val riskConfig = RiskConfig()
    private val leverageConfig = LeverageConfig()
    private val instrumentsConfig = InstrumentsConfig().apply {
        instruments = mutableListOf(
            InstrumentsConfig.InstrumentSpec(
                ticker = "Si",
                type = "FUTURES",
                lotSize = 1,
                priceStep = BigDecimal("0.01"),
                priceStepCost = BigDecimal("10.0"),
                go = BigDecimal("15000"),
                leverage = BigDecimal("2.0"),
                baseAsset = "USD"
            )
        )
    }
    private val positionRepo: PositionRepository = Mockito.mock(PositionRepository::class.java)
    private val dailyRiskRepo: DailyRiskSnapshotRepository = Mockito.mock(DailyRiskSnapshotRepository::class.java)

    private fun engine(openGuard: Boolean = true): FuturesRiskEngine {
        val guardConfig = RiskConfig().apply {
            if (openGuard) {
                tradingHoursStart = "00:00"
                tradingHoursEnd = "23:59"
            } else {
                tradingHoursStart = "19:00"
                tradingHoursEnd = "18:00"
            }
        }
        return FuturesRiskEngine(
            riskConfig = riskConfig,
            leverageConfig = leverageConfig,
            positionSizer = FuturesPositionSizer(leverageConfig, riskConfig, instrumentsConfig),
            positionRepo = positionRepo,
            tradingHoursGuard = TradingHoursGuard(guardConfig),
            instrumentsConfig = instrumentsConfig,
            dailyRiskSnapshotRepo = dailyRiskRepo,
            meterRegistry = SimpleMeterRegistry()
        )
    }

    @Test
    fun `entry allowed within all limits`() = runBlocking {
        Mockito.`when`(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(emptyList())

        val result = engine().validateEntry(
            ticker = "Si",
            entryPrice = BigDecimal("92000"),
            direction = PositionDirection.LONG,
            portfolioMoney = BigDecimal("50000"),
            currentGo = BigDecimal("15000")
        )

        assertTrue(result.allowed)
        assertEquals(1, result.quantity)
        assertEquals(0, BigDecimal("7500").compareTo(result.marginRequired))
        // SL = 92000 - 50*0.01 = 91999.50, TP = 92000 + 100*0.01 = 92001.00 (R:R 1:2)
        assertEquals(0, BigDecimal("91999.50").compareTo(result.stopLossPrice))
        assertEquals(0, BigDecimal("92001").compareTo(result.takeProfitPrice))
        val liq = requireNotNull(result.liquidationPrice)
        assertEquals(0, BigDecimal("91985").compareTo(liq))
    }

    @Test
    fun `entry blocked after daily loss limit reached`() = runBlocking {
        val e = engine()
        e.updateDailyPnL(BigDecimal("-3000"))
        e.updateDailyPnL(BigDecimal("-2000")) // суммарно -5000 = 10% депозита

        assertTrue(e.isDailyLossLimitReached())

        val result = e.validateEntry(
            ticker = "Si",
            entryPrice = BigDecimal("92000"),
            direction = PositionDirection.LONG,
            portfolioMoney = BigDecimal("50000"),
            currentGo = BigDecimal("15000")
        )

        assertFalse(result.allowed)
        assertEquals("DAILY_LIMIT", result.reason)
    }

    @Test
    fun `entry blocked outside trading hours`() = runBlocking {
        val result = engine(openGuard = false).validateEntry(
            ticker = "Si",
            entryPrice = BigDecimal("92000"),
            direction = PositionDirection.LONG,
            portfolioMoney = BigDecimal("50000"),
            currentGo = BigDecimal("15000")
        )

        assertFalse(result.allowed)
        assertEquals("OUTSIDE_HOURS", result.reason)
    }

    @Test
    fun `entry blocked when position already open`() = runBlocking {
        Mockito.`when`(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(
            listOf(
                Position(
                    ticker = "Si",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("92000"),
                    instrumentType = InstrumentType.FUTURES
                )
            )
        )

        val result = engine().validateEntry(
            ticker = "Si",
            entryPrice = BigDecimal("92000"),
            direction = PositionDirection.LONG,
            portfolioMoney = BigDecimal("50000"),
            currentGo = BigDecimal("15000")
        )

        assertFalse(result.allowed)
        assertEquals("MAX_POSITIONS", result.reason)
    }

    @Test
    fun `liquidation status thresholds`() {
        val e = engine()
        val pos = Position(
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("92000"),
            currentPrice = BigDecimal("92000"),
            liquidationPrice = BigDecimal("91985"), // буфер 15
            instrumentType = InstrumentType.FUTURES
        )

        // остаток 4/15 = 26.7% → SAFE
        assertEquals(
            FuturesRiskEngine.LiquidationStatus.SAFE,
            e.checkLiquidationDistance(pos, BigDecimal("91989"))
        )
        // остаток 3/15 = 20% → WARNING
        assertEquals(
            FuturesRiskEngine.LiquidationStatus.WARNING,
            e.checkLiquidationDistance(pos, BigDecimal("91988"))
        )
        // остаток 1/15 = 6.7% → CRITICAL
        assertEquals(
            FuturesRiskEngine.LiquidationStatus.CRITICAL,
            e.checkLiquidationDistance(pos, BigDecimal("91986"))
        )
    }

    @Test
    fun `trailing stop moves only in profit and never below hard stop`() {
        val e = engine()
        val pos = Position(
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("92000"),
            currentPrice = BigDecimal("92000"),
            stopLoss = BigDecimal("91950"),
            trailingStopPrice = BigDecimal("91950"),
            instrumentType = InstrumentType.FUTURES,
            variationMargin = BigDecimal.ZERO
        )

        // цена упала → вариационная маржа < 0, trailing не двигается
        e.updateTrailingStop(pos, BigDecimal("91900"))
        assertEquals(BigDecimal("91950"), pos.trailingStopPrice)

        // цена выросла до 94000 → trailing = 94000 * 0.99 = 93060
        e.updateTrailingStop(pos, BigDecimal("94000"))
        assertEquals(BigDecimal("93060.0000"), pos.trailingStopPrice)
        // вариационная маржа = (94000 - 92000) * 1000 * 1 = 2 000 000 ₽
        assertEquals(0, BigDecimal("2000000").compareTo(pos.variationMargin))
    }
}
