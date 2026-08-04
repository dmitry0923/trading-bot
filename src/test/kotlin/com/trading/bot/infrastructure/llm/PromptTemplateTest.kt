package com.trading.bot.infrastructure.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PromptTemplateTest {

    private val template = PromptTemplate(
        name = "test",
        version = "default",
        system = "Ты эксперт по тикеру {{ticker}}",
        userTemplate = "Тикер: {{ticker}}\nЦена: {{currentPrice}}\nRSI: {{rsi}}"
    )

    @Test
    fun `renderUser substitutes variables`() {
        val rendered = template.renderUser(
            mapOf("ticker" to "SBER", "currentPrice" to "280.5", "rsi" to "62")
        )
        assertEquals("Тикер: SBER\nЦена: 280.5\nRSI: 62", rendered)
    }

    @Test
    fun `renderUser replaces missing variables with empty string`() {
        val rendered = template.renderUser(mapOf("ticker" to "GAZP"))
        assertEquals("Тикер: GAZP\nЦена: \nRSI: ", rendered)
    }

    @Test
    fun `renderSystem substitutes variables`() {
        val rendered = template.renderSystem(mapOf("ticker" to "SBER"))
        assertEquals("Ты эксперт по тикеру SBER", rendered)
    }

    @Test
    fun `template without placeholders is returned verbatim`() {
        val plain = PromptTemplate("plain", "default", "fixed system", "fixed user")
        assertEquals("fixed user", plain.renderUser(emptyMap()))
        assertEquals("fixed system", plain.renderSystem(emptyMap()))
    }

    @Test
    fun `whitespace inside placeholders is tolerated`() {
        val t = PromptTemplate("ws", "default", "", "a={{ x }}")
        assertEquals("a=42", t.renderUser(mapOf("x" to 42)))
    }

    @Test
    fun `partial variable name does not match`() {
        val t = PromptTemplate("partial", "default", "", "{{ticker}} vs {{tick}}")
        assertEquals("SBER vs ", t.renderUser(mapOf("ticker" to "SBER")))
    }
}
