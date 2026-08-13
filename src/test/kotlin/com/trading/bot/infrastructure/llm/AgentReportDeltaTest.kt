package com.trading.bot.infrastructure.llm

import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.TechnicalReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты дельта-компрессии отчётов агентов (roadmap 13.8).
 *
 * Семантика: null на первой оценке (нет предыдущего отчёта) → агент использует
 * полный текст; "NO_CHANGE" при идентичных значениях; перечисление только
 * изменённых полей в остальных случаях.
 */
class AgentReportDeltaTest {
    private val tech =
        TechnicalReport(
            trend = "UP",
            rsi = 45.0,
            atr = 1.0,
            conclusion = "BULLISH",
            confidence = 0.7,
            reasoning = "strong momentum above MA",
        )

    @Test
    fun `null delta on first evaluation - full reasoning is used`() {
        assertNull(AgentReportDelta.technical(null, tech))
        assertNull(
            AgentReportDelta.fundamental(
                null,
                FundamentalReport("NEUTRAL", 0.5, "macro ok"),
            ),
        )
    }

    @Test
    fun `NO_CHANGE when previous report is identical`() {
        assertEquals("NO_CHANGE", AgentReportDelta.technical(tech, tech))
        val fund = FundamentalReport("NEUTRAL", 0.5, "macro ok")
        assertEquals("NO_CHANGE", AgentReportDelta.fundamental(fund, fund))
    }

    @Test
    fun `lists only changed technical fields with old to new values`() {
        val current = tech.copy(rsi = 46.2)
        val delta = AgentReportDelta.technical(tech, current)

        assertTrue(delta!!.contains("rsi: 45.00→46.20"))
        assertTrue(delta.contains("trend: UP→UP") == false)
        assertTrue(delta.contains("conclusion") == false)
    }

    @Test
    fun `truncates long reasoning diffs`() {
        val longReasoning = "x".repeat(500)
        val prev = tech.copy(reasoning = "y".repeat(300))
        val current = tech.copy(reasoning = longReasoning)

        val delta = AgentReportDelta.technical(prev, current)

        assertTrue(delta!!.contains("→" + "x".repeat(120) + "..."))
        assertTrue(delta.length < 300)
    }

    @Test
    fun `fundamental delta reports conclusion confidence and reasoning changes`() {
        val prev = FundamentalReport("NEUTRAL", 0.5, "macro ok")
        val current = FundamentalReport("BULLISH", 0.6, "rate cut expected")
        val delta = AgentReportDelta.fundamental(prev, current)

        assertTrue(delta!!.contains("conclusion: NEUTRAL→BULLISH"))
        assertTrue(delta.contains("confidence: 0.50→0.60"))
        assertTrue(delta.contains("reasoning: macro ok→rate cut expected"))
    }
}
