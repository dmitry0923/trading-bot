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
import com.trading.bot.service.HigherTfTrendFilter
import com.trading.bot.service.MlEntryFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [BacktestEngine] — неохваченные крупные ветви: production-сайзинг фьючерсов
 * через [PositionSizer] (с override'ами и без), funding-window capture по
 * point-in-time ставке, а также ML/MTF-фильтры с разворотом позиции.
 */
class BacktestEngineSizingAndFiltersTest {
    private fun candle(
        ticker: String,
        price: Double,
        time: LocalDateTime,
    ): Candle =
        Candle(
            ticker = ticker,
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(price),
            highPrice = BigDecimal(price),
            lowPrice = BigDecimal(price),
            closePrice = BigDecimal(price),
            volume = 1000L,
            time = time,
        )

    private fun minuteCandles(
        ticker: String,
        count: Int = 80,
    ): List<Candle> {
        val start = LocalDateTime.of(2026, 3, 2, 10, 0)
        return List(count) { i -> candle(ticker, 100.0 + i * 0.01, start.plusMinutes(10L * i)) }
    }

    private class AlternatingGenerator : BacktestSignalGenerator {
        override suspend fun signal(
            ticker: String,
            candles: List<Candle>,
            index: Int,
            minBars: Int,
            cycleId: String,
        ): StrategyAction = if (index % 2 == 0) StrategyAction.BUY else StrategyAction.SELL
    }

    private fun engine(
        config: BacktestConfig = BacktestConfig().apply { liveRiskGates = false },
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
        positionSizer: PositionSizer? = null,
        mlEntryFilter: MlEntryFilter? = null,
        higherTfTrendFilter: HigherTfTrendFilter? = null,
        fundingRepo: FundingHistoryRepository? = null,
    ): BacktestEngine =
        BacktestEngine(
            candleRepo = CandleRepository(Mockito.mock(DatabaseClient::class.java)),
            instrumentsConfig = InstrumentsConfig(),
            backtestConfig = config,
            meterRegistry = meterRegistry,
            signalGenerator = AlternatingGenerator(),
            mlEntryFilter = mlEntryFilter,
            higherTfTrendFilter = higherTfTrendFilter,
            positionSizer = positionSizer,
            riskConfig = RiskConfig(),
            fundingHistoryRepository = fundingRepo,
        )

    private fun size(quantity: Int) =
        PositionSizeResult(
            quantity = quantity,
            marginRequired = BigDecimal("1275"),
            riskAmount = BigDecimal("500"),
            liquidationPrice = null,
            reason = null,
        )

    @Test
    fun `futures sizing uses the production sizer without overrides`(): Unit =
        runBlocking {
            val sizer = mock<PositionSizer>()
            whenever(
                sizer.calculateContracts(
                    anyString(),
                    anyBigDecimal(),
                    anyIntValue(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                ),
            ).thenReturn(size(2))

            val result =
                engine(positionSizer = sizer)
                    .simulate("CNYRUBF", minuteCandles("CNYRUBF"))

            assertTrue(result.totalTrades > 0)
            assertTrue(result.trades.all { it.quantity == 2 })
            Mockito
                .verify(sizer, Mockito.atLeastOnce())
                .calculateContracts(
                    anyString(),
                    anyBigDecimal(),
                    anyIntValue(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                )
        }

    @Test
    fun `futures sizing passes risk and contract-cap overrides`(): Unit =
        runBlocking {
            val sizer = mock<PositionSizer>()
            whenever(
                sizer.calculateContracts(
                    anyString(),
                    anyBigDecimal(),
                    anyIntValue(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                    anyNullableDouble(),
                    anyNullableInt(),
                ),
            ).thenReturn(size(3))

            val result =
                engine(positionSizer = sizer)
                    .simulate(
                        "CNYRUBF",
                        minuteCandles("CNYRUBF"),
                        riskPerTradePercent = 5.0,
                        futuresMaxContractsPerPosition = 3,
                    )

            assertTrue(result.trades.all { it.quantity == 3 })
            Mockito
                .verify(sizer, Mockito.atLeastOnce())
                .calculateContracts(
                    anyString(),
                    anyBigDecimal(),
                    anyIntValue(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyDirection(),
                    anyNullableDouble(),
                    anyNullableInt(),
                )
        }

    @Test
    fun `ml filter blocks an opposite entry and closes the current position`(): Unit =
        runBlocking {
            val filter = mock<MlEntryFilter>()
            whenever(filter.shouldBlock(anyString(), anyAction(), anyNullableDouble(), anyTime(), eqBoolean(false)))
                .thenAnswer { inv ->
                    if (inv.getArgument<StrategyAction>(1) == StrategyAction.SELL) "ML_BLOCK" else null
                }
            val registry = SimpleMeterRegistry()

            val result =
                engine(
                    config =
                        BacktestConfig().apply {
                            liveRiskGates = false
                            mlFilterEnabled = true
                        },
                    meterRegistry = registry,
                    mlEntryFilter = filter,
                ).simulate("SBER", minuteCandles("SBER"))

            assertTrue(result.trades.any { it.closeReason == "ML_FILTER_REVERSAL" })
            assertTrue(registry.counter("bt_ml_blocked_total", "ticker", "SBER").count() > 0)
        }

    @Test
    fun `mtf filter blocks an opposite entry and closes the current position`(): Unit =
        runBlocking {
            val filter = mock<HigherTfTrendFilter>()
            whenever(filter.shouldBlock(anyString(), anyAction(), anyCandles(), anyTime(), eqBoolean(false)))
                .thenAnswer { inv ->
                    if (inv.getArgument<StrategyAction>(1) == StrategyAction.SELL) "MTF_BLOCK" else null
                }
            val registry = SimpleMeterRegistry()

            val result =
                engine(
                    config =
                        BacktestConfig().apply {
                            liveRiskGates = false
                            mtfFilterEnabled = true
                        },
                    meterRegistry = registry,
                    higherTfTrendFilter = filter,
                ).simulate("SBER", minuteCandles("SBER"))

            assertTrue(result.trades.any { it.closeReason == "MTF_FILTER_REVERSAL" })
            assertTrue(registry.counter("bt_mtf_blocked_total", "ticker", "SBER").count() > 0)
        }

    @Test
    fun `funding window holds through the rate and exits when it fades`(): Unit =
        runBlocking {
            val d1 = LocalDate.of(2026, 3, 2)
            val candles =
                (0..3).flatMap { day ->
                    (0..1).map { hour ->
                        candle(
                            "SBER",
                            100.0,
                            d1.plusDays(day.toLong()).atTime(10 + hour, 0),
                        )
                    }
                }
            val funding = mock<FundingHistoryRepository>()
            whenever(funding.findValuesBetween(anyString(), anyDate(), anyDate())).thenReturn(
                mapOf(
                    d1 to BigDecimal("1.50"),
                    d1.plusDays(1) to BigDecimal("1.50"),
                    d1.plusDays(2) to BigDecimal("0.00"),
                ),
            )
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
                engine(config = config, meterRegistry = registry, fundingRepo = funding)
                    .simulate("SBER", candles, initialCapital = BigDecimal("100000"))

            assertTrue(result.trades.any { it.closeReason == "FUNDING_WINDOW_EXIT" })
            assertTrue(registry.counter("bt_funding_window_entry_total", "ticker", "SBER").count() > 0)
            assertTrue(registry.counter("bt_funding_window_exit_total", "ticker", "SBER").count() > 0)
        }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun eqBoolean(value: Boolean): Boolean {
        Mockito.eq(value)
        return value
    }

    private fun anyIntValue(): Int {
        Mockito.anyInt()
        return 50
    }

    private fun anyNullableInt(): Int? {
        Mockito.nullable(Int::class.javaObjectType)
        return null
    }

    private fun anyDirection(): PositionDirection {
        Mockito.any(PositionDirection::class.java)
        return PositionDirection.LONG
    }

    private fun anyAction(): StrategyAction {
        Mockito.any(StrategyAction::class.java)
        return StrategyAction.BUY
    }

    private fun anyNullableDouble(): Double? {
        Mockito.nullable(Double::class.javaObjectType)
        return null
    }

    private fun anyTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return LocalDateTime.now()
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyCandles(): List<Candle> {
        Mockito.any(List::class.java)
        return emptyList()
    }

    private fun anyString(): String {
        Mockito.anyString()
        return "SBER"
    }

    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return LocalDate.now()
    }
}
