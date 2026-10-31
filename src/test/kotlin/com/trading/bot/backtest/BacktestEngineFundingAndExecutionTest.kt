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
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [BacktestEngine] — funding-гейты и исполнение: point-in-time funding-veto
 * (неизвестная ставка fail-closed, блокировка разворота с
 * `FUNDING_VETO_REVERSAL`), недоступность ряда ставок (fallback), ликвидация
 * SHORT-позиции по high бара, risk-gate без причины и funding-window без ставки
 * до даты бара.
 */
class BacktestEngineFundingAndExecutionTest {
    private val start = LocalDateTime.of(2026, 3, 2, 10, 0)

    private fun candle(
        ticker: String,
        price: Double,
        index: Int,
        high: Double = price,
        low: Double = price,
    ): Candle =
        Candle(
            ticker = ticker,
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(price),
            highPrice = BigDecimal(high),
            lowPrice = BigDecimal(low),
            closePrice = BigDecimal(price),
            volume = 1_000L,
            time = start.plusMinutes(10L * index),
        )

    private fun risingCandles(
        ticker: String,
        count: Int = 60,
        step: Double = 0.5,
    ): List<Candle> =
        (0 until count).map { i ->
            val price = 100.0 + i * step
            candle(ticker, price, i, high = price + step, low = price - step)
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

    private class ShortOnlyGenerator : BacktestSignalGenerator {
        override suspend fun signal(
            ticker: String,
            candles: List<Candle>,
            index: Int,
            minBars: Int,
            cycleId: String,
        ): StrategyAction = StrategyAction.SELL
    }

    private fun engine(
        config: BacktestConfig,
        generator: BacktestSignalGenerator = AlternatingGenerator(),
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
        positionSizer: PositionSizer? = null,
        fundingRepo: FundingHistoryRepository? = null,
        riskSimulator: BacktestRiskSimulator? = null,
    ): BacktestEngine =
        BacktestEngine(
            candleRepo = CandleRepository(Mockito.mock(DatabaseClient::class.java)),
            instrumentsConfig = InstrumentsConfig(),
            backtestConfig = config,
            meterRegistry = meterRegistry,
            signalGenerator = generator,
            positionSizer = positionSizer,
            riskConfig = RiskConfig(),
            riskSimulator = riskSimulator,
            fundingHistoryRepository = fundingRepo,
        )

    private fun baseConfig(): BacktestConfig =
        BacktestConfig().apply {
            liveRiskGates = false
            futuresLiquidationSimulation = true
        }

    @Test
    fun `funding veto blocks an opposite entry and closes the position`(): Unit =
        runBlocking {
            val funding = mock<FundingHistoryRepository>()
            whenever(funding.findValuesBetween(org.mockito.kotlin.any(), anyDate(), anyDate())).thenReturn(
                mapOf(LocalDate.of(2026, 2, 27) to BigDecimal("5.0")),
            )
            val registry = SimpleMeterRegistry()
            val config =
                baseConfig().apply {
                    fundingVetoEnabled = true
                    fundingVetoLongThresholdRub = 1.0
                    fundingVetoShortThresholdRub = 1.0
                    fundingVetoBlockOnUnknown = true
                }

            val result =
                engine(config = config, meterRegistry = registry, fundingRepo = funding)
                    .simulate("SBER", risingCandles("SBER"), minBarsForSignal = 5)

            // LONG при ставке 5 ₽ > порога блокируется; на инверсии SHORT→LONG
            // позиция закрывается (FUNDING_VETO_REVERSAL).
            assertTrue(result.trades.any { it.closeReason == "FUNDING_VETO_REVERSAL" }, "${result.trades.map { it.closeReason }}")
            assertTrue(registry.counter("bt_funding_veto_blocked_total", "ticker", "SBER").count() > 0)
        }

    @Test
    fun `unavailable funding history fails closed when configured and falls back otherwise`(): Unit =
        runBlocking {
            val failing = mock<FundingHistoryRepository>()
            whenever(failing.findValuesBetween(org.mockito.kotlin.any(), anyDate(), anyDate()))
                .thenThrow(IllegalStateException("db down"))

            val failClosed =
                baseConfig().apply {
                    fundingVetoEnabled = true
                    fundingVetoBlockOnUnknown = true
                }
            val blocked =
                engine(config = failClosed, fundingRepo = failing)
                    .simulate("SBER", risingCandles("SBER"), minBarsForSignal = 5)
            assertTrue(blocked.trades.isEmpty(), "неизвестная ставка при fail-closed — входов нет")

            // Ряд недоступен, но fail-open: прогон идёт на обычных сигналах.
            val failOpen = baseConfig().apply { fundingVetoEnabled = false }
            val traded =
                engine(config = failOpen, fundingRepo = failing)
                    .simulate("SBER", risingCandles("SBER"), minBarsForSignal = 5)
            assertTrue(traded.trades.isNotEmpty(), "без veto недоступный ряд — просто fallback")
        }

    @Test
    fun `short position is liquidated when the bar high crosses the liquidation price`(): Unit =
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
            ).thenAnswer { inv ->
                val entryPrice = inv.getArgument<BigDecimal>(4)
                PositionSizeResult(
                    quantity = 1,
                    marginRequired = BigDecimal("1275"),
                    riskAmount = BigDecimal("500"),
                    liquidationPrice = entryPrice.add(BigDecimal("5")),
                    reason = null,
                )
            }

            val result =
                engine(
                    config = baseConfig(),
                    generator = ShortOnlyGenerator(),
                    positionSizer = sizer,
                ).simulate(
                    "CNYRUBF",
                    risingCandles("CNYRUBF", count = 60, step = 1.0),
                    minBarsForSignal = 5,
                    slPoints = 50_000,
                    tpPoints = 50_000,
                )

            assertTrue(result.trades.any { it.closeReason == "LIQUIDATION" }, "${result.trades.map { it.closeReason }}")
        }

    @Test
    fun `risk gate rejection without a reason is metered as UNKNOWN`(): Unit =
        runBlocking {
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
            val registry = SimpleMeterRegistry()
            val config =
                baseConfig().apply {
                    liveRiskGates = true
                }

            val result =
                engine(
                    config = config,
                    meterRegistry = registry,
                    riskSimulator = simulator,
                ).simulate("SBER", risingCandles("SBER"), minBarsForSignal = 5)

            assertEquals(0, result.trades.size, "risk gate блокирует все входы")
            assertTrue(
                registry.counter("bt_risk_blocked_total", "ticker", "SBER", "reason", "UNKNOWN").count() >= 1.0,
                "блокировки без причины метятся как UNKNOWN",
            )
        }

    @Test
    fun `funding window without a rate before the bar stays flat`(): Unit =
        runBlocking {
            val funding = mock<FundingHistoryRepository>()
            // Ряд есть, но все клиринги ПОСЛЕ дат баров → point-in-time ставка неизвестна.
            whenever(funding.findValuesBetween(org.mockito.kotlin.any(), anyDate(), anyDate())).thenReturn(
                mapOf(LocalDate.of(2026, 12, 31) to BigDecimal("5.0")),
            )
            val config =
                baseConfig().apply {
                    fundingWindowEnabled = true
                    fundingWindowLongThresholdRub = 2.0
                    fundingWindowShortThresholdRub = 1.0
                    fundingWindowBlockOnUnknown = false
                }

            val result =
                engine(config = config, fundingRepo = funding)
                    .simulate("SBER", risingCandles("SBER"), minBarsForSignal = 5)

            assertTrue(result.trades.isEmpty(), "без ставки до даты бара направление неизвестно → FLAT")
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

    private fun anyAction(): StrategyAction {
        Mockito.any(StrategyAction::class.java)
        return StrategyAction.BUY
    }

    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return LocalDate.of(2026, 3, 1)
    }

    private fun anyDateTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return start
    }

    private fun anyCandle(): Candle {
        Mockito.any(Candle::class.java)
        return candle("SBER", 100.0, 0)
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
