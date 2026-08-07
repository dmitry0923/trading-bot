package com.trading.bot.infrastructure.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmResponseTest {
    @Test
    fun `fallback carries the reason as errorMessage`() {
        val resp = LlmResponse.fallback("NO_API_KEY")
        assertTrue(resp.isFallback)
        assertEquals("NO_API_KEY", resp.errorMessage)
        assertTrue(resp.content.contains("NO_API_KEY"))
    }

    @Test
    fun `fallback with explicit message prefers message over reason`() {
        val resp = LlmResponse.fallback("CALL_ERROR", "connection refused: host")
        assertEquals("connection refused: host", resp.errorMessage)
    }

    @Test
    fun `successful response has no errorMessage`() {
        assertNull(LlmResponse(content = "{}").errorMessage)
    }
}
