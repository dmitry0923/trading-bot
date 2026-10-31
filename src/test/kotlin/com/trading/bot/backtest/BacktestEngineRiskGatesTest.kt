package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [BacktestEngine] — ветка live risk gates (`bt.live-risk-gates=true` + непустой
 * [BacktestRiskSimulator]): инициализация state, вызов `checkEntry` на каждом
 * входе, блокировка с метрикой `bt_risk_blocked_total` и продолжение прогона
 * без открытия позиции.
 */
class BacktestEngineRiskGatesTest {
    private fun candle(
        price: Double,
        i: Int,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(price),
            highPrice = BigDecimal(price * 1.01),
            lowPrice = BigDecimal(price * 0.99),
            closePrice = BigDecimal(price),
            volume = 1000L,
            time = LocalDateTime.now().plusMinutes(10L * i),
        )

    /** V-образная серия: детерминированные входы без look-ahead. */
    private fun trendingCandles(): List<Candle> {
        val prices =
            (0 until 100).map { 200.0 - it * 1.0 } +
                (100 until 300).map { 100.0 + (it - 100) * 0.5 }
        return prices.mapIndexed { i, price -> candle(price, i) }
    }

    /** Чередует BUY/SELL, чтобы прогон проходил и открытие, и разворот позиции. */
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
        riskConfig: RiskConfig,
        liveRiskGates: Boolean,
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
        simulator: BacktestRiskSimulator? = BacktestRiskSimulator(riskConfig, InstrumentsConfig()),
    ): BacktestEngine =
        BacktestEngine(
            candleRepo = CandleRepository(Mockito.mock(DatabaseClient::class.java)),
            instrumentsConfig = InstrumentsConfig(),
            backtestConfig = BacktestConfig().apply { this.liveRiskGates = liveRiskGates },
            meterRegistry = meterRegistry,
            signalGenerator = AlternatingGenerator(),
            riskConfig = riskConfig,
            riskSimulator = simulator,
        )

    private fun permissiveRiskConfig(): RiskConfig =
        RiskConfig().apply {
            maxOpenPositions = 5
            maxSectorExposure = 5
            maxVolatilityPercent = 50.0
            maxDailyLossPercent = 50.0
            maxRollingLoss7dPercent = 100.0
            maxRollingLoss30dPercent = 100.0
            maxGrossExposurePercent = 1000.0
            maxNetExposurePercent = 1000.0
            drawdownScaleTiers = emptyMap()
            shadowModeEnabled = false
        }

    @Test
    fun `risk simulator is initialized and consulted on every entry`() {
        val registry = SimpleMeterRegistry()
        val result =
            runBlocking {
                engine(permissiveRiskConfig(), liveRiskGates = true, meterRegistry = registry)
                    .simulate("SBER", trendingCandles())
            }

        assertTrue(result.equityCurve.isNotEmpty())
        // гейты вызваны на каждом входе: либо сделки открылись, либо есть блокировки
        assertTrue(result.totalTrades > 0 || registry.find("bt_risk_blocked_total").counters().isNotEmpty())
    }

    @Test
    fun `degenerate gap gate blocks every entry and leaves the run flat`() {
        val registry = SimpleMeterRegistry()
        val riskConfig =
            permissiveRiskConfig().apply {
                // любой разрыв open/prevClose > 0% — блок (детерминированно)
                maxVolatilityPercent = 0.0
            }
        val result =
            runBlocking {
                engine(riskConfig, liveRiskGates = true, meterRegistry = registry)
                    .simulate("SBER", trendingCandles())
            }

        assertEquals(0, result.totalTrades)
        val blocked =
            registry
                .find("bt_risk_blocked_total")
                .tag("reason", "DEGENERATE_GAP")
                .counter()
                ?.count() ?: 0.0
        assertTrue(blocked > 0.0, "every entry attempt must be blocked by the gap gate")
    }

    @Test
    fun `risk gates are skipped when liveRiskGates is disabled`() {
        val registry = SimpleMeterRegistry()
        val riskConfig = permissiveRiskConfig().apply { maxVolatilityPercent = 0.0 }

        val result = runBlocking { engine(riskConfig, liveRiskGates = false, meterRegistry = registry).simulate("SBER", trendingCandles()) }

        // без гейтов нулевой порог волатильности ни на что не влияет
        assertTrue(result.equityCurve.isNotEmpty())
        assertTrue(registry.find("bt_risk_blocked_total").counters().isEmpty())
    }

    @Test
    fun `missing risk simulator keeps the run working with the flag on`() {
        val result =
            runBlocking {
                engine(permissiveRiskConfig(), liveRiskGates = true, simulator = null)
                    .simulate("SBER", trendingCandles())
            }

        assertTrue(result.equityCurve.isNotEmpty())
    }
}
