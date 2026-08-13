package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.MlConfig
import com.trading.bot.config.MtfConfig
import com.trading.bot.domain.ml.MlFeatureVector
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import com.trading.bot.service.HigherTfTrendFilter
import com.trading.bot.service.MlEntryFilter
import com.trading.bot.service.MlFeatureResolver
import com.trading.bot.service.ml.MlModel
import com.trading.bot.service.ml.MlModelProvider
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime

class BacktestEngineTest {
    private val engine =
        BacktestEngine(
            CandleRepository(Mockito.mock(DatabaseClient::class.java)),
        )

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

    /** Нисходящий тренд: RSI низкий, MACD hist должен давать BUY на дне. */
    private fun candles(): List<Candle> = (0 until 300).map { i -> candle(300.0 - i * 0.5, i) }

    /** V-образная серия: падение до oversold, затем рост — детерминированный BUY. */
    private fun trendingCandles(): List<Candle> {
        val prices =
            (0 until 100).map { 200.0 - it * 1.0 } +
                (100 until 300).map { 100.0 + (it - 100) * 0.5 }
        return prices.mapIndexed { i, price -> candle(price, i) }
    }

    @Test
    fun `simulate produces results on trending data`() {
        val candles = candles()
        val result = runBlocking { engine.simulate("SBER", candles) }

        assertTrue(result.totalTrades >= 0)
        assertTrue(result.equityCurve.isNotEmpty())
        assertTrue(result.sharpeRatio.isFinite())
        assertTrue(result.profitFactor >= 0.0)
    }

    @Test
    fun `sharpe ratio is zero for flat returns`() {
        val result =
            BacktestMetrics.compute(
                "SBER",
                listOf(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                listOf(0.0, 0.0, 0.0),
            )
        assertEquals(0.0, result.sharpeRatio)
    }

    @Test
    fun `max drawdown is computed correctly`() {
        val mdd =
            BacktestMetrics.maxDrawdown(
                listOf(
                    BigDecimal("1.00"),
                    BigDecimal("1.50"),
                    BigDecimal("1.20"),
                    BigDecimal("1.30"),
                    BigDecimal("0.90"),
                ),
            )
        // пик 1.50 -> минимум 0.90 => DD = 1 - 0.9/1.5 = 0.4
        assertEquals(0.4, mdd, 1e-6)
    }

    @Test
    fun `acceptance criteria reject weak results`() {
        val result =
            BacktestResult(
                ticker = "SBER",
                totalReturn = 0.05,
                sharpeRatio = 0.8,
                maxDrawdown = 0.25,
                winRate = 0.4,
                profitFactor = 1.0,
                totalTrades = 50,
                avgHoldBars = 3.0,
                equityCurve = emptyList(),
                monthlyReturns = emptyMap(),
            )
        assertFalse(result.isPassable())
    }

    @Test
    fun `acceptance criteria pass strong results`() {
        val result =
            BacktestResult(
                ticker = "SBER",
                totalReturn = 0.30,
                sharpeRatio = 1.5,
                sortinoRatio = 1.8,
                maxDrawdown = 0.10,
                winRate = 0.55,
                profitFactor = 1.8,
                totalTrades = 250,
                avgHoldBars = 3.0,
                equityCurve = emptyList(),
                monthlyReturns = emptyMap(),
                expectancy = 120.0,
                winLossRatio = 1.4,
                avgTrade = 100.0,
                recoveryFactor = 3.0,
            )
        assertTrue(result.isPassable())
    }

    @Test
    fun `backtest metrics include risk and quality ratios`() {
        val result =
            BacktestMetrics.compute(
                "SBER",
                listOf(BigDecimal("100000"), BigDecimal("101000"), BigDecimal("99000"), BigDecimal("102000")),
                listOf(1000.0, -500.0, 2000.0),
            )
        assertEquals(3, result.totalTrades)
        assertEquals(2.0 / 3.0, result.winRate, 1e-9)
        assertTrue(result.sortinoRatio.isFinite())
        // AvgTrade = средний P&L сделки; Expectancy (Van Tharp) для $-доходностей
        // совпадает с ним: (Win% × AvgWin) − (Loss% × AvgLoss) = 833.33
        assertEquals(2500.0 / 3.0, result.avgTrade, 1e-9)
        assertEquals((2.0 / 3.0) * 1500.0 - (1.0 / 3.0) * 500.0, result.expectancy, 1e-9)
        assertEquals(1500.0 / 500.0, result.winLossRatio, 1e-9)
        assertEquals(2500.0 / result.maxDrawdown, result.recoveryFactor, 1e-9)
    }

    @Test
    fun `metrics map is compact and excludes heavy series`() {
        val result =
            BacktestResult(
                ticker = "SBER",
                totalReturn = 0.30,
                sharpeRatio = 1.5,
                sortinoRatio = 1.8,
                maxDrawdown = 0.10,
                winRate = 0.55,
                profitFactor = 1.8,
                totalTrades = 250,
                avgHoldBars = 3.0,
                equityCurve = listOf(BigDecimal("100000"), BigDecimal("110000")),
                monthlyReturns = mapOf("2026-07" to 0.05),
                tradeReturns = listOf(100.0, -50.0),
                expectancy = 120.0,
                winLossRatio = 1.4,
                avgTrade = 100.0,
                recoveryFactor = 3.0,
            )

        val metrics = result.metrics()

        assertEquals(13, metrics.size)
        assertEquals(1.5, metrics["sharpeRatio"])
        assertEquals(250, metrics["totalTrades"])
        assertEquals(true, metrics["passable"])
        assertFalse(metrics.containsKey("equityCurve"))
        assertFalse(metrics.containsKey("monthlyReturns"))
        assertFalse(metrics.containsKey("tradeReturns"))
    }

    @Test
    fun `backtest config exposes default values`() {
        val config = BacktestConfig()
        assertEquals(BigDecimal("100000"), config.initialCapital)
        assertEquals(365, config.days)
        assertEquals("MINUTE_10", config.timeframe)
        assertEquals(30, config.minBarsForSignal)
        assertEquals(2.0, config.slPercent)
        assertEquals(4.0, config.tpPercent)
        assertEquals(0.20, config.capitalSlice)
        assertFalse(config.mlFilterEnabled)
        assertEquals(1000, config.monteCarloSimulations)
        assertEquals(42L, config.monteCarloSeed)
    }

    @Test
    fun `stress multipliers degrade backtest equity`() {
        val base = runBlocking { engine.simulate("SBER", trendingCandles()) }
        assertTrue(base.totalTrades > 0, "fixture must produce trades")
        val commissionStress =
            runBlocking { engine.simulate("SBER", trendingCandles(), commissionMultiplier = 5.0) }
        val slippageStress =
            runBlocking { engine.simulate("SBER", trendingCandles(), slippageMultiplier = 5.0) }
        val combined =
            runBlocking { engine.simulate("SBER", trendingCandles(), commissionMultiplier = 3.0, slippageMultiplier = 3.0) }

        assertTrue(
            commissionStress.equityCurve.last() < base.equityCurve.last(),
            "commission x5 must reduce equity, base=${base.equityCurve.last()} stress=${commissionStress.equityCurve.last()}",
        )
        assertTrue(
            slippageStress.equityCurve.last() < base.equityCurve.last(),
            "slippage x5 must reduce equity, base=${base.equityCurve.last()} stress=${slippageStress.equityCurve.last()}",
        )
        assertTrue(
            combined.equityCurve.last() < commissionStress.equityCurve.last(),
            "combined stress must be harsher than commission alone",
        )
        assertEquals(base.totalTrades, commissionStress.totalTrades, "stress must not change trade count")
    }

    @Test
    fun `initial capital from config scales equity proportionally`() {
        val base = runBlocking { engine.simulate("SBER", trendingCandles()) }
        val big =
            runBlocking {
                BacktestEngine(
                    CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                    backtestConfig =
                        BacktestConfig().apply {
                            initialCapital = BigDecimal("200000")
                        },
                ).simulate("SBER", trendingCandles())
            }
        assertTrue(base.totalTrades > 0, "fixture must produce trades")
        val ratio = big.equityCurve.last().toDouble() / base.equityCurve.last().toDouble()
        assertTrue(ratio in 1.5..2.5, "expected ~2x equity scaling with doubled capital, got $ratio")
    }

    @Test
    fun `capital slice from config changes position size`() {
        val base = runBlocking { engine.simulate("SBER", trendingCandles()) }
        val doubled =
            runBlocking {
                BacktestEngine(
                    CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                    backtestConfig =
                        BacktestConfig().apply {
                            capitalSlice = 0.40
                        },
                ).simulate("SBER", trendingCandles())
            }
        assertTrue(base.totalTrades > 0, "fixture must produce trades")
        val baseDeviation =
            base.equityCurve
                .last()
                .subtract(BigDecimal("100000"))
                .abs()
        val doubledDeviation =
            doubled.equityCurve
                .last()
                .subtract(BigDecimal("100000"))
                .abs()
        assertTrue(
            doubledDeviation > baseDeviation.multiply(BigDecimal("1.5")),
            "doubling capital slice should scale P&L, base=${base.equityCurve.last()} doubled=${doubled.equityCurve.last()}",
        )
    }

    @Test
    fun `commission and slippage constants`() {
        assertEquals(BigDecimal("0.0005"), SimulatedExecution.COMMISSION_RATE)
        assertEquals(BigDecimal("0.001"), SimulatedExecution.MARKET_SLIPPAGE_RATE)
    }

    @Test
    fun `execution costs are parameterizable for stress runs`() {
        // Комиссия по параметризованной ставке: price * rate
        val highCommission = SimulatedExecution.commissionOn(BigDecimal("100000"), BigDecimal("0.001"))
        assertEquals(BigDecimal("100.0000"), highCommission)
        // Проскальзывание по удвоенной ставке: цена отклоняется в 2 раза сильнее
        val buy = SimulatedExecution.marketFill(BigDecimal("100"), isBuy = true, slippageRate = BigDecimal("0.002"))
        assertEquals(0, BigDecimal("100.2").compareTo(buy.price))
        val sell = SimulatedExecution.marketFill(BigDecimal("100"), isBuy = false, slippageRate = BigDecimal("0.002"))
        assertEquals(0, BigDecimal("99.8").compareTo(sell.price))
    }

    @Test
    fun `ml filter blocks all entries when enabled and model rejects`() {
        val modelProvider = Mockito.mock(MlModelProvider::class.java)
        val featureResolver = Mockito.mock(MlFeatureResolver::class.java)
        val meterRegistry = SimpleMeterRegistry()
        val mlEntryFilter = MlEntryFilter(MlConfig(), modelProvider, featureResolver, meterRegistry)
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(BtRecordingModel(0.3))
            Mockito.`when`(featureResolver.resolve(any(), any(), any(), anyOrNull(), any())).thenReturn(btVector())
        }
        val filteredEngine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                meterRegistry = meterRegistry,
                backtestConfig =
                    BacktestConfig().apply {
                        mlFilterEnabled = true
                    },
                mlEntryFilter = mlEntryFilter,
            )

        val result = runBlocking { filteredEngine.simulate("SBER", trendingCandles()) }

        assertEquals(0, result.totalTrades, "ML-фильтр должен блокировать все входы")
        val blocked = meterRegistry.counter("bt_ml_blocked_total", "ticker", "SBER").count()
        assertTrue(blocked > 0, "метрика блокировок должна увеличиваться, got $blocked")
    }

    @Test
    fun `ml filter pass-through keeps trades when model allows`() {
        val modelProvider = Mockito.mock(MlModelProvider::class.java)
        val featureResolver = Mockito.mock(MlFeatureResolver::class.java)
        val mlEntryFilter = MlEntryFilter(MlConfig(), modelProvider, featureResolver, SimpleMeterRegistry())
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(BtRecordingModel(0.9))
            Mockito.`when`(featureResolver.resolve(any(), any(), any(), anyOrNull(), any())).thenReturn(btVector())
        }
        val filteredEngine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                backtestConfig =
                    BacktestConfig().apply {
                        mlFilterEnabled = true
                    },
                mlEntryFilter = mlEntryFilter,
            )

        val result = runBlocking { filteredEngine.simulate("SBER", trendingCandles()) }

        assertTrue(result.totalTrades > 0, "pass-through фильтра не должен блокировать сделки")
    }

    @Test
    fun `ml filter is not consulted when bt flag disabled`() {
        val mlEntryFilter = Mockito.mock(MlEntryFilter::class.java)
        val engineWithoutFlag =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                mlEntryFilter = mlEntryFilter,
            )

        runBlocking { engineWithoutFlag.simulate("SBER", trendingCandles()) }

        runBlocking {
            Mockito.verify(mlEntryFilter, Mockito.never()).shouldBlock(any(), any(), anyOrNull(), any(), any())
        }
    }

    @Test
    fun `mtf filter blocks opposing entries when enabled`() {
        val meterRegistry = SimpleMeterRegistry()
        val mtfFilter =
            HigherTfTrendFilter(
                MtfConfig(),
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                meterRegistry,
            )
        val filteredEngine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                meterRegistry = meterRegistry,
                backtestConfig =
                    BacktestConfig().apply {
                        mtfFilterEnabled = true
                    },
                higherTfTrendFilter = mtfFilter,
            )

        // V-образная серия: BUY-сигналы рождаются у дна (тренд старшего ТФ ещё
        // не накоплен → fail-closed блок) и на подъёме тренд UP противоположен
        // возможным SELL-сигналам у вершин → все входы блокируются.
        val result = runBlocking { filteredEngine.simulate("SBER", trendingCandles()) }

        assertEquals(0, result.totalTrades, "MTF-фильтр должен блокировать все входы в нисходящем тренде")
        val blocked = meterRegistry.counter("bt_mtf_blocked_total", "ticker", "SBER").count()
        assertTrue(blocked > 0, "метрика блокировок должна увеличиваться, got $blocked")
    }

    @Test
    fun `mtf filter pass-through keeps trades when filter allows`() {
        val higherTfTrendFilter = Mockito.mock(HigherTfTrendFilter::class.java)
        val filteredEngine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                backtestConfig =
                    BacktestConfig().apply {
                        mtfFilterEnabled = true
                    },
                higherTfTrendFilter = higherTfTrendFilter,
            )

        val result = runBlocking { filteredEngine.simulate("SBER", trendingCandles()) }

        assertTrue(result.totalTrades > 0, "pass-through фильтра не должен блокировать сделки")
    }

    @Test
    fun `mtf filter is not consulted when bt flag disabled`() {
        val higherTfTrendFilter = Mockito.mock(HigherTfTrendFilter::class.java)
        val engineWithoutFlag =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                higherTfTrendFilter = higherTfTrendFilter,
            )

        runBlocking { engineWithoutFlag.simulate("SBER", trendingCandles()) }

        runBlocking {
            Mockito.verify(higherTfTrendFilter, Mockito.never()).shouldBlock(any(), any(), any(), any(), any())
        }
    }

    private class BtRecordingModel(
        private val probability: Double,
    ) : MlModel {
        override val available: Boolean = true
        override val unavailableReason: String? = null

        override fun probability(
            numeric: FloatArray,
            categorical: Array<String>,
        ): Double = probability
    }

    private fun btVector(): MlFeatureVector =
        MlFeatureVector(
            rsi14 = 50.0,
            atrPercent = 1.0,
            macdHistogramPercent = 0.0,
            bbPercentB = 50.0,
            emaSlopePercent = 0.0,
            volatility20Percent = 1.0,
            return3 = 0.0,
            return10 = 0.0,
            return20 = 0.0,
            cbrRate = 16.0,
            brentPrice = 75.0,
            usdRub = 90.0,
            strategyConfidence = 0.8,
            inBlindSpotHour = 0,
            hourOfDay = 14,
            strategyAction = "BUY",
            direction = "LONG",
        )

    @Test
    fun `lot rounding is down to whole lots of instrument`() {
        // SBER lot = 10
        assertEquals(0, SimulatedExecution.lotRounded(5, 10))
        assertEquals(10, SimulatedExecution.lotRounded(10, 10))
        assertEquals(90, SimulatedExecution.lotRounded(99, 10))
        // VTBR lot = 1000
        assertEquals(0, SimulatedExecution.lotRounded(999, 1000))
        assertEquals(1000, SimulatedExecution.lotRounded(1500, 1000))
        // неизвестный инструмент (lotSize <= 0) — лотность игнорируется
        assertEquals(77, SimulatedExecution.lotRounded(77, 0))
        assertEquals(77, SimulatedExecution.lotRounded(77, -1))
    }
}
