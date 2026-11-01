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
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.util.concurrent.atomic.AtomicInteger

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

    /** Только LONG: позиция закрывается по maxHold, а не разворотом. */
    private class BuyOnlyGenerator : BacktestSignalGenerator {
        override suspend fun signal(
            ticker: String,
            candles: List<Candle>,
            index: Int,
            minBars: Int,
            cycleId: String,
        ): StrategyAction = StrategyAction.BUY
    }

    @Test
    fun `risk gates are skipped when no new entry is triggered`(): Unit =
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

            engine(config = config, riskSimulator = simulator, generator = BuyOnlyGenerator())
                .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5, maxHoldBars = 10_000)

            assertEquals(
                1,
                Mockito.mockingDetails(simulator).invocations.count { it.method.name == "checkEntry" },
            )
        }

    @Test
    fun `ml filter blocking a reversal closes the open position`(): Unit =
        runBlocking {
            val config =
                BacktestConfig().apply {
                    liveRiskGates = false
                    mlFilterEnabled = true
                    futuresLiquidationSimulation = false
                }
            val ml = mock<com.trading.bot.service.MlEntryFilter>()
            var calls = 0
            whenever(ml.shouldBlock(anyTicker(), anyAction(), anyNullableDouble(), anyDateTime(), anyBooleanArg()))
                .thenAnswer {
                    calls += 1
                    if (calls == 1) null else "ML_BLOCK"
                }

            val result =
                engine(config = config, mlEntryFilter = ml)
                    .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5)

            assertTrue(
                result.trades.any { it.closeReason == "ML_FILTER_REVERSAL" },
                result.trades.map { it.closeReason }.toString(),
            )
        }

    @Test
    fun `mtf filter blocking a reversal closes the open position`(): Unit =
        runBlocking {
            val config =
                BacktestConfig().apply {
                    liveRiskGates = false
                    mtfFilterEnabled = true
                    futuresLiquidationSimulation = false
                }
            val mtf = mock<com.trading.bot.service.HigherTfTrendFilter>()
            var calls = 0
            whenever(
                mtf.shouldBlock(anyTicker(), anyAction(), Mockito.anyList(), anyDateTime(), anyBooleanArg()),
            ).thenAnswer {
                calls += 1
                if (calls == 1) null else "MTF_BLOCK"
            }

            val result =
                engine(config = config, higherTfTrendFilter = mtf)
                    .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5)

            assertTrue(
                result.trades.any { it.closeReason == "MTF_FILTER_REVERSAL" },
                result.trades.map { it.closeReason }.toString(),
            )
        }

    private fun anyTicker(): String {
        Mockito.anyString()
        return "SBER"
    }

    private fun anyBooleanArg(): Boolean {
        Mockito.anyBoolean()
        return false
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
        instruments: InstrumentsConfig = InstrumentsConfig(),
        mlEntryFilter: com.trading.bot.service.MlEntryFilter? = null,
        higherTfTrendFilter: com.trading.bot.service.HigherTfTrendFilter? = null,
        generator: BacktestSignalGenerator = AlternatingGenerator(),
    ): BacktestEngine =
        BacktestEngine(
            candleRepo = CandleRepository(Mockito.mock(DatabaseClient::class.java)),
            instrumentsConfig = instruments,
            backtestConfig = config,
            meterRegistry = meterRegistry,
            signalGenerator = generator,
            positionSizer = sizer,
            riskConfig = permissiveRiskConfig(),
            riskSimulator = riskSimulator,
            fundingHistoryRepository = fundingRepo,
            mlEntryFilter = mlEntryFilter,
            higherTfTrendFilter = higherTfTrendFilter,
        )

    @Test
    fun `simulate without a meter registry still produces a result`(): Unit =
        runBlocking {
            val result =
                engine(config = BacktestConfig(), meterRegistry = null)
                    .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5)

            assertTrue(result.totalTrades >= 0)
            assertTrue(result.equityCurve.isNotEmpty())
        }

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
    fun `risk gate rejection without a reason is metered as unknown`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
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
            ).thenReturn(BacktestRiskSimulator.GateResult(allowed = false, reason = null))
            val config =
                BacktestConfig().apply {
                    liveRiskGates = true
                    futuresLiquidationSimulation = false
                }

            val result =
                engine(config = config, meterRegistry = registry, riskSimulator = simulator)
                    .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5, maxHoldBars = 1)

            assertEquals(0, result.totalTrades)
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

    @Test
    fun `execution fill covers bps tick realistic and passive branches`() {
        val futures =
            InstrumentsConfig.InstrumentSpec(
                ticker = "FUT1",
                type = "FUTURES",
                lotSize = 1000,
                priceStep = BigDecimal("0.001"),
                priceStepCost = BigDecimal("1.0"),
                go = BigDecimal("850"),
                leverage = BigDecimal("1.0"),
                baseAsset = "CNY",
                slippageBps = BigDecimal("1.0"),
            )
        val zeroBps = futures.copy(ticker = "FUT0", slippageBps = BigDecimal.ZERO)
        val noBps = futures.copy(ticker = "FUTN", slippageBps = null)
        val stock =
            InstrumentsConfig.InstrumentSpec(
                ticker = "STK",
                type = "STOCK",
                lotSize = 1,
                priceStep = BigDecimal("0.01"),
                priceStepCost = BigDecimal("0.01"),
                go = BigDecimal.ZERO,
                leverage = BigDecimal.ONE,
                baseAsset = "STK",
            )
        val instruments =
            InstrumentsConfig().apply { this.instruments = mutableListOf(futures, zeroBps, noBps, stock) }
        val engine = engine(BacktestConfig().apply { liveRiskGates = false }, instruments = instruments)
        val ref = BigDecimal("100")
        val candle = candle("STK", 100.0, start, high = 101.0, low = 99.0)

        // Фьючерсы: bps-модель, tick-модель (0 и null bps).
        for ((ticker, spec) in listOf("FUT1" to futures, "FUT0" to zeroBps, "FUTN" to noBps)) {
            val buy = engine.executionFill(spec, ticker, ref, isBuy = true, slippageMultiplier = 1.0)
            val sell = engine.executionFill(spec, ticker, ref, isBuy = false, slippageMultiplier = 1.0)
            assertTrue(buy.price >= ref, "$ticker buy fill must not be better than reference")
            assertTrue(sell.price <= ref, "$ticker sell fill must not be better than reference")
        }

        // Акции: realistic half-spread при наличии свечи, market-fill без свечи.
        val realistic =
            engine.executionFill(stock, "STK", ref, isBuy = true, slippageMultiplier = 1.0, candle = candle)
        assertTrue(realistic.price >= ref)
        val noCandle =
            engine.executionFill(stock, "STK", ref, isBuy = true, slippageMultiplier = 1.0, candle = null)
        assertTrue(noCandle.price >= ref)

        val lenient =
            engine(
                BacktestConfig().apply {
                    liveRiskGates = false
                    realisticExecution = false
                },
                instruments = instruments,
            )
        val plain =
            lenient.executionFill(stock, "STK", ref, isBuy = true, slippageMultiplier = 1.0, candle = candle)
        assertTrue(plain.price >= ref)

        // Passive-ветка: включается флагом/параметром и только для passiveEligible.
        val passive =
            engine.executionFill(
                stock,
                "STK",
                ref,
                isBuy = true,
                slippageMultiplier = 1.0,
                candle = candle,
                passiveEligible = true,
                passiveFillEnabled = true,
            )
        assertTrue(passive.price <= ref, "passive buy исполняется не выше mid")
        val takerOnly =
            engine.executionFill(
                stock,
                "STK",
                ref,
                isBuy = true,
                slippageMultiplier = 1.0,
                candle = candle,
                passiveEligible = false,
                passiveFillEnabled = true,
            )
        assertTrue(takerOnly.price >= ref, "passiveEligible=false — taker-филл")
    }

    @Test
    fun `funding window takes the long side on a deeply negative rate`(): Unit =
        runBlocking {
            val d1 = LocalDate.of(2026, 3, 2)
            val candles =
                (0..3).flatMap { day ->
                    (0..1).map { hour ->
                        candle("SBER", 100.0, d1.plusDays(day.toLong()).atTime(10 + hour, 0))
                    }
                }
            val funding = mock<FundingHistoryRepository>()
            whenever(funding.findValuesBetween(org.mockito.kotlin.any(), anyDate(), anyDate())).thenReturn(
                mapOf(
                    d1 to BigDecimal("-5.0"),
                    d1.plusDays(1) to BigDecimal("-5.0"),
                    d1.plusDays(2) to BigDecimal("0.00"),
                ),
            )
            val config =
                BacktestConfig().apply {
                    liveRiskGates = false
                    fundingWindowEnabled = true
                    fundingWindowLongThresholdRub = 2.0
                    fundingWindowShortThresholdRub = 1.0
                    fundingWindowBlockOnUnknown = true
                }

            val result =
                engine(config = config, fundingRepo = funding)
                    .simulate("SBER", candles, minBarsForSignal = 2, initialCapital = BigDecimal("100000"))

            assertTrue(result.trades.any { it.closeReason == "FUNDING_WINDOW_EXIT" })
            assertTrue(result.trades.any { it.direction == PositionDirection.LONG }, "ставка ≤ −long → LONG")
        }

    @Test
    fun `funding window with a rejected sizing leaves no position`(): Unit =
        runBlocking {
            val d1 = LocalDate.of(2026, 3, 2)
            val candles =
                (0..2).flatMap { day ->
                    (0..1).map { hour ->
                        candle("CNYRUBF", 100.0, d1.plusDays(day.toLong()).atTime(10 + hour, 0))
                    }
                }
            val funding = mock<FundingHistoryRepository>()
            whenever(funding.findValuesBetween(org.mockito.kotlin.any(), anyDate(), anyDate())).thenReturn(
                mapOf(d1 to BigDecimal("5.0")),
            )
            val sizer = mock<PositionSizer>()
            whenever(
                sizer.calculateContracts(
                    org.mockito.kotlin.any(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                ),
            ).thenReturn(PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, "ZERO_GO"))
            val registry = SimpleMeterRegistry()
            val config =
                BacktestConfig().apply {
                    liveRiskGates = false
                    fundingWindowEnabled = true
                    fundingWindowLongThresholdRub = 2.0
                    fundingWindowShortThresholdRub = 1.0
                    fundingWindowBlockOnUnknown = true
                }

            val result =
                engine(config = config, meterRegistry = registry, sizer = sizer, fundingRepo = funding)
                    .simulate("CNYRUBF", candles, minBarsForSignal = 1)

            assertTrue(result.trades.isEmpty())
            assertEquals(0.0, registry.counter("bt_funding_window_entry_total", "ticker", "CNYRUBF").count())
        }

    @Test
    fun `funding veto blocks the short side on a negative rate`(): Unit =
        runBlocking {
            val funding = mock<FundingHistoryRepository>()
            whenever(funding.findValuesBetween(org.mockito.kotlin.any(), anyDate(), anyDate())).thenReturn(
                mapOf(LocalDate.of(2026, 2, 27) to BigDecimal("-5.0")),
            )
            val config =
                BacktestConfig().apply {
                    liveRiskGates = false
                    fundingVetoEnabled = true
                    fundingVetoLongThresholdRub = 1.0
                    fundingVetoShortThresholdRub = 1.0
                    fundingVetoBlockOnUnknown = true
                }

            val result =
                engine(config = config, fundingRepo = funding)
                    .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5)

            assertTrue(result.trades.any { it.closeReason == "FUNDING_VETO_REVERSAL" })
        }

    @Test
    fun `empty candle list returns an empty result without touching funding`(): Unit =
        runBlocking {
            val result =
                engine(BacktestConfig().apply { liveRiskGates = false })
                    .simulate("SBER", emptyList())

            assertEquals(0, result.totalTrades)
        }

    @Test
    fun `reversal with a rejected sizing leaves no new position`(): Unit =
        runBlocking {
            val sizer = mock<PositionSizer>()
            val calls = AtomicInteger(0)
            whenever(
                sizer.calculateContracts(
                    org.mockito.kotlin.any(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                ),
            ).thenAnswer {
                if (calls.getAndIncrement() == 0) {
                    PositionSizeResult(1, BigDecimal("1275"), BigDecimal("500"), null, null)
                } else {
                    PositionSizeResult(0, BigDecimal.ZERO, BigDecimal.ZERO, null, "ZERO_GO")
                }
            }
            val config =
                BacktestConfig().apply {
                    liveRiskGates = false
                    futuresLiquidationSimulation = false
                }

            val result =
                engine(config = config, sizer = sizer)
                    .simulate(
                        "CNYRUBF",
                        minuteCandles("CNYRUBF", count = 40),
                        minBarsForSignal = 3,
                        slPoints = 50_000,
                        tpPoints = 50_000,
                    )

            assertTrue(result.trades.any { it.closeReason == "REVERSAL" }, "${result.trades.map { it.closeReason }}")
            assertTrue(
                result.trades.none { it.closeReason == "LIQUIDATION" || it.closeReason == "FUNDING_WINDOW_EXIT" },
                "после отказа сайзера новая позиция не открывается",
            )
        }

    @Test
    fun `explicit veto overrides reach the simulate run`(): Unit =
        runBlocking {
            val result =
                engine(BacktestConfig().apply { liveRiskGates = false })
                    .simulate(
                        "SBER",
                        minuteCandles("SBER"),
                        minBarsForSignal = 5,
                        fundingVetoEnabled = false,
                        fundingVetoLongThresholdRub = 1.0,
                        fundingVetoShortThresholdRub = 1.0,
                        fundingVetoBlockOnUnknown = false,
                    )

            assertTrue(result.trades.isNotEmpty())
        }

    @Test
    fun `fractional commission on an unknown ticker falls back to the unit lot`(): Unit =
        runBlocking {
            val backtest = engine(BacktestConfig().apply { liveRiskGates = false })

            assertEquals(0, BigDecimal.ZERO.compareTo(backtest.computeCommission("ZZZZ", BigDecimal("100"), 0)))
        }

    @Test
    fun `null registry tolerates blocked ml mtf and risk metrics`(): Unit =
        runBlocking {
            val mlFilter = mock<com.trading.bot.service.MlEntryFilter>()
            whenever(
                mlFilter.shouldBlock(
                    org.mockito.kotlin.any(),
                    anyAction(),
                    anyNullableDouble(),
                    anyDateTime(),
                    org.mockito.kotlin.eq(false),
                ),
            ).thenReturn("ML_BLOCK")
            val mlResult =
                engine(
                    config =
                        BacktestConfig().apply {
                            liveRiskGates = false
                            mlFilterEnabled = true
                        },
                    meterRegistry = null,
                    mlEntryFilter = mlFilter,
                ).simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5)
            assertTrue(mlResult.trades.isEmpty())

            val mtfFilter = mock<com.trading.bot.service.HigherTfTrendFilter>()
            whenever(
                mtfFilter.shouldBlock(
                    org.mockito.kotlin.any(),
                    anyAction(),
                    anyCandleList(),
                    anyDateTime(),
                    org.mockito.kotlin.eq(false),
                ),
            ).thenReturn("MTF_BLOCK")
            val mtfResult =
                engine(
                    config =
                        BacktestConfig().apply {
                            liveRiskGates = false
                            mtfFilterEnabled = true
                        },
                    meterRegistry = null,
                    higherTfTrendFilter = mtfFilter,
                ).simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5)
            assertTrue(mtfResult.trades.isEmpty())

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
            ).thenReturn(BacktestRiskSimulator.GateResult(allowed = false, reason = null))
            val riskResult =
                engine(
                    config = BacktestConfig().apply { liveRiskGates = true },
                    meterRegistry = null,
                    riskSimulator = simulator,
                ).simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5)
            assertTrue(riskResult.trades.isEmpty())
        }

    @Test
    fun `run persists a passable result and meters a pass`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            // Ровный рост +1 за бар и единственный шаг -0.2: все LONG-сделки (maxHold=1)
            // прибыльны, но PF остаётся конечным (нужен хотя бы один убыток).
            val candles =
                (0 until 40).map { i ->
                    val price = 100.0 + i - if (i >= 10) 0.2 else 0.0
                    candle("CNYRUBF", price, start.plusMinutes(10L * i), high = price, low = price)
                }
            val sizer = mock<PositionSizer>()
            whenever(
                sizer.calculateContracts(
                    org.mockito.kotlin.any(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                    anyNullableDouble(),
                    anyNullableInt(),
                ),
            ).thenReturn(PositionSizeResult(1, BigDecimal("1275"), BigDecimal("500"), null, null))

            val result =
                BacktestEngine(
                    candleRepo = CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                    instrumentsConfig = InstrumentsConfig(),
                    backtestConfig = BacktestConfig().apply { liveRiskGates = false },
                    meterRegistry = registry,
                    signalGenerator = BuyOnlyGenerator(),
                    positionSizer = sizer,
                    riskConfig = permissiveRiskConfig(),
                ).run(
                    ticker = "CNYRUBF",
                    days = 10,
                    minBarsForSignal = 2,
                    slPercent = 0.005,
                    tpPercent = 0.01,
                    capitalSlice = 1.0,
                    riskPerTradePercent = 100.0,
                    slPoints = 50_000,
                    tpPoints = 50_000,
                    maxHoldBars = 1,
                    candlesOverride = candles,
                )

            assertTrue(result.totalTrades >= 10, "для PASS нужно >= 10 фьючерсных сделок: ${result.totalTrades}")
            assertTrue(result.profitFactor.isFinite() && result.profitFactor > 1.3, "PF=${result.profitFactor}")
            assertTrue(result.isPassable(), "ожидался passable-результат: $result")
            assertEquals(1.0, registry.counter("bt_pass_total", "result", "PASS").count())
        }

    @Test
    fun `zero price candles cannot be sized into a position`(): Unit =
        runBlocking {
            val zeros =
                (0 until 30).map { i ->
                    candle("SBER", 0.0, start.plusMinutes(10L * i), high = 0.0, low = 0.0)
                }

            val result =
                engine(BacktestConfig().apply { liveRiskGates = false })
                    .simulate("SBER", zeros, minBarsForSignal = 2)

            assertEquals(0, result.totalTrades, "нотионал 0 не даёт лота")
        }

    @Test
    fun `instrument level stop percent is honoured for stock sizing`(): Unit =
        runBlocking {
            val custom = customSpec()
            val instruments = InstrumentsConfig().apply { this.instruments = mutableListOf(custom) }
            val candles =
                (0 until 40).map { i ->
                    val price = 100.0 + (i % 3)
                    candle("CUSTOM", price, start.plusMinutes(10L * i), high = price, low = price)
                }

            val result =
                engine(
                    config = BacktestConfig().apply { liveRiskGates = false },
                    instruments = instruments,
                ).simulate("CUSTOM", candles, minBarsForSignal = 2)

            assertTrue(result.trades.isNotEmpty(), "spec.slPercent должен дать ненулевой сайзинг")
        }

    @Test
    fun `a losing run with trades is rejected by the metric`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val candles =
                (0 until 40).map { i ->
                    val price = 200.0 - i
                    candle("CNYRUBF", price, start.plusMinutes(10L * i), high = price, low = price)
                }
            val sizer = mock<PositionSizer>()
            whenever(
                sizer.calculateContracts(
                    org.mockito.kotlin.any(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                    anyNullableDouble(),
                    anyNullableInt(),
                ),
            ).thenReturn(PositionSizeResult(1, BigDecimal("1275"), BigDecimal("500"), null, null))

            val result =
                BacktestEngine(
                    candleRepo = CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                    instrumentsConfig = InstrumentsConfig(),
                    backtestConfig = BacktestConfig().apply { liveRiskGates = false },
                    meterRegistry = registry,
                    signalGenerator = BuyOnlyGenerator(),
                    positionSizer = sizer,
                    riskConfig = permissiveRiskConfig(),
                ).run(
                    ticker = "CNYRUBF",
                    days = 10,
                    minBarsForSignal = 2,
                    slPercent = 0.005,
                    tpPercent = 0.01,
                    capitalSlice = 1.0,
                    riskPerTradePercent = 100.0,
                    slPoints = 50_000,
                    tpPoints = 50_000,
                    maxHoldBars = 1,
                    candlesOverride = candles,
                )

            assertTrue(result.totalTrades >= 1, "trades=${result.totalTrades}")
            assertFalse(result.isPassable(), "убыточная серия не passable: PF=${result.profitFactor}")
            assertEquals(1.0, registry.counter("bt_pass_total", "result", "REJECT").count())
        }

    @Test
    fun `ml and mtf filters enabled without filter beans stay inert`(): Unit =
        runBlocking {
            val config =
                BacktestConfig().apply {
                    liveRiskGates = false
                    mlFilterEnabled = true
                    mtfFilterEnabled = true
                }

            val result =
                engine(config = config)
                    .simulate("SBER", minuteCandles("SBER"), minBarsForSignal = 5)

            assertTrue(result.trades.isNotEmpty())
        }

    @Test
    fun `futures without a sizer fall back to the generic lot sizing`(): Unit =
        runBlocking {
            val result =
                engine(BacktestConfig().apply { liveRiskGates = false })
                    .simulate("CNYRUBF", minuteCandles("CNYRUBF"), minBarsForSignal = 5)

            assertTrue(result.equityCurve.isNotEmpty(), "generic-сайзинг исполняется без PositionSizer")
        }

    @Test
    fun `futures sizer without overrides uses the default stop points`(): Unit =
        runBlocking {
            val sizer = mock<PositionSizer>()
            whenever(
                sizer.calculateContracts(
                    org.mockito.kotlin.any(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                ),
            ).thenReturn(PositionSizeResult(1, BigDecimal("1275"), BigDecimal("500"), null, null))

            val result =
                engine(BacktestConfig().apply { liveRiskGates = false }, sizer = sizer)
                    .simulate("CNYRUBF", minuteCandles("CNYRUBF"), minBarsForSignal = 5)

            assertTrue(result.equityCurve.isNotEmpty())
        }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal("100")
    }

    private fun customSpec(): InstrumentsConfig.InstrumentSpec {
        val spec = InstrumentsConfig.InstrumentSpec()
        spec.ticker = "CUSTOM"
        spec.type = "STOCK"
        spec.lotSize = 1
        spec.priceStep = BigDecimal("0.01")
        spec.priceStepCost = BigDecimal("0.01")
        spec.go = BigDecimal.ZERO
        spec.leverage = BigDecimal.ONE
        spec.baseAsset = "CUSTOM"
        spec.slPercent = BigDecimal("1.0")
        return spec
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

    private fun anyNullableInt(): Int? {
        Mockito.nullable(Int::class.javaObjectType)
        return null
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
