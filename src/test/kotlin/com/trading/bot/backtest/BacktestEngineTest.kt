package com.trading.bot.backtest

import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.MlConfig
import com.trading.bot.config.MtfConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.ml.MlFeatureVector
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.PositionSizer
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.BacktestResultRepository
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
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
    fun `sharpe and sortino are computed from equity curve returns not per-trade`() {
        // Ровные сделки давали бы Sharpe = 0 при расчёте по сделкам; по кривой
        // капитала с волатильностью путь учитывается, и Sharpe/Sortino > 0.
        val result =
            BacktestMetrics.compute(
                "SBER",
                listOf(
                    BigDecimal("100000"),
                    BigDecimal("100300"),
                    BigDecimal("99800"),
                    BigDecimal("100400"),
                    BigDecimal("100500"),
                ),
                listOf(40.0, 40.0, 40.0, 40.0),
            )
        assertTrue(result.sharpeRatio > 0.0)
        assertTrue(result.sortinoRatio > 0.0)
        assertEquals(4, result.totalTrades)
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
                // Кривая капитала согласована со сделками: 1000 → −500 → +2000
                listOf(BigDecimal("100000"), BigDecimal("101000"), BigDecimal("100500"), BigDecimal("102500")),
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
                calmarRatio = 3.0,
            )

        val metrics = result.metrics()

        assertEquals(14, metrics.size)
        assertEquals(1.5, metrics["sharpeRatio"])
        assertEquals(250, metrics["totalTrades"])
        assertEquals(true, metrics["passable"])
        assertEquals(3.0, metrics["calmarRatio"])
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
            runBlocking { engine.simulate("SBER", trendingCandles(), commissionMultiplier = 5.0, slippageMultiplier = 5.0) }

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
            "combined stress must be harsher than commission alone, " +
                "combined=${combined.equityCurve.last()} (trades=${combined.totalTrades}) " +
                "commission=${commissionStress.equityCurve.last()} (trades=${commissionStress.totalTrades})",
        )
        assertTrue(
            combined.equityCurve.last() < slippageStress.equityCurve.last(),
            "combined stress must be harsher than slippage alone, " +
                "combined=${combined.equityCurve.last()} (trades=${combined.totalTrades}) " +
                "slippage=${slippageStress.equityCurve.last()} (trades=${slippageStress.totalTrades})",
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
        // Комиссия по параметризованной ставке: price * qty * rate (qty=1 по умолчанию)
        val highCommission = SimulatedExecution.commissionOn(BigDecimal("100000"), 1, BigDecimal("0.001"))
        assertEquals(BigDecimal("100.0000"), highCommission)
        // Проскальзывание по удвоенной ставке: цена отклоняется в 2 раза сильнее
        val buy = SimulatedExecution.marketFill(BigDecimal("100"), isBuy = true, slippageRate = BigDecimal("0.002"))
        assertEquals(0, BigDecimal("100.2").compareTo(buy.price))
        val sell = SimulatedExecution.marketFill(BigDecimal("100"), isBuy = false, slippageRate = BigDecimal("0.002"))
        assertEquals(0, BigDecimal("99.8").compareTo(sell.price))
    }

    @Test
    fun `commission scales with quantity for full turnover`() {
        // Пример из ревью: цена 300 ₽, количество 1000, ставка 0.05%
        // раньше комиссия = 300 * 0.0005 = 0.15 ₽ (вместо 150 ₽) — quantity игнорировался.
        assertEquals(0, BigDecimal("150").compareTo(SimulatedExecution.commissionOn(BigDecimal("300"), 1000)))
        // единичное количество — без изменений
        assertEquals(0, BigDecimal("0.1500").compareTo(SimulatedExecution.commissionOn(BigDecimal("300"))))
        // стресс-ставка (умноженная) тоже учитывает оборот
        assertEquals(
            0,
            BigDecimal("1500").compareTo(SimulatedExecution.commissionOn(BigDecimal("300"), 1000, BigDecimal("0.005"))),
        )
    }

    @Test
    fun `pnl charges commission on the full position size`() {
        // flat 100 ₽, constant BUY: qty = 100000 * 0.2 / 100 = 200 акций SBER (лот 10).
        // entry fill = 100.1 (slippage 0.1%), exit fill = 99.9.
        // gross = (99.9 - 100.1) * 200 = -40 ₽
        // комиссии = (100.1*200 + 99.9*200) * 0.0005 = 20 ₽
        // итог equity = 100000 - 40 - 20 = 99940 ₽ (раньше было ~99959.9).
        val engine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                signalGenerator = ConstantSignalGenerator(StrategyAction.BUY),
            )

        val result = runBlocking { engine.simulate("SBER", flatCandles()) }

        assertEquals(1, result.totalTrades)
        assertEquals(-60.0, result.tradeReturns.single(), 1e-9)
        assertEquals(0, BigDecimal("99940").compareTo(result.equityCurve.last()))
    }

    @Test
    fun `stock fallback size is capped by risk per trade`() {
        // capitalSlice = 1.0 → slice дал бы 1000 акций; риск-кап (1% портфеля против
        // 2% стопа, как StockEntryProfile) ограничивает qty = (100000*0.01)/(100*0.02) = 500.
        // entry fill = 100.1, exit fill = 99.9, qty = 500:
        // gross = (99.9-100.1)*500 = -100; комиссии = (100.1+99.9)*500*0.0005 = 50
        // equity = 100000 - 150 = 99850 (без капа при qty=1000 было бы 99700).
        val engine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                backtestConfig =
                    BacktestConfig().apply {
                        capitalSlice = 1.0
                    },
                signalGenerator = ConstantSignalGenerator(StrategyAction.BUY),
            )

        val result = runBlocking { engine.simulate("SBER", flatCandles()) }

        assertEquals(1, result.totalTrades)
        assertEquals(-150.0, result.tradeReturns.single(), 1e-9)
        assertEquals(0, BigDecimal("99850").compareTo(result.equityCurve.last()))
    }

    @Test
    fun `backtest sizes futures through the production PositionSizer`() {
        // Si @ 92000: старый fallback capitalSlice (20% от 100k = 20k) давал бы
        // qty = 0 (92000 > 20000) → 0 сделок. Production-сайзер (риск на сделку)
        // даёт 1 контракт → сделки есть. Это доказывает, что backtest использует
        // тот же алгоритм сайзинга, что и live.
        val sizer = FuturesPositionSizer(RiskConfig(), InstrumentsConfig())
        val engine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                instrumentsConfig = InstrumentsConfig(),
                positionSizer = sizer,
                riskConfig = RiskConfig(),
                signalGenerator = ConstantSignalGenerator(StrategyAction.BUY),
            )

        val result = runBlocking { engine.simulate("Si", siCandles()) }

        assertTrue(result.totalTrades > 0, "production sizer must allow Si entries that the 20%% slice would reject")
        // Slippage для фьючерсов считается в пунктах (1 тик = 0.01 ₽), а не 0.1% цены
        // (~92 пункта, больше стопа в 50 пунктов): процентная ставка + стоп-лосс на
        // флэте съедали ~75% капитала (~25k), пунктовая оставляет только реалистичную
        // комиссию от сотен кругосветок по стопу (~72k).
        assertTrue(
            result.equityCurve.last() > BigDecimal("60000"),
            "percent-based slippage must not wipe futures equity, last=${result.equityCurve.last()}",
        )
    }

    @Test
    fun `backtest consults PositionSizer for futures tickers only`() {
        val sizer = Mockito.mock(PositionSizer::class.java)
        Mockito
            .`when`(
                sizer.calculateContracts(any(), any(), any(), any(), anyOrNull(), anyOrNull()),
            ).thenReturn(
                PositionSizeResult(
                    quantity = 3,
                    marginRequired = BigDecimal("45000"),
                    riskAmount = BigDecimal("1000"),
                    liquidationPrice = null,
                    reason = null,
                ),
            )
        val sizerEngine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                positionSizer = sizer,
                signalGenerator = ConstantSignalGenerator(StrategyAction.BUY),
            )

        val futures = runBlocking { sizerEngine.simulate("Si", flatCandles()) }
        assertTrue(futures.totalTrades > 0, "futures ticker must route through PositionSizer")
        val verifiedSi = Mockito.verify(sizer, Mockito.atLeastOnce())
        verifiedSi.calculateContracts(eq("Si"), any(), any(), any(), anyOrNull(), anyOrNull())

        Mockito.clearInvocations(sizer)
        val stock = runBlocking { sizerEngine.simulate("SBER", flatCandles()) }
        assertTrue(stock.totalTrades > 0, "stock ticker must use capital-slice fallback")
        val verifiedSber = Mockito.verify(sizer, Mockito.never())
        verifiedSber.calculateContracts(eq("SBER"), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `atr stop widens futures stop versus fixed default`() {
        // Si-флэт с широкими свечами: ATR(14) = 400 пунктов -> стоп упирается в
        // max 100 пунктов (фиксированный дефолт — 50). При том же qty=1 риск на
        // сделку = stopPoints * priceStepCost вдвое больше, поэтому риск-бюджет
        // (1% портфеля) исчерпывается вдвое раньше => сделок существенно меньше.
        val atrEngine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                instrumentsConfig = InstrumentsConfig(),
                positionSizer = FuturesPositionSizer(RiskConfig(), InstrumentsConfig()),
                riskConfig = RiskConfig(),
                signalGenerator = ConstantSignalGenerator(StrategyAction.BUY),
            )
        val fixedEngine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                instrumentsConfig = InstrumentsConfig(),
                positionSizer = FuturesPositionSizer(RiskConfig(), InstrumentsConfig()),
                riskConfig = RiskConfig().apply { futuresAtrStopEnabled = false },
                signalGenerator = ConstantSignalGenerator(StrategyAction.BUY),
            )

        val atr = runBlocking { atrEngine.simulate("Si", siCandles()) }
        val fixed = runBlocking { fixedEngine.simulate("Si", siCandles()) }

        assertTrue(fixed.totalTrades > 0, "fixture must produce trades")
        assertTrue(atr.totalTrades > 0, "ATR stop must not block all entries")
        assertTrue(
            atr.totalTrades < fixed.totalTrades,
            "wider ATR stop must exhaust the risk budget sooner, " +
                "atr=${atr.totalTrades} fixed=${fixed.totalTrades}",
        )
        assertTrue(
            atr.tradeReturns.average() < fixed.tradeReturns.average(),
            "wider ATR stop must lose more per trade, " +
                "atr=${atr.tradeReturns.average()} fixed=${fixed.tradeReturns.average()}",
        )
    }

    @Test
    fun `tick fill applies point-based slippage for futures`() {
        // 1 тик Si = 0.01 ₽: 0.1% цены (92 ₽ ≈ 9200 тиков) нереалистично для
        // биржевого исполнения market-ордера по фьючерсу (исполнение в пунктах).
        val buy = SimulatedExecution.tickFill(BigDecimal("92000"), isBuy = true, ticks = 1, tickSize = BigDecimal("0.01"))
        assertEquals(0, BigDecimal("92000.01").compareTo(buy.price))
        val sell = SimulatedExecution.tickFill(BigDecimal("92000"), isBuy = false, ticks = 1, tickSize = BigDecimal("0.01"))
        assertEquals(0, BigDecimal("91999.99").compareTo(sell.price))
        val twoTicks = SimulatedExecution.tickFill(BigDecimal("92000"), isBuy = true, ticks = 2, tickSize = BigDecimal("0.01"))
        assertEquals(0, BigDecimal("92000.02").compareTo(twoTicks.price))
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

    @Test
    fun `hitStopOrTarget detects stop and target within candle range`() {
        val candle =
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal("100"),
                highPrice = BigDecimal("105"),
                lowPrice = BigDecimal("95"),
                closePrice = BigDecimal("100"),
                volume = 1000L,
                time = LocalDateTime.now(),
            )
        assertEquals(SimulatedExecution.StopTpHit.STOP, SimulatedExecution.hitStopOrTarget(candle, BigDecimal("96"), BigDecimal("106")))
        assertEquals(SimulatedExecution.StopTpHit.STOP, SimulatedExecution.hitStopOrTarget(candle, BigDecimal("95"), BigDecimal("106")))
        assertEquals(SimulatedExecution.StopTpHit.TARGET, SimulatedExecution.hitStopOrTarget(candle, BigDecimal("90"), BigDecimal("104")))
        assertEquals(SimulatedExecution.StopTpHit.TARGET, SimulatedExecution.hitStopOrTarget(candle, BigDecimal("90"), BigDecimal("105")))
        assertNull(SimulatedExecution.hitStopOrTarget(candle, BigDecimal("94"), BigDecimal("106")))
        assertEquals(SimulatedExecution.StopTpHit.STOP, SimulatedExecution.hitStopOrTarget(candle, BigDecimal("96"), BigDecimal("104")))
    }

    @Test
    fun `profit factor win-loss ratio and recovery factor are infinite with only wins`() {
        val result =
            BacktestMetrics.compute(
                "SBER",
                listOf(BigDecimal("100000"), BigDecimal("101000"), BigDecimal("102000")),
                listOf(1000.0, 1000.0),
            )
        assertEquals(Double.POSITIVE_INFINITY, result.profitFactor)
        assertEquals(Double.POSITIVE_INFINITY, result.winLossRatio)
        assertEquals(Double.POSITIVE_INFINITY, result.recoveryFactor)
        assertEquals(1.0, result.winRate)
    }

    @Test
    fun `profit factor is zero with only losing trades`() {
        val result =
            BacktestMetrics.compute(
                "SBER",
                listOf(BigDecimal("100000"), BigDecimal("99500"), BigDecimal("99200")),
                listOf(-500.0, -300.0),
            )
        assertEquals(0.0, result.profitFactor)
        assertEquals(0.0, result.winLossRatio)
        assertEquals(0.0, result.winRate)
        assertEquals(2, result.totalTrades)
    }

    @Test
    fun `deterministic generator holds when window is below min bars`() {
        runBlocking {
            val gen = DeterministicBacktestSignalGenerator()
            assertEquals(StrategyAction.HOLD, gen.signal("SBER", candles(), 10, 30, "cycle"))
            assertEquals(StrategyAction.HOLD, gen.signal("SBER", candles(), 0, 30, "cycle"))
        }
    }

    @Test
    fun `deterministic generator emits buy and sell on trend turn fixtures`() {
        runBlocking {
            val gen = DeterministicBacktestSignalGenerator()
            val signals = mutableListOf<StrategyAction>()
            for (i in 30 until 300) {
                signals += gen.signal("SBER", trendingCandles(), i, 30, "cycle")
                signals += gen.signal("SBER", peakCandles(), i, 30, "cycle")
            }
            assertTrue(signals.contains(StrategyAction.BUY), "V-образная серия должна давать BUY у дна")
            assertTrue(signals.contains(StrategyAction.SELL), "перевёрнутая V-серия должна давать SELL у вершины")
            assertTrue(signals.contains(StrategyAction.HOLD), "на промежутках должны быть удержания")
        }
    }

    @Test
    fun `run returns empty result when too few candles and skips persist`() {
        val candleRepo = Mockito.mock(CandleRepository::class.java)
        val resultRepo = Mockito.mock(BacktestResultRepository::class.java)
        val engine = BacktestEngine(candleRepo, backtestResultRepository = resultRepo)
        runBlocking {
            Mockito
                .`when`(
                    candleRepo.findByTickerAndTimeframeAndTimeBetween(
                        any(),
                        any(),
                        any(),
                        any(),
                    ),
                ).thenReturn(emptyList())

            val result = engine.run("SBER", days = 5, timeframe = "MINUTE_10")

            assertEquals(0, result.totalTrades)
            assertEquals(0.0, result.totalReturn)
            Mockito.verify(resultRepo, Mockito.never()).save(any())
        }
    }

    @Test
    fun `run persists result and increments pass metric`() {
        val candleRepo = Mockito.mock(CandleRepository::class.java)
        val resultRepo = Mockito.mock(BacktestResultRepository::class.java)
        val meterRegistry = SimpleMeterRegistry()
        val engine = BacktestEngine(candleRepo, backtestResultRepository = resultRepo, meterRegistry = meterRegistry)
        runBlocking {
            Mockito
                .`when`(
                    candleRepo.findByTickerAndTimeframeAndTimeBetween(
                        any(),
                        any(),
                        any(),
                        any(),
                    ),
                ).thenReturn(trendingCandles())

            val result = engine.run("SBER", days = 5, timeframe = "MINUTE_10")

            assertTrue(result.totalTrades > 0)
            Mockito.verify(resultRepo).save(any())
        }
        val pass =
            meterRegistry
                .find("bt_pass_total")
                .tag("result", "PASS")
                .counter()
                ?.count() ?: 0.0
        val reject =
            meterRegistry
                .find("bt_pass_total")
                .tag("result", "REJECT")
                .counter()
                ?.count() ?: 0.0
        assertTrue(pass + reject > 0, "bt_pass_total должен инкрементироваться")
    }

    @Test
    fun `persist failure does not break the run`() {
        val candleRepo = Mockito.mock(CandleRepository::class.java)
        val resultRepo = Mockito.mock(BacktestResultRepository::class.java)
        val engine = BacktestEngine(candleRepo, backtestResultRepository = resultRepo)
        runBlocking {
            Mockito
                .`when`(
                    candleRepo.findByTickerAndTimeframeAndTimeBetween(
                        any(),
                        any(),
                        any(),
                        any(),
                    ),
                ).thenReturn(trendingCandles())
            Mockito.`when`(resultRepo.save(any())).thenThrow(RuntimeException("db down"))

            val result = engine.run("SBER", days = 5, timeframe = "MINUTE_10")

            assertTrue(result.totalTrades > 0, "сбой персиста не должен ронять прогон")
        }
    }

    @Test
    fun `tiny capital opens no positions because of lot rounding`() {
        val result = runBlocking { engine.simulate("SBER", candles(), initialCapital = BigDecimal("0.01")) }
        assertEquals(0, result.totalTrades)
        assertTrue(result.equityCurve.isNotEmpty())
        assertTrue(result.equityCurve.all { it == BigDecimal("0.01") })
    }

    @Test
    fun `zero capital opens no positions`() {
        val result = runBlocking { engine.simulate("SBER", candles(), initialCapital = BigDecimal.ZERO) }
        assertEquals(0, result.totalTrades)
        assertTrue(result.equityCurve.isNotEmpty())
        assertTrue(result.equityCurve.all { it == BigDecimal.ZERO })
    }

    @Test
    fun `CLOSE signal is treated as hold and opens no position`() {
        val closeEngine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                signalGenerator = ConstantSignalGenerator(StrategyAction.CLOSE),
            )

        val result = runBlocking { closeEngine.simulate("SBER", candles()) }

        assertEquals(0, result.totalTrades)
        assertEquals(candles().size, result.equityCurve.size)
        assertTrue(result.equityCurve.all { it == BigDecimal("100000") })
    }

    @Test
    fun `ml filter reversal closes open position when opposite entry blocked`() {
        val meterRegistry = SimpleMeterRegistry()
        val mlEntryFilter = Mockito.mock(MlEntryFilter::class.java)
        runBlocking {
            Mockito
                .`when`(
                    mlEntryFilter.shouldBlock(
                        any(),
                        any(),
                        anyOrNull(),
                        any(),
                        Mockito.eq(false),
                    ),
                ).thenAnswer { inv ->
                    if (inv.getArgument<StrategyAction>(1) == StrategyAction.SELL) "blocked-by-ml" else null
                }
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
                signalGenerator = SwitchSignalGenerator(switchAfter = 50, first = StrategyAction.BUY, second = StrategyAction.SELL),
            )

        val result = runBlocking { filteredEngine.simulate("SBER", flatCandles()) }

        assertEquals(1, result.totalTrades, "заблокированная инверсия закрывает позицию ровно один раз")
        val blocked = meterRegistry.counter("bt_ml_blocked_total", "ticker", "SBER").count()
        assertTrue(blocked > 0, "метрика блокировок должна увеличиваться, got $blocked")
    }

    @Test
    fun `mtf filter reversal closes open position when opposite entry blocked`() {
        val meterRegistry = SimpleMeterRegistry()
        val higherTfTrendFilter = Mockito.mock(HigherTfTrendFilter::class.java)
        runBlocking {
            Mockito
                .`when`(
                    higherTfTrendFilter.shouldBlock(
                        any(),
                        any(),
                        Mockito.anyList(),
                        any(),
                        Mockito.eq(false),
                    ),
                ).thenAnswer { inv ->
                    if (inv.getArgument<StrategyAction>(1) == StrategyAction.SELL) "blocked-by-mtf" else null
                }
        }
        val filteredEngine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                meterRegistry = meterRegistry,
                backtestConfig =
                    BacktestConfig().apply {
                        mtfFilterEnabled = true
                    },
                higherTfTrendFilter = higherTfTrendFilter,
                signalGenerator = SwitchSignalGenerator(switchAfter = 50, first = StrategyAction.BUY, second = StrategyAction.SELL),
            )

        val result = runBlocking { filteredEngine.simulate("SBER", flatCandles()) }

        assertEquals(1, result.totalTrades, "заблокированная инверсия закрывает позицию ровно один раз")
        val blocked = meterRegistry.counter("bt_mtf_blocked_total", "ticker", "SBER").count()
        assertTrue(blocked > 0, "метрика блокировок должна увеличиваться, got $blocked")
    }

    private fun peakCandles(): List<Candle> {
        val prices = (0 until 150).map { 100.0 + it * 1.0 } + (150 until 300).map { 250.0 - (it - 150) * 1.0 }
        return prices.mapIndexed { i, price -> candle(price, i) }
    }

    private fun flatCandles(count: Int = 300): List<Candle> =
        (0 until count).map { i ->
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal("100"),
                highPrice = BigDecimal("101"),
                lowPrice = BigDecimal("99"),
                closePrice = BigDecimal("100"),
                volume = 1000L,
                time = LocalDateTime.now().plusMinutes(10L * i),
            )
        }

    /** Si-фьючерс на реальных уровнях цены (~92 000): capitalSlice-fallback даёт 0 лотов. */
    private fun siCandles(count: Int = 300): List<Candle> =
        (0 until count).map { i ->
            Candle(
                ticker = "Si",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal("92000"),
                highPrice = BigDecimal("92200"),
                lowPrice = BigDecimal("91800"),
                closePrice = BigDecimal("92000"),
                volume = 1000L,
                time = LocalDateTime.now().plusMinutes(10L * i),
            )
        }

    private class ConstantSignalGenerator(
        private val action: StrategyAction,
    ) : BacktestSignalGenerator {
        override suspend fun signal(
            ticker: String,
            candles: List<Candle>,
            index: Int,
            minBars: Int,
            cycleId: String,
        ): StrategyAction = action
    }

    private class SwitchSignalGenerator(
        private val switchAfter: Int,
        private val first: StrategyAction,
        private val second: StrategyAction,
    ) : BacktestSignalGenerator {
        override suspend fun signal(
            ticker: String,
            candles: List<Candle>,
            index: Int,
            minBars: Int,
            cycleId: String,
        ): StrategyAction = if (index < switchAfter) first else second
    }
}
