package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import com.trading.bot.model.StrategyAction
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class GuardrailsTest {
    private fun guardrails(): Guardrails {
        val cfg = LlmConfig().apply { guardrailsMaxPriceDeviationPercent = 3.0 }
        return Guardrails(cfg, SimpleMeterRegistry())
    }

    private fun buySignal(
        signalStrength: Double = 0.8,
        price: BigDecimal = BigDecimal("100"),
    ) = Guardrails.Signal(
        action = StrategyAction.BUY,
        targetPrice = price,
        signalStrength = signalStrength,
    )

    @Test
    fun `high signalStrength buy passes through unchanged`() {
        val result = guardrails().apply(buySignal(), marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6)
        assertFalse(result.overridden)
        assertEquals(StrategyAction.BUY, result.signal.action)
        assertEquals(BigDecimal("100"), result.signal.targetPrice)
    }

    @Test
    fun `low signalStrength signal is overridden to HOLD`() {
        val result = guardrails().apply(buySignal(signalStrength = 0.4), marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6)
        assertTrue(result.overridden)
        assertEquals(StrategyAction.HOLD, result.signal.action)
        assertEquals("GUARDRAIL: LOW_CONFIDENCE", result.overrideReason)
    }

    @Test
    fun `critical risk level forces HOLD`() {
        val result = guardrails().apply(buySignal(), marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6, riskLevel = "CRITICAL")
        assertTrue(result.overridden)
        assertEquals(StrategyAction.HOLD, result.signal.action)
        assertEquals("DETERMINISTIC: RISK_CRITICAL", result.overrideReason)
    }

    @Test
    fun `price deviation above threshold is adjusted to market price`() {
        val result =
            guardrails().apply(
                buySignal(price = BigDecimal("105")),
                marketPrice = BigDecimal("100"),
                adaptiveThreshold = 0.6,
            )
        assertTrue(result.overridden)
        assertEquals(StrategyAction.BUY, result.signal.action)
        assertEquals(BigDecimal("100"), result.signal.targetPrice)
        assertEquals("GUARDRAIL: PRICE_DEVIATION", result.overrideReason)
    }

    @Test
    fun `small price deviation is kept`() {
        val result =
            guardrails().apply(
                buySignal(price = BigDecimal("101")),
                marketPrice = BigDecimal("100"),
                adaptiveThreshold = 0.6,
            )
        assertFalse(result.overridden)
        assertEquals(BigDecimal("101"), result.signal.targetPrice)
    }

    @Test
    fun `hold signal is not processed further`() {
        val hold = Guardrails.Signal.hold(BigDecimal("100"))
        val result = guardrails().apply(hold, marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6)
        assertFalse(result.overridden)
        assertNull(result.overrideReason)
        assertEquals(StrategyAction.HOLD, result.signal.action)
    }

    @Test
    fun `hold is short-circuited before critical check`() {
        val result =
            guardrails().apply(
                Guardrails.Signal.hold(BigDecimal("100")),
                marketPrice = BigDecimal("100"),
                adaptiveThreshold = 0.6,
                riskLevel = "CRITICAL",
            )
        assertFalse(result.overridden)
        assertNull(result.overrideReason)
        assertTrue(result.appliedRules.isEmpty())
        assertEquals(StrategyAction.HOLD, result.signal.action)
    }

    @Test
    fun `sell signal passes through unchanged`() {
        val sell =
            Guardrails.Signal(
                action = StrategyAction.SELL,
                targetPrice = BigDecimal("100"),
                signalStrength = 0.8,
            )
        val result = guardrails().apply(sell, marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6)
        assertFalse(result.overridden)
        assertEquals(StrategyAction.SELL, result.signal.action)
        assertEquals(BigDecimal("100"), result.signal.targetPrice)
    }

    @Test
    fun `lowercase critical is not treated as critical`() {
        val result =
            guardrails().apply(
                buySignal(signalStrength = 0.4),
                marketPrice = BigDecimal("100"),
                adaptiveThreshold = 0.6,
                riskLevel = "critical",
            )
        assertTrue(result.overridden)
        assertEquals("GUARDRAIL: LOW_CONFIDENCE", result.overrideReason)
    }

    @Test
    fun `deviation exactly at threshold is kept`() {
        val result =
            guardrails().apply(
                buySignal(price = BigDecimal("103")),
                marketPrice = BigDecimal("100"),
                adaptiveThreshold = 0.6,
            )
        assertFalse(result.overridden)
        assertEquals(BigDecimal("103"), result.signal.targetPrice)
    }

    @Test
    fun `deviation just above threshold is adjusted`() {
        val result =
            guardrails().apply(
                buySignal(price = BigDecimal("103.01")),
                marketPrice = BigDecimal("100"),
                adaptiveThreshold = 0.6,
            )
        assertTrue(result.overridden)
        assertEquals(BigDecimal("100"), result.signal.targetPrice)
        assertEquals("GUARDRAIL: PRICE_DEVIATION", result.overrideReason)
    }

    @Test
    fun `zero market price throws on deviation calculation`() {
        assertThrows(ArithmeticException::class.java) {
            guardrails().apply(buySignal(), marketPrice = BigDecimal.ZERO, adaptiveThreshold = 0.6)
        }
    }

    @Test
    fun `override records deterministic metric with reason tag`() {
        val registry = SimpleMeterRegistry()
        val result =
            Guardrails(LlmConfig().apply { guardrailsMaxPriceDeviationPercent = 3.0 }, registry)
                .apply(buySignal(price = BigDecimal("105")), marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6)
        assertTrue(result.overridden)
        val counter =
            registry
                .find("arbitrator.deterministic.override")
                .tags("reason", "PRICE_DEVIATION")
                .counter()
        assertEquals(1.0, counter!!.count())
    }
}
