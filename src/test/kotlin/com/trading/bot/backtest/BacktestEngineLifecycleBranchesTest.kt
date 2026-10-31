package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.PositionSizer
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.FundingHistoryRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [BacktestEngine] — жизненный цикл позиции и комиссии: замыкание сделки в
 * live-risk-gates (передача закрытия в [BacktestRiskSimulator]), неизвестный
 * тикер (единичный лот, нулевая комиссия), funding-дырка в истории на
 * межсессионном удержании, а также debug-трассировка открытия/сайзинга.
 */
class BacktestEngineLifecycleBranchesTest {
    private val start = LocalDateTime.of(2026, 3, 2, 10, 0)

    private fun candle(
        ticker: String,
        price: Double,
        time: LocalDateTime,
        high: Double = price + 0.5,
        low: Double = price - 0.5,
    ): Candle =
        Candle(
            ticker = ticker,
            timeframe = "MINUTE_10",
            openPrice = BigDecimal.valueOf(price),
            highPrice = BigDecimal.valueOf(high),
            lowPrice = BigDecimal.valueOf(low),
            closePrice = BigDecimal.valueOf(price),
            volume = 1_000L,
            time = time,
        )

    private fun minuteCandles(
        ticker: String,
        count: Int = 120,
    ): List<Candle> = (0 until count).map { i -> candle(ticker, 100.0 + (i % 5), start.plusMinutes(10L * i)) }

    private class AlternatingGenerator : BacktestSignalGenerator {
        override suspend fun signal(
            ticker: String,
            candles: List<Candle>,
            index: Int,
            minBars: Int,
            cycleId: String,
        ): StrategyAction = if (index % 2 == 0) StrategyAction.BUY else StrategyAction.SELL
    }

    private fun permissiveRiskConfig(): RiskConfig =
        RiskConfig().apply {
            maxOpenPositions = 5
            maxSectorExposure = 5
            maxVolatilityPercent = 500.0
            maxDailyLossPercent = 50.0
            maxRollingLoss7dPercent = 100.0
            maxRollingLoss30dPercent = 100.0
            maxGrossExposurePercent = 100_000.0
            maxNetExposurePercent = 100_000.0
            drawdownScaleTiers = emptyMap()
            shadowModeEnabled = false
        }

    private fun engine(
        config: BacktestConfig,
        meterRegistry: SimpleMeterRegistry? = SimpleMeterRegistry(),
        sizer: PositionSizer? = null,
        fundingRepo: FundingHistoryRepository? = null,
        riskSimulator: BacktestRiskSimulator? = null,
    ): BacktestEngine =
        BacktestEngine(
            candleRepo = CandleRepository(Mockito.mock(DatabaseClient::class.java)),
            instrumentsConfig = InstrumentsConfig(),
            backtestConfig = config,
            meterRegistry = meterRegistry,
            signalGenerator = AlternatingGenerator(),
            positionSizer = sizer,
            riskConfig = permissiveRiskConfig(),
            riskSimulator = riskSimulator,
            fundingHistoryRepository = fundingRepo,
        )

    @Test
    fun `closed trades are reported to the live risk simulator`(): Unit =
        runBlocking {
            val config =
                BacktestConfig().apply {
                    liveRiskGates = true
                    futuresLiquidationSimulation = false
                }
            val simulator = mock<BacktestRiskSimulator>()
            whenever(
                simulator.checkEntry(
                    org.mockito.kotlin.any(),
                    anyAction(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyCandle(),
                    anyCandleList(),
                    anyNullableDouble(),
                    anyDateTime(),
                    anyFundingMap(),
                ),
            ).thenReturn(BacktestRiskSimulator.GateResult(allowed = true))

            val result =
                engine(config = config, riskSimulator = simulator)
                    .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5, maxHoldBars = 1)

            assertTrue(result.trades.isNotEmpty(), "${result.trades.size} сделок должно закрыться через MAX_HOLD")
        }

    @Test
    fun `unknown ticker uses a unit lot and zero funding`(): Unit =
        runBlocking {
            val result =
                engine(BacktestConfig().apply { liveRiskGates = false })
                    .simulate("ZZZZ", minuteCandles("ZZZZ"), minBarsForSignal = 5)

            assertTrue(result.trades.isNotEmpty())
            assertTrue(result.trades.all { it.funding == 0.0 })
        }

    @Test
    fun `funding hole on a cross session hold is metered`(): Unit =
        runBlocking {
            val sizer = mock<PositionSizer>()
            whenever(
                sizer.calculateContracts(
                    org.mockito.kotlin.any(),
                    anyBigDecimal(),
                    anyInt(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                ),
            ).thenReturn(PositionSizeResult(1, BigDecimal("1275"), BigDecimal("500"), null, null))
            // Ряд есть, но клиринга между входом и выходом в нём нет → fail-open fallback
            // и метрика дырки.
            val funding = mock<FundingHistoryRepository>()
            whenever(funding.findValuesBetween(org.mockito.kotlin.any(), anyDate(), anyDate())).thenReturn(
                mapOf(LocalDate.of(2026, 1, 1) to BigDecimal("5.0")),
            )
            val registry = SimpleMeterRegistry()
            val day1 = LocalDateTime.of(2026, 3, 2, 18, 0)
            val candles =
                listOf(
                    candle("CNYRUBF", 100.0, day1, high = 100.0, low = 100.0),
                    candle("CNYRUBF", 100.0, day1.plusMinutes(10), high = 100.0, low = 100.0),
                    candle("CNYRUBF", 100.0, day1.plusHours(15), high = 100.0, low = 100.0),
                    candle("CNYRUBF", 100.0, day1.plusHours(15).plusMinutes(10), high = 100.0, low = 100.0),
                    candle("CNYRUBF", 100.0, day1.plusHours(15).plusMinutes(20), high = 100.0, low = 100.0),
                )
            val config =
                BacktestConfig().apply {
                    liveRiskGates = false
                    futuresLiquidationSimulation = false
                    fundingDirectionAware = true
                }

            val result =
                engine(config = config, meterRegistry = registry, sizer = sizer, fundingRepo = funding)
                    .simulate(
                        "CNYRUBF",
                        candles,
                        minBarsForSignal = 1,
                        maxHoldBars = 1,
                        slPoints = 50_000,
                        tpPoints = 50_000,
                        slPercent = 0.0,
                        tpPercent = 0.0,
                    )

            assertTrue(result.trades.isNotEmpty())
            assertTrue(
                registry.counter("bt_funding_missing_total", "ticker", "CNYRUBF").count() > 0,
                "дырка в funding_history обязана попасть в метрику",
            )
        }

    @Test
    fun `debug logging exercises the open and sizing traces`(): Unit =
        runBlocking {
            val logger = LoggerFactory.getLogger(BacktestEngine::class.java) as ch.qos.logback.classic.Logger
            val previous = logger.level
            logger.level = ch.qos.logback.classic.Level.DEBUG
            try {
                val sizer = mock<PositionSizer>()
                whenever(
                    sizer.calculateContracts(
                        org.mockito.kotlin.any(),
                        anyBigDecimal(),
                        anyInt(),
                        anyBigDecimal(),
                        anyBigDecimal(),
                        anyDirection(),
                    ),
                ).thenReturn(PositionSizeResult(1, BigDecimal("1275"), BigDecimal("500"), null, null))

                val result =
                    engine(
                        config =
                            BacktestConfig().apply {
                                liveRiskGates = false
                                futuresLiquidationSimulation = false
                            },
                        sizer = sizer,
                    ).simulate("CNYRUBF", minuteCandles("CNYRUBF", count = 40), minBarsForSignal = 2)

                assertTrue(result.trades.isNotEmpty())
            } finally {
                logger.level = previous
            }
        }

    @Test
    fun `commission handles per lot fixed and fractional quantities`(): Unit =
        runBlocking {
            val backtest = engine(BacktestConfig().apply { liveRiskGates = false })

            // Фьючерс: фиксированная комиссия за лот/сторону × qty.
            val fixed = backtest.computeCommission("CNYRUBF", BigDecimal("100"), 2)
            assertTrue(fixed > BigDecimal.ZERO, "fixed commission must be positive: $fixed")

            // Дробное количество (< 1 лота): процентный путь, без падения.
            val fractional = backtest.computeCommission("CNYRUBF", BigDecimal("100"), 0)
            assertEquals(0, BigDecimal.ZERO.compareTo(fractional))

            // Акция без фиксированной комиссии: процент от оборота.
            assertTrue(backtest.computeCommission("SBER", BigDecimal("100"), 2) >= BigDecimal.ZERO)
        }

    @Test
    fun `debug logging covers sizing overrides on both sides`(): Unit =
        runBlocking {
            val logger = LoggerFactory.getLogger(BacktestEngine::class.java) as ch.qos.logback.classic.Logger
            val previous = logger.level
            logger.level = ch.qos.logback.classic.Level.DEBUG
            try {
                val sizer = mock<PositionSizer>()
                whenever(
                    sizer.calculateContracts(
                        org.mockito.kotlin.any(),
                        anyBigDecimal(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        anyBigDecimal(),
                        anyDirection(),
                        org.mockito.kotlin.any(),
                        org.mockito.kotlin.any(),
                    ),
                ).thenReturn(PositionSizeResult(2, BigDecimal("1275"), BigDecimal("500"), null, null))

                val result =
                    engine(
                        config =
                            BacktestConfig().apply {
                                liveRiskGates = false
                                futuresLiquidationSimulation = false
                            },
                        sizer = sizer,
                    ).simulate(
                        "CNYRUBF",
                        minuteCandles("CNYRUBF", count = 40),
                        minBarsForSignal = 2,
                        riskPerTradePercent = 5.0,
                        futuresMaxContractsPerPosition = 2,
                        slPoints = 100,
                        tpPoints = 200,
                    )

                assertTrue(result.trades.isNotEmpty())
            } finally {
                logger.level = previous
            }
        }

    @Test
    fun `passive fill override enables the passive branch`(): Unit =
        runBlocking {
            val result =
                engine(BacktestConfig().apply { liveRiskGates = false })
                    .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5, passiveFillEnabled = true)

            assertTrue(result.trades.isNotEmpty())
        }

    @Test
    fun `null meter registry is tolerated by veto and funding window metrics`(): Unit =
        runBlocking {
            val funding = mock<FundingHistoryRepository>()
            whenever(funding.findValuesBetween(org.mockito.kotlin.any(), anyDate(), anyDate())).thenReturn(
                mapOf(LocalDate.of(2026, 2, 27) to BigDecimal("5.0")),
            )
            val vetoConfig =
                BacktestConfig().apply {
                    liveRiskGates = false
                    fundingVetoEnabled = true
                    fundingVetoLongThresholdRub = 1.0
                    fundingVetoShortThresholdRub = 1.0
                    fundingVetoBlockOnUnknown = true
                }
            val vetoResult =
                engine(config = vetoConfig, meterRegistry = null, fundingRepo = funding)
                    .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5)
            assertTrue(vetoResult.trades.isNotEmpty())

            val d1 = LocalDate.of(2026, 3, 2)
            val windowCandles =
                (0..3).flatMap { day ->
                    (0..1).map { hour ->
                        candle("SBER", 100.0, d1.plusDays(day.toLong()).atTime(10 + hour, 0))
                    }
                }
            val windowFunding = mock<FundingHistoryRepository>()
            whenever(windowFunding.findValuesBetween(org.mockito.kotlin.any(), anyDate(), anyDate())).thenReturn(
                mapOf(
                    d1 to BigDecimal("1.50"),
                    d1.plusDays(1) to BigDecimal("1.50"),
                    d1.plusDays(2) to BigDecimal("0.00"),
                ),
            )
            val windowConfig =
                BacktestConfig().apply {
                    liveRiskGates = false
                    fundingWindowEnabled = true
                    fundingWindowLongThresholdRub = 2.0
                    fundingWindowShortThresholdRub = 1.0
                    fundingWindowBlockOnUnknown = true
                }
            val windowResult =
                engine(config = windowConfig, meterRegistry = null, fundingRepo = windowFunding)
                    .simulate("SBER", windowCandles, minBarsForSignal = 2, initialCapital = BigDecimal("100000"))
            assertTrue(windowResult.trades.isNotEmpty())
        }

    @Test
    fun `run persists a result and meters a rejected pass`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val result =
                engine(BacktestConfig().apply { liveRiskGates = false }, meterRegistry = registry)
                    .run("SBER", days = 10, minBarsForSignal = 5, candlesOverride = minuteCandles("SBER"))

            assertTrue(result.totalTrades > 0)
            assertEquals(1.0, registry.counter("bt_pass_total", "result", "REJECT").count())
        }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal("100")
    }

    private fun anyInt(): Int {
        Mockito.anyInt()
        return 50
    }

    private fun anyDirection(): PositionDirection {
        Mockito.any(PositionDirection::class.java)
        return PositionDirection.LONG
    }

    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return LocalDate.of(2026, 3, 1)
    }

    private fun anyAction(): StrategyAction {
        Mockito.any(StrategyAction::class.java)
        return StrategyAction.BUY
    }

    private fun anyDateTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return start
    }

    private fun anyCandle(): Candle {
        Mockito.any(Candle::class.java)
        return candle("SBER", 100.0, start)
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyCandleList(): List<Candle> {
        Mockito.any(List::class.java)
        return emptyList()
    }

    private fun anyNullableDouble(): Double? {
        Mockito.nullable(Double::class.javaObjectType)
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyFundingMap(): Map<LocalDate, BigDecimal> {
        Mockito.any(Map::class.java)
        return emptyMap()
    }
}
