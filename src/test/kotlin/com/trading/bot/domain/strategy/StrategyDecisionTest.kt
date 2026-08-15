package com.trading.bot.domain.strategy

import com.trading.bot.model.StrategyAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Тесты адаптивного порога уверенности как ГЕЙТА входа (roadmap 13.11.8, 13.23.2,
 * CYCLE-01): BUY/SELL ниже порога или с non-finite силой -> HOLD; HOLD не трогается.
 */
class StrategyDecisionTest {
    private val price = BigDecimal("150.0")

    @Test
    fun `buy at or above threshold passes unchanged`() {
        val decision = StrategyDecision(StrategyAction.BUY, price, 0.65, "buy")

        val gated = StrategyDecision.gatedByConfidence(decision, price, 0.60)

        assertEquals(decision, gated)
        assertEquals(StrategyAction.BUY, gated.action)
        assertEquals(0.65, gated.signalStrength, 1e-9)
    }

    @Test
    fun `buy exactly at threshold passes`() {
        val decision = StrategyDecision(StrategyAction.BUY, price, 0.60, "buy")

        val gated = StrategyDecision.gatedByConfidence(decision, price, 0.60)

        assertEquals(StrategyAction.BUY, gated.action)
    }

    @Test
    fun `buy below threshold is held with zero strength and reason`() {
        val decision = StrategyDecision(StrategyAction.BUY, price, 0.42, "buy")

        val gated = StrategyDecision.gatedByConfidence(decision, price, 0.60)

        assertEquals(StrategyAction.HOLD, gated.action)
        assertEquals(0.0, gated.signalStrength, 1e-9)
        assertTrue(gated.reasoning.contains("0.6"))
    }

    @Test
    fun `sell below threshold is held`() {
        val decision = StrategyDecision(StrategyAction.SELL, price, 0.30, "sell")

        val gated = StrategyDecision.gatedByConfidence(decision, price, 0.55)

        assertEquals(StrategyAction.HOLD, gated.action)
        assertEquals(0.0, gated.signalStrength, 1e-9)
    }

    @Test
    fun `non-finite strength fails closed to hold`() {
        val decision = StrategyDecision(StrategyAction.BUY, price, Double.NaN, "NaN poison from advisor")

        val gated = StrategyDecision.gatedByConfidence(decision, price, 0.60)

        assertEquals(StrategyAction.HOLD, gated.action)
        assertEquals(0.0, gated.signalStrength, 1e-9)
    }

    @Test
    fun `existing hold decision is not re-gated`() {
        val decision = StrategyDecision.hold(price, "already holding")

        val gated = StrategyDecision.gatedByConfidence(decision, price, 0.60)

        assertEquals(decision, gated)
    }
}
