package com.trading.bot.backtest

import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime

class BacktestValidatorTest {
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

    // 3 OOS-сделки на фолд: 3 фолда x 3 = 9 агрегированных OOS-сделок.
    private fun result(): BacktestResult =
        BacktestMetrics.compute(
            "SBER",
            List(OOS_TRADES + 1) { BigDecimal("100000").add(BigDecimal.valueOf(it * 40L)) },
            List(OOS_TRADES) { 40.0 },
        )

    @Test
    fun `insufficient candles produce empty non-robust result`() {
        val result = runBlocking { validator.validate("SBER", emptyList(), folds = 4) }
        assertTrue(result.folds.isEmpty())
        assertFalse(result.isRobust())
        assertEquals(0.0, result.consistency)
    }

    @Test
    fun `fold with too-small train window falls back to first grid parameters`() {
        val result =
            runBlocking {
                whenever(engine.simulate(anyString(), any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(result())
                validator.validate("SBER", List(300) { mockCandle(it) }, folds = 3)
            }
        // Первый фолд: train пуст (недостаточно баров для настройки) -> дефолт первой пары сетки (0.01, 0.02).
        assertEquals(0.01, result.folds[0].chosenSlPercent)
        assertEquals(0.02, result.folds[0].chosenTpPercent)
        // Последующие фолды имеют достаточно train-баров -> тюнинг по сетке.
        assertTrue(result.folds[1].chosenSlPercent in listOf(0.01, 0.02, 0.03))
    }

    @Test
    fun `aggregate with no out-of-sample trades is empty and not robust`() {
        val noTrades = BacktestMetrics.compute("SBER", listOf(BigDecimal("100000")), emptyList())
        val result =
            runBlocking {
                whenever(engine.simulate(anyString(), any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(noTrades)
                validator.validate("SBER", List(300) { mockCandle(it) }, folds = 3)
            }
        assertEquals(3, result.folds.size)
        assertEquals(0, result.aggregateOutOfSample.totalTrades)
        assertFalse(result.isRobust())
    }

    private companion object {
        const val OOS_TRADES = 3
    }

    @Test
    fun `validation produces per-fold and aggregate out-of-sample results`() {
        val result =
            runBlocking {
                whenever(engine.simulate(anyString(), any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(result())
                validator.validate("SBER", List(300) { mockCandle(it) }, folds = 3)
            }
        assertEquals(3, result.folds.size)
        assertEquals(OOS_TRADES, result.folds[0].outOfSample.totalTrades)
        // 3 фолда x 3 OOS-сделки
        assertEquals(3 * OOS_TRADES, result.aggregateOutOfSample.totalTrades)
        assertTrue(result.consistency in 0.0..1.0)
        assertTrue(result.aggregateOutOfSample.equityCurve.isNotEmpty())
    }

    @Test
    fun `isRobust rejects insufficient out-of-sample trades`() {
        val weakAggregate =
            BacktestMetrics.compute(
                "SBER",
                listOf(BigDecimal("100000"), BigDecimal("100500")),
                listOf(500.0),
            )
        val result = ValidationResult(folds = emptyList(), aggregateOutOfSample = weakAggregate)
        assertFalse(result.isRobust())
    }

    @Test
    fun `isRobust passes strong out-of-sample aggregates`() {
        val returns = List(250) { if (it % 2 == 0) 30.0 else 50.0 }
        val equity = returns.runningFold(BigDecimal("100000")) { acc, r -> acc.add(BigDecimal.valueOf(r)) }
        val strongAggregate = BacktestMetrics.compute("SBER", equity, returns)
        val folds =
            (0 until 4).map { i ->
                FoldValidation(
                    foldIndex = i,
                    inSample = strongAggregate,
                    outOfSample = strongAggregate.copy(totalReturn = 0.02),
                    chosenSlPercent = 0.02,
                    chosenTpPercent = 0.04,
                )
            }
        val result = ValidationResult(folds, strongAggregate)
        assertTrue(result.isRobust())
        assertEquals(1.0, result.consistency)
    }
}
