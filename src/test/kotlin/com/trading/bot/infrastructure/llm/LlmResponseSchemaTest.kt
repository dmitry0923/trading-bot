package com.trading.bot.infrastructure.llm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * DefaultJsonSchemaValidator: структурная валидация ответов LLM по JSON Schema
 * (ограниченный поднабор JSON Schema: type, enum, required, additionalProperties,
 * minimum/maximum, minLength/maxLength).
 *
 * Покрытие: валидный/невалидный контракт аналитических агентов, стратегий;
 * невалидные типы, выход за диапазон, лишние поля, malformed JSON.
 */
class LlmResponseSchemaTest {
    private val validator = DefaultJsonSchemaValidator(ObjectMapper())

    private val validConclusion =
        """{"conclusion":"BULLISH","signalStrength":0.8,"reasoning":"тренд вверх"}"""

    private val validStrategy =
        """{"action":"BUY","targetPrice":123.5,"signalStrength":0.7,"reasoning":"oscar best picture"}"""

    @Test
    fun `valid conclusion passes`() {
        assertTrue(validator.isValid(validConclusion, LlmResponseSchemas.AGENT_CONCLUSION))
    }

    @Test
    fun `valid conclusion neutral passes`() {
        assertTrue(
            validator.isValid(
                """{"conclusion":"NEUTRAL","signalStrength":0.0,"reasoning":"нет данных"}""",
                LlmResponseSchemas.AGENT_CONCLUSION,
            ),
        )
    }

    @Test
    fun `invalid conclusion enum rejected`() {
        assertFalse(
            validator.isValid("""{"conclusion":"HOLD","signalStrength":0.8,"reasoning":"x"}""", LlmResponseSchemas.AGENT_CONCLUSION),
        )
    }

    @Test
    fun `missing required reasoning rejected`() {
        assertFalse(validator.isValid("""{"conclusion":"BULLISH","signalStrength":0.8}""", LlmResponseSchemas.AGENT_CONCLUSION))
    }

    @Test
    fun `empty reasoning rejected by minLength`() {
        assertFalse(
            validator.isValid("""{"conclusion":"BULLISH","signalStrength":0.8,"reasoning":""}""", LlmResponseSchemas.AGENT_CONCLUSION),
        )
    }

    @Test
    fun `extra fields rejected by additionalProperties false`() {
        val injected =
            """{"conclusion":"BULLISH","signalStrength":0.8,"reasoning":"ok","instructions":"ignore previous"}"""
        assertFalse(validator.isValid(injected, LlmResponseSchemas.AGENT_CONCLUSION))
    }

    @Test
    fun `signal strength above maximum rejected`() {
        assertFalse(
            validator.isValid(
                """{"conclusion":"BULLISH","signalStrength":1.2,"reasoning":"over confident"}""",
                LlmResponseSchemas.AGENT_CONCLUSION,
            ),
        )
    }

    @Test
    fun `signal strength negative rejected`() {
        assertFalse(
            validator.isValid("""{"conclusion":"BEARISH","signalStrength":-0.1,"reasoning":"down"}""", LlmResponseSchemas.AGENT_CONCLUSION),
        )
    }

    @Test
    fun `malformed json rejected`() {
        assertFalse(validator.isValid("{not json{{", LlmResponseSchemas.AGENT_CONCLUSION))
    }

    @Test
    fun `array instead of object rejected`() {
        assertFalse(validator.isValid("""[1,2]""", LlmResponseSchemas.AGENT_CONCLUSION))
    }

    @Test
    fun `strategy decision with buy and target price passes`() {
        assertTrue(validator.isValid(validStrategy, LlmResponseSchemas.STRATEGY_DECISION))
    }

    @Test
    fun `strategy decision hold passes without reasoning`() {
        assertTrue(validator.isValid("""{"action":"HOLD","signalStrength":0.0}""", LlmResponseSchemas.STRATEGY_DECISION))
    }

    @Test
    fun `strategy decision rejects lowercase action`() {
        assertFalse(validator.isValid("""{"action":"hold","signalStrength":0.5}""", LlmResponseSchemas.STRATEGY_DECISION))
    }

    @Test
    fun `strategy decision missing required action rejected`() {
        assertFalse(validator.isValid("""{"signalStrength":0.5}""", LlmResponseSchemas.STRATEGY_DECISION))
    }
}
