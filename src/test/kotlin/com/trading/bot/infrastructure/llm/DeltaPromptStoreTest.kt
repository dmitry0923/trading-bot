package com.trading.bot.infrastructure.llm

import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.TechnicalReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты хранилища последних отчётов для дельта-промптов (roadmap 13.8).
 *
 * Проверяется: независимость по тикерам, null на первой оценке, NO_CHANGE на
 * идентичных повторных, обновление состояния после цикла и сброс.
 */
class DeltaPromptStoreTest {
    private val store = DeltaPromptStore()

    private val tech =
        TechnicalReport(
            trend = "UP",
            rsi = 45.0,
            atr = 1.0,
            conclusion = "BULLISH",
            confidence = 0.7,
            reasoning = "momentum above MA",
        )

    private val fund = FundamentalReport("NEUTRAL", 0.5, "macro ok")

    @Test
    fun `first evaluation per ticker has no delta`() {
        assertNull(store.techDelta("SBER", tech))
        assertNull(store.fundDelta("SBER", fund))
    }

    @Test
    fun `delta produced after update and NO_CHANGE when nothing changed`() {
        store.update("SBER", tech, fund)

        val techDelta = store.techDelta("SBER", tech)
        val fundDelta = store.fundDelta("SBER", fund)

        assertEquals("NO_CHANGE", techDelta)
        assertEquals("NO_CHANGE", fundDelta)
    }

    @Test
    fun `delta lists changes after the state moved`() {
        store.update("SBER", tech, fund)
        val moved = tech.copy(rsi = 52.0, conclusion = "BEARISH")

        val delta = store.techDelta("SBER", moved)

        assertEquals("conclusion: BULLISH→BEARISH; rsi: 45.00→52.00", delta)
    }

    @Test
    fun `tickers are independent`() {
        store.update("SBER", tech, fund)

        assertNull(store.techDelta("GAZP", tech))
        assertNull(store.fundDelta("GAZP", fund))
    }

    @Test
    fun `clear resets all tickers`() {
        store.update("SBER", tech, fund)
        store.clear()

        assertNull(store.techDelta("SBER", tech))
    }
}
