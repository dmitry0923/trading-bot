package com.trading.bot.backtest

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Финальный независимый holdout (аудит P1): устраняет OOS-leakage, резервируя
 * последние [holdoutFraction] истории и трогая их ОДИН раз зафиксированными
 * параметрами после WFA.
 */
class FinalHoldoutValidatorTest {
    private val capital = BigDecimal("100000")

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

    private fun strongResult(trades: Int): BacktestResult {
        val returns = List(trades) { if (it % 2 == 0) 40.0 else -15.0 }
        val equity = returns.runningFold(capital) { acc, r -> acc.add(BigDecimal.valueOf(r)) }
        return BacktestMetrics.compute("SBER", equity, tradeReturns = returns)
    }

    @Test
    fun `division cuts candles at the holdout boundary`() {
        val validator = Mockito.mock(BacktestValidator::class.java)
        val engine = mock<BacktestEngine> {}

        // Валидатор на WFA-части (первые 80% из 300 свечей = 240) возвращает
        // сильный WFA с выбранными параметрами (0.02, 0.04).
        val wfaAggregate = strongResult(250)
        val wfFolds =
            (0 until 4).map { i ->
                FoldValidation(
                    foldIndex = i,
                    inSample = wfaAggregate,
                    outOfSample = wfaAggregate.copy(totalReturn = 0.02),
                    chosenSlPercent = 0.02,
                    chosenTpPercent = 0.04,
                )
            }
        val wfResult = ValidationResult(wfFolds, wfaAggregate)

        runBlocking {
            whenever(
                validator.validate(
                    eq("SBER"),
                    any(),
                    eq(4),
                    eq(true),
                    any(),
                    anyInt(),
                    eq(1.0),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                ),
            ).thenReturn(wfResult)
        }

        val holdoutCandidate = strongResult(250)
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
            ).thenReturn(holdoutCandidate)
        }

        val validatorUnderTest = FinalHoldoutValidator(validator, engine)
        val result = runBlocking { validatorUnderTest.validate("SBER", List(300) { mockCandle(it) }, holdoutFraction = 0.2) }

        // Holdout окно = последние 20% от 300 = 60 свечей.
        assertEquals(GridParams(0.02, 0.04), result.paramsUsed)
        assertTrue(result.walkForward.folds.isNotEmpty())
        assertTrue(result.holdout.totalTrades > 0)
        assertTrue(result.holdout.isPassable())
        assertTrue(result.passed)
    }

    @Test
    fun `params are fixed from last walk forward fold`() {
        val validator = Mockito.mock(BacktestValidator::class.java)
        val engine = mock<BacktestEngine> {}
        val wfaAggregate = strongResult(120)
        val wfFolds =
            (0 until 4).map { i ->
                FoldValidation(
                    foldIndex = i,
                    inSample = wfaAggregate,
                    outOfSample = wfaAggregate.copy(totalReturn = 0.02),
                    chosenSlPercent = 0.03,
                    chosenTpPercent = 0.06,
                )
            }
        val wfResult = ValidationResult(wfFolds, wfaAggregate)
        runBlocking {
            whenever(
                validator.validate(eq("SBER"), any(), anyInt(), any(), any(), anyInt(), any(), anyOrNull(), anyOrNull(), anyOrNull()),
            ).thenReturn(wfResult)
            whenever(
                engine.simulate(anyString(), any(), any(), anyInt(), eq(0.03), eq(0.06), any(), any(), anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()),
            ).thenReturn(strongResult(40))
        }
        val validatorUnderTest = FinalHoldoutValidator(validator, engine)
        val result = runBlocking { validatorUnderTest.validate("SBER", List(300) { mockCandle(it) }, holdoutFraction = 0.2) }
        assertEquals(0.03, result.paramsUsed.slPercent)
        assertEquals(0.06, result.paramsUsed.tpPercent)
    }

    @Test
    fun `failing holdout result is not passed`() {
        val validator = Mockito.mock(BacktestValidator::class.java)
        val engine = mock<BacktestEngine> {}
        val wfaAggregate = strongResult(120)
        val wfFolds =
            (0 until 4).map { i ->
                FoldValidation(i, strongResult(10), strongResult(10), 0.02, 0.04)
            }
        val wfResult = ValidationResult(wfFolds, wfaAggregate)
        // Слабая equity-кривая holdout -> не passable.
        val weakHoldout =
            BacktestMetrics.compute(
                "SBER",
                List(41) { capital.subtract(BigDecimal.valueOf(it * 300L)) },
                tradeReturns = List(40) { -200.0 },
            )
        runBlocking {
            whenever(
                validator.validate(eq("SBER"), any(), anyInt(), any(), any(), anyInt(), any(), anyOrNull(), anyOrNull(), anyOrNull()),
            ).thenReturn(wfResult)
            whenever(
                engine.simulate(anyString(), any(), any(), anyInt(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()),
            ).thenReturn(weakHoldout)
        }
        val result = runBlocking { FinalHoldoutValidator(validator, engine).validate("SBER", List(300) { mockCandle(it) }, holdoutFraction = 0.2) }
        assertFalse(result.holdout.isPassable())
        assertFalse(result.passed)
    }

    @Test
    fun `invalid holdout fraction rejected`() {
        val validator = Mockito.mock(BacktestValidator::class.java)
        val engine = mock<BacktestEngine> {}
        val validatorUnderTest = FinalHoldoutValidator(validator, engine)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { validatorUnderTest.validate("SBER", List(100) { mockCandle(it) }, holdoutFraction = 0.0) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { validatorUnderTest.validate("SBER", List(100) { mockCandle(it) }, holdoutFraction = 1.0) }
        }
    }
}
