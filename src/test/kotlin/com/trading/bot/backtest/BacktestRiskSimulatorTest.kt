package com.trading.bot.backtest

import com.trading.bot.application.decision.NetEvGate
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Тесты полного набора risk gates в [BacktestRiskSimulator] — зеркало
 * DecisionEngine.doOpenPosition в in-memory окружении.
 */
class BacktestRiskSimulatorTest {
    private lateinit var riskConfig: RiskConfig
    private lateinit var instrumentsConfig: InstrumentsConfig
    private lateinit var simulator: BacktestRiskSimulator

    private val capital: BigDecimal = BigDecimal("100000")

    @BeforeEach
    fun setUp() {
        riskConfig = makeRiskConfig()
        instrumentsConfig = makeInstrumentsConfig()
        simulator = initSimulator(riskConfig, instrumentsConfig)
    }

    // ===== Helpers =====

    private fun bd(value: String): BigDecimal = BigDecimal(value)

    /** Дата относительно «сегодня» — чтобы rolling-окна (LocalDateTime.now()) оставались валидными. */
    private fun at(
        dayOffset: Long,
        hour: Int,
    ): LocalDateTime = LocalDate.now().plusDays(dayOffset).atTime(hour, 0)

    private fun makeCandle(
        time: LocalDateTime,
        open: String,
        high: String,
        low: String,
        close: String,
        ticker: String = "TEST",
    ): Candle =
        Candle(
            ticker = ticker,
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(open),
            highPrice = BigDecimal(high),
            lowPrice = BigDecimal(low),
            closePrice = BigDecimal(close),
            volume = 10_000L,
            time = time,
        )

    /** Последовательность из `count` свечей с нулевым гэпом между соседними close/open. */
    private fun historyOf(
        open: String,
        high: String,
        low: String,
        close: String,
        count: Int = 20,
        dayOffset: Long = -1,
    ): List<Candle> =
        (0 until count).map { i ->
            makeCandle(at(dayOffset, 9).plusMinutes(10L * i), open = open, high = high, low = low, close = close)
        }

    /** Спокойная история: диапазон 1.0 при цене 100 → ATR = 1.0, ATR% = 1%. */
    private fun normalHistory(count: Int = 20): List<Candle> =
        historyOf(open = "100.00", high = "100.50", low = "99.50", close = "100.00", count = count)

    private fun entryCandle(time: LocalDateTime = at(0, 10)): Candle =
        makeCandle(time, open = "100.00", high = "100.50", low = "99.50", close = "100.00")

    private fun makeRiskConfig(): RiskConfig =
        RiskConfig().apply {
            maxOpenPositions = 5
            maxSectorExposure = 3
            riskPerTradePercent = 1.0
            // Gate 11 выключен по умолчанию: одна позиция всегда даёт effectivePositions = 1.0 < 1.5
            portfolioRiskEnabled = false
            sectors = mapOf("TEST" to "TECH", "TEST2" to "TECH", "TEST3" to "TECH")
        }

    private fun makeInstrumentsConfig(): InstrumentsConfig =
        InstrumentsConfig().apply {
            instruments =
                listOf(
                    InstrumentsConfig.InstrumentSpec(
                        ticker = "TEST",
                        type = "STOCK",
                        lotSize = 10,
                        priceStep = BigDecimal("0.01"),
                        priceStepCost = BigDecimal("0.1"),
                        go = BigDecimal.ZERO,
                        leverage = BigDecimal("1.0"),
                        baseAsset = "RUB",
                    ),
                    InstrumentsConfig.InstrumentSpec(
                        ticker = "TEST2",
                        type = "STOCK",
                        lotSize = 10,
                        priceStep = BigDecimal("0.01"),
                        priceStepCost = BigDecimal("0.1"),
                        go = BigDecimal.ZERO,
                        leverage = BigDecimal("1.0"),
                        baseAsset = "RUB",
                    ),
                    InstrumentsConfig.InstrumentSpec(
                        ticker = "TEST3",
                        type = "STOCK",
                        lotSize = 10,
                        priceStep = BigDecimal("0.01"),
                        priceStepCost = BigDecimal("0.1"),
                        go = BigDecimal.ZERO,
                        leverage = BigDecimal("1.0"),
                        baseAsset = "RUB",
                    ),
                )
        }

    private fun initSimulator(
        riskConfig: RiskConfig,
        instrumentsConfig: InstrumentsConfig,
        netEvGate: NetEvGate? = null,
    ): BacktestRiskSimulator =
        BacktestRiskSimulator(riskConfig, instrumentsConfig, netEvGate).apply {
            initialize(capital)
        }

    private suspend fun checkEntry(
        sim: BacktestRiskSimulator = simulator,
        ticker: String = "TEST",
        cash: BigDecimal = capital,
        candle: Candle = entryCandle(),
        history: List<Candle> = normalHistory(),
    ): BacktestRiskSimulator.GateResult =
        sim.checkEntry(
            ticker = ticker,
            signal = StrategyAction.BUY,
            entryPrice = BigDecimal("100.00"),
            cash = cash,
            candle = candle,
            history = history,
        )

    // ===== Tests =====

    @Nested
    inner class Gate1GapGuard {
        @Test
        fun `gap exceeding maxVolatilityPercent blocks with DEGENERATE_GAP`() =
            runBlocking {
                val gapped = makeCandle(at(0, 10), open = "106.00", high = "106.50", low = "105.50", close = "106.00")
                val result = checkEntry(candle = gapped)
                assertFalse(result.allowed)
                assertEquals("DEGENERATE_GAP", result.reason)
            }

        @Test
        fun `gap within threshold does not block`() =
            runBlocking {
                // Гэп 1% < maxVolatilityPercent (5%) — gate 1 пропускает вход
                val smallGap = makeCandle(at(0, 10), open = "101.00", high = "101.50", low = "100.50", close = "101.00")
                val result = checkEntry(candle = smallGap)
                assertTrue(result.allowed)
                assertNull(result.reason)
            }
    }

    @Nested
    inner class Gate2DailyLossLimit {
        @Test
        fun `daily loss beyond limit blocks with DAILY_LIMIT`() =
            runBlocking {
                // pnl = (97.50 - 100.00) * 100 lots * lotSize 10 = -2500 RUB <= -(2% * 100000)
                simulator.recordClose(
                    "TEST",
                    PositionDirection.LONG,
                    bd("100.00"),
                    bd("97.50"),
                    100,
                    at(0, 10),
                    at(0, 11),
                    "STOP_LOSS",
                    BigDecimal.ZERO,
                    capital,
                )
                assertTrue(simulator.currentDailyPnl().compareTo(bd("-2500")) == 0)

                val result = checkEntry(candle = entryCandle(at(0, 12)))
                assertFalse(result.allowed)
                assertEquals("DAILY_LIMIT", result.reason)
            }
    }

    @Nested
    inner class Gate3DrawdownProtection {
        @Test
        fun `three consecutive losses trigger shadow mode and block with DRAWDOWN_PROTECTION`() =
            runBlocking {
                // Убытки в разные дни, чтобы дневной лимит не сработал раньше drawdown-защиты
                listOf(3L, 2L, 1L).forEach { offset ->
                    // pnl = (99.50 - 100.00) * 100 * 10 = -500 RUB в день
                    simulator.recordClose(
                        "TEST",
                        PositionDirection.LONG,
                        bd("100.00"),
                        bd("99.50"),
                        100,
                        at(-offset, 10),
                        at(-offset, 11),
                        "STOP_LOSS",
                        BigDecimal.ZERO,
                        capital,
                    )
                }
                assertEquals(3, simulator.maxConsecutiveLosses())

                val result = checkEntry()
                assertFalse(result.allowed)
                assertEquals("DRAWDOWN_PROTECTION", result.reason)
            }
    }

    @Nested
    inner class Gate4VolatilityGuard {
        @Test
        fun `large ATR blocks with VOLATILITY_GUARD`() =
            runBlocking {
                // Диапазон свечи 20 RUB при цене 100 → ATR% = 20% >> 5%
                val wild = historyOf(open = "100.00", high = "110.00", low = "90.00", close = "100.00")
                val result = checkEntry(history = wild)
                assertFalse(result.allowed)
                assertEquals("VOLATILITY_GUARD", result.reason)
            }
    }

    @Nested
    inner class Gate5DuplicatePosition {
        @Test
        fun `open position for ticker blocks re-entry with DUPLICATE_POSITION`() =
            runBlocking {
                simulator.recordOpen("TEST", PositionDirection.LONG, 5, bd("100.00"), 10, at(0, 10))
                assertEquals(1, simulator.openPositionCount())

                val result = checkEntry()
                assertFalse(result.allowed)
                assertEquals("DUPLICATE_POSITION", result.reason)
            }
    }

    @Nested
    inner class Gate6MaxPositions {
        @Test
        fun `entry beyond maxOpenPositions blocks with MAX_POSITIONS`() =
            runBlocking {
                val cfg = makeRiskConfig().apply { maxOpenPositions = 1 }
                val sim = initSimulator(cfg, instrumentsConfig)
                sim.recordOpen("TEST2", PositionDirection.LONG, 5, bd("100.00"), 10, at(0, 10))

                val result = checkEntry(sim = sim)
                assertFalse(result.allowed)
                assertEquals("MAX_POSITIONS", result.reason)
            }
    }

    @Nested
    inner class Gate7SectorExposure {
        @Test
        fun `sector at capacity blocks with SECTOR_EXPOSURE`() =
            runBlocking {
                val cfg = makeRiskConfig().apply { maxSectorExposure = 2 }
                val sim = initSimulator(cfg, instrumentsConfig)
                sim.recordOpen("TEST", PositionDirection.LONG, 5, bd("100.00"), 10, at(0, 10))
                sim.recordOpen("TEST2", PositionDirection.LONG, 5, bd("100.00"), 10, at(0, 10))

                // TEST3 в том же секторе TECH
                val result = checkEntry(sim = sim, ticker = "TEST3")
                assertFalse(result.allowed)
                assertEquals("SECTOR_EXPOSURE", result.reason)
            }
    }

    @Nested
    inner class Gate8KellySizing {
        @Test
        fun `tiny riskPerTradePercent caps size to zero with ZERO_RISK_SIZE`() =
            runBlocking {
                val cfg = makeRiskConfig().apply { riskPerTradePercent = 0.001 }
                val sim = initSimulator(cfg, instrumentsConfig)

                // riskAmount = 100000 * 0.001% = 1 RUB; lossPerLot = 2*ATR*lotSize = 20 RUB → 0 лотов
                val result = checkEntry(sim = sim)
                assertFalse(result.allowed)
                assertEquals("ZERO_RISK_SIZE", result.reason)
                assertEquals(0, result.kellySizeLots)
                assertTrue(result.kellySizeRub.compareTo(BigDecimal.ZERO) > 0)
            }

        @Test
        fun `kelly budget below one lot yields KELLY_BELOW_MIN_LOT`() =
            runBlocking {
                // base = 10% от 3000 = 300 RUB < notionalPerLot (100 * 10 = 1000)
                val result = checkEntry(cash = bd("3000"))
                assertFalse(result.allowed)
                assertEquals("KELLY_BELOW_MIN_LOT", result.reason)
                assertEquals(0, result.kellySizeLots)
            }
    }

    @Nested
    inner class Gate9GrossExposure {
        @Test
        fun `notional above maxGrossExposurePercent blocks with GROSS_EXPOSURE`() =
            runBlocking {
                val cfg = makeRiskConfig().apply { maxGrossExposurePercent = 1.0 }
                val sim = initSimulator(cfg, instrumentsConfig)

                // Кандидат: 5 лотов * 1000 RUB = 5000 RUB = 5% AUM > 1%
                val result = checkEntry(sim = sim)
                assertFalse(result.allowed)
                assertEquals("GROSS_EXPOSURE", result.reason)
            }
    }

    @Nested
    inner class Gate10NetEv {
        @Test
        fun `blocked NetEvGate blocks with NET_EV_TOO_LOW`() =
            runBlocking {
                val gate = mock<NetEvGate>()
                whenever(gate.check(org.mockito.kotlin.any(), anyOrNull(), anyOrNull()))
                    .thenReturn(NetEvGate.GateResult.Blocked(netEV = bd("-5"), expectedNet = null, executionCost = null))
                val sim = initSimulator(makeRiskConfig(), instrumentsConfig, gate)

                val result = checkEntry(sim = sim)
                assertFalse(result.allowed)
                assertEquals("NET_EV_TOO_LOW", result.reason)
                assertTrue(result.netEvResult is NetEvGate.GateResult.Blocked)
            }
    }

    @Nested
    inner class Gate11PortfolioConcentration {
        @Test
        fun `single position below minEffectivePositions blocks with PORTFOLIO_CONCENTRATION`() =
            runBlocking {
                val cfg = makeRiskConfig().apply { portfolioRiskEnabled = true }
                val sim = initSimulator(cfg, instrumentsConfig)

                // Одна позиция → effectivePositions = 1.0 < 1.5
                val result = checkEntry(sim = sim)
                assertFalse(result.allowed)
                assertEquals("PORTFOLIO_CONCENTRATION", result.reason)
            }
    }

    @Nested
    inner class AllGatesPass {
        @Test
        fun `normal conditions allow entry with correct kelly size`() =
            runBlocking {
                // ATR = 1.0 → base 10% * 100000 = 10000, volMult = 4% / (1% * sqrt(57)) ≈ 0.53
                // → size ≈ 5298 RUB → floor(5298 / 1000) = 5 лотов; риск-кап: 1000 / 20 = 50 лотов
                val result = checkEntry()
                assertTrue(result.allowed)
                assertNull(result.reason)
                assertEquals(5, result.kellySizeLots)
                assertTrue(result.kellySizeRub.compareTo(BigDecimal.ZERO) > 0)
                assertTrue(result.netEvResult is NetEvGate.GateResult.Pass)
            }
    }

    @Nested
    inner class StateManagement {
        @Test
        fun `daily pnl resets on new calendar day`() =
            runBlocking {
                simulator.recordClose(
                    "TEST",
                    PositionDirection.LONG,
                    bd("100.00"),
                    bd("98.50"),
                    100,
                    at(0, 10),
                    at(0, 11),
                    "STOP_LOSS",
                    BigDecimal.ZERO,
                    capital,
                )
                assertTrue(simulator.currentDailyPnl().compareTo(BigDecimal.ZERO) < 0)

                // Вход на следующий день: дневной P&L обнуляется до проверок
                val result = checkEntry(candle = entryCandle(at(1, 10)))
                assertTrue(simulator.currentDailyPnl().compareTo(BigDecimal.ZERO) == 0)
                assertTrue(result.reason != "DAILY_LIMIT")
            }

        @Test
        fun `recordClose clears open position and updates peak equity`() {
            simulator.recordOpen("TEST", PositionDirection.LONG, 5, bd("100.00"), 10, at(0, 10))
            assertEquals(1, simulator.openPositionCount())

            // Прибыльная сделка поднимает пик equity: 105000 + 500 = 105500
            simulator.recordClose(
                "TEST",
                PositionDirection.LONG,
                bd("100.00"),
                bd("100.50"),
                100,
                at(0, 10),
                at(0, 11),
                "TAKE_PROFIT",
                BigDecimal.ZERO,
                bd("105000"),
            )
            assertEquals(0, simulator.openPositionCount())
            assertEquals(1, simulator.closedTradeCount())
            assertTrue(simulator.currentDailyPnl().compareTo(bd("500")) == 0)

            // Убыток после пика даёт положительную просадку — доказательство обновления peakEquity
            simulator.recordClose(
                "TEST2",
                PositionDirection.LONG,
                bd("100.00"),
                bd("99.90"),
                100,
                at(0, 11),
                at(0, 12),
                "STOP_LOSS",
                BigDecimal.ZERO,
                bd("104000"),
            )
            assertEquals(1, simulator.maxConsecutiveLosses())
            assertTrue(simulator.currentDrawdownPercent() > 0.0)
        }
    }
}
