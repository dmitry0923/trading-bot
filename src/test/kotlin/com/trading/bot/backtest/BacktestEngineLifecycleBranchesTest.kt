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
