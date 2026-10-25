package com.trading.bot.tuning

import com.trading.bot.backtest.StrategyParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-тесты [ParameterOptimizer]: пропуск оптимизации при недостаточной статистике
 * и при отсутствии SL/TP в пунктах, выбор сетки для прибыльной и warn-зоны,
 * проверка регуляризации (порог отклонения).
 */
class ParameterOptimizerTest {
    private val optimizer = ParameterOptimizer()

    private val futuresParameters =
        StrategyParameters(
            slPercent = 0.02,
            tpPercent = 0.04,
            slPoints = 150,
            tpPoints = 300,
            confidenceThreshold = 0.6,
        )

    @Test
    fun `skips optimization when there are too few trades`() {
        val result =
            optimizer.optimize(
                currentParameters = futuresParameters,
                tradesCount = ParameterOptimizer.MIN_TRADES_FOR_OPTIMIZATION - 1,
                profitFactor = 2.0,
            )

        assertTrue(result.skipped, "optimization must be skipped on thin sample")
        assertEquals(futuresParameters, result.parameters, "parameters must stay unchanged")
        assertTrue(result.reason.contains("недостаточно данных"), "reason must explain skip: ${result.reason}")
        assertFalse(result.requiresExtraValidation)
    }

    @Test
    fun `skips optimization when sl or tp points are not configured`() {
        val percentOnly = StrategyParameters(slPercent = 0.02, tpPercent = 0.04)

        val noSl = optimizer.optimize(percentOnly.copy(slPoints = null, tpPoints = 300), tradesCount = 30, profitFactor = 2.0)
        assertTrue(noSl.skipped)
        assertEquals("SL/TP не заданы (slPoints=null, tpPoints=300)", noSl.reason)

        val noTp = optimizer.optimize(percentOnly.copy(slPoints = 150, tpPoints = null), tradesCount = 30, profitFactor = 2.0)
        assertTrue(noTp.skipped)
        assertTrue(noTp.reason.contains("tpPoints=null"))
    }

    @Test
    fun `profitable strategy keeps the first grid candidate and needs no extra validation`() {
        val result = optimizer.optimize(futuresParameters, tradesCount = 30, profitFactor = 2.0)

        assertFalse(result.skipped)
        assertFalse(result.requiresExtraValidation, "±20% grid must stay inside the 30% deviation guard: ${result.reason}")
        // Сетка ±20% с 3 шагами: первый кандидат = center - 20%.
        assertEquals(120, result.parameters.slPoints)
        assertEquals(240, result.parameters.tpPoints)
        // Остальные поля переносятся без изменений.
        assertEquals(futuresParameters.confidenceThreshold, result.parameters.confidenceThreshold)
        assertEquals(futuresParameters.slPercent, result.parameters.slPercent)
    }

    @Test
    fun `warn zone pf narrows stop and widens take profit`() {
        val result = optimizer.optimize(futuresParameters, tradesCount = 30, profitFactor = 1.1)

        assertFalse(result.skipped)
        assertEquals(120, result.parameters.slPoints, "warn zone must narrow the stop")
        assertEquals(360, result.parameters.tpPoints, "warn zone must widen the take profit")
        assertTrue(result.reason.contains("Grid search"), "reason must describe the grid: ${result.reason}")
    }

    @Test
    fun `pf exactly at pass threshold is treated as warn zone`() {
        val result = optimizer.optimize(futuresParameters, tradesCount = 30, profitFactor = PerformanceGateCheck.PF_PASS)

        assertEquals(120, result.parameters.slPoints)
        assertEquals(360, result.parameters.tpPoints, "PF == 1.2 is not > PF_PASS, so R:R improves")
    }
}
