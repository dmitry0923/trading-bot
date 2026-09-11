package com.trading.bot.backtest

import com.trading.bot.model.entity.Candle
import com.trading.bot.service.BuildIdentity
import com.trading.bot.service.LiveStrategyFingerprintProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Регрессия canonical WFA: /validate и DeploymentGate обязаны выполнять
 * walk-forward ТОЛЬКО через [WalkForwardAnalyzer]. Любое расхождение validate vs
 * gate должно быть вызвано исключительно различием входных данных
 * (holdout-сплит в [FinalHoldoutValidator]), а не различием алгоритмов.
 */
class WalkForwardCanonicalityTest {
    private val engine = Mockito.mock(BacktestEngine::class.java)
    private val validator = BacktestValidator(engine)

    private fun mockCandle(i: Int): Candle =
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

    private fun strongTrades(n: Int): BacktestResult =
        BacktestMetrics.compute(
            "SBER",
            List(n + 1) { BigDecimal("100000").add(BigDecimal.valueOf(it * 40L)) },
            tradeReturns = List(n) { 40.0 },
        )

    @Test
    fun `run and validate are the same canonical computation`() {
        val candles = List(300) { mockCandle(it) }
        val results =
            runBlocking {
                whenever(
                    engine.simulate(
                        anyString(),
                        any(),
                        any(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyOrNull(),
                        anyOrNull(),
                        any(),
                        anyOrNull(),
                        anyOrNull(),
                        anyOrNull(),
                        anyOrNull(),
                    ),
                ).thenReturn(strongTrades(3))
                val viaRun = validator.run("SBER", candles, WfaConfig(folds = 3))
                val viaValidate = validator.validate("SBER", candles, folds = 3)
                viaRun to viaValidate
            }
        assertEquals(results.second.aggregateOutOfSample.totalTrades, results.first.aggregateOutOfSample.totalTrades)
        assertEquals(results.second.consistency, results.first.consistency)
        assertEquals(results.second.aggregateOutOfSample.totalReturn, results.first.aggregateOutOfSample.totalReturn)
        assertEquals(
            results.second.folds.map { it.chosenSlPercent to it.chosenTpPercent },
            results.first.folds.map { it.chosenSlPercent to it.chosenTpPercent },
        )
    }

    @Test
    fun `final holdout validator delegates walk forward to the same analyzer`() {
        val candles = List(300) { mockCandle(it) }
        val wfaResult =
            ValidationResult(
                folds =
                    (0 until 4).map { i ->
                        FoldValidation(
                            foldIndex = i,
                            inSample = strongTrades(120),
                            outOfSample = strongTrades(120).copy(totalReturn = 0.02),
                            chosenSlPercent = 0.02,
                            chosenTpPercent = 0.04,
                        )
                    },
                aggregateOutOfSample = strongTrades(250),
            )
        val analyzed = mutableListOf<String>()

        // Интерфейс-шпион: записываем, какие данные реально пошли в WFA.
        val spy =
            object : WalkForwardAnalyzer {
                override suspend fun run(
                    ticker: String,
                    candlesIn: List<Candle>,
                    config: WfaConfig,
                ): ValidationResult {
                    analyzed.add("$ticker:${candlesIn.size}")
                    return wfaResult
                }
            }
        val buildIdentity = Mockito.mock(BuildIdentity::class.java)
        val fingerprint =
            Mockito.mock(LiveStrategyFingerprintProvider::class.java).also {
                whenever(it.strategyVersion).thenReturn("live-v2")
            }
        val holdout = strongTrades(40)

        runBlocking {
            whenever(
                engine.simulate(
                    anyString(),
                    any(),
                    any(),
                    anyInt(),
                    eq(0.02),
                    eq(0.04),
                    any(),
                    any(),
                    anyOrNull(),
                    anyOrNull(),
                    any(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                ),
            ).thenReturn(holdout)
        }

        val result =
            runBlocking {
                FinalHoldoutValidator(spy, engine, buildIdentity, fingerprint).validate(
                    "SBER",
                    candles,
                    holdoutFraction = 0.2,
                    folds = 4,
                )
            }

        // WFA вызван ОДИН раз с dev-частью (240 из 300 свечей).
        assertEquals(listOf("SBER:240"), analyzed.toList())
        // OOS-метрики результата gate — ровно те, что вернул анализатор.
        assertEquals(wfaResult.aggregateOutOfSample.totalTrades, result.walkForward.aggregateOutOfSample.totalTrades)
        assertEquals(250, result.walkForward.aggregateOutOfSample.totalTrades)
        assertTrue(result.walkForward.isPassable())
    }
}
