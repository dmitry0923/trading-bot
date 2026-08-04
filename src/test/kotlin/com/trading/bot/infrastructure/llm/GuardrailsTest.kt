package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import com.trading.bot.model.StrategyAction
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class GuardrailsTest {
    private fun guardrails(): Guardrails {
        val cfg = LlmConfig().apply { guardrailsMaxPriceDeviationPercent = 3.0 }
        return Guardrails(cfg, SimpleMeterRegistry())
    }

    private fun buySignal(
        confidence: Double = 0.8,
        price: BigDecimal = BigDecimal("100"),
        qty: Int = 10,
    ) = Guardrails.Signal(
        action = StrategyAction.BUY,
        targetPrice = price,
        quantity = qty,
        stopLoss = BigDecimal("98"),
        takeProfit = BigDecimal("104"),
        trailingStop = true,
        confidence = confidence,
    )

    @Test
    fun `high confidence buy passes through unchanged`() {
        val result = guardrails().apply(buySignal(), marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6)
        assertFalse(result.overridden)
        assertEquals(StrategyAction.BUY, result.signal.action)
        assertEquals(BigDecimal("100"), result.signal.targetPrice)
    }

    @Test
    fun `low confidence signal is overridden to HOLD`() {
        val result = guardrails().apply(buySignal(confidence = 0.4), marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6)
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
    fun `daily loss limit forces HOLD`() {
        val result = guardrails().apply(buySignal(), marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6, dailyLossLimitReached = true)
        assertTrue(result.overridden)
        assertEquals(StrategyAction.HOLD, result.signal.action)
        assertEquals("DETERMINISTIC: DAILY_LOSS_LIMIT", result.overrideReason)
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
    fun `zero quantity is overridden to HOLD`() {
        val result = guardrails().apply(buySignal(qty = 0), marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6)
        assertTrue(result.overridden)
        assertEquals(StrategyAction.HOLD, result.signal.action)
    }

    @Test
    fun `hold signal is not processed further`() {
        val hold = Guardrails.Signal.hold(BigDecimal("100"))
        val result = guardrails().apply(hold, marketPrice = BigDecimal("100"), adaptiveThreshold = 0.6)
        assertFalse(result.overridden)
        assertNull(result.overrideReason)
        assertEquals(StrategyAction.HOLD, result.signal.action)
    }
}
