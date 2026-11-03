package com.trading.bot.infrastructure.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [TokenRedactor]: маскирование токенов Alor в тексте логов/исключений (P2-1).
 */
class TokenRedactorTest {
    @Test
    fun `query token is masked and other params survive`() {
        assertEquals(
            "https://oauth.alor.ru/refresh?token=***",
            TokenRedactor.redact("https://oauth.alor.ru/refresh?token=secret-refresh"),
        )
        assertEquals(
            "wss://api.alor.ru/ws?token=***&portfolio=D1",
            TokenRedactor.redact("wss://api.alor.ru/ws?token=secret-access&portfolio=D1"),
        )
    }

    @Test
    fun `access refresh and api key params are masked case insensitively`() {
        assertEquals(
            "https://host/path?Access_Token=***&refresh_token=***&API-KEY=***&x=1",
            TokenRedactor.redact("https://host/path?Access_Token=a&refresh_token=b&API-KEY=c&x=1"),
        )
    }

    @Test
    fun `bearer token in text is masked`() {
        assertEquals(
            "Authorization: Bearer ***",
            TokenRedactor.redact("Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.payload.sig"),
        )
    }

    @Test
    fun `null and empty text are safe`() {
        assertNull(TokenRedactor.redact(null))
        assertEquals("", TokenRedactor.redact(""))
    }

    @Test
    fun `url without secrets is unchanged`() {
        val url = "https://iss.moex.com/iss/engines/futures/markets/forts/securities/CNYRUBF.json?iss.meta=off"
        assertEquals(url, TokenRedactor.redact(url))
    }

    @Test
    fun `describe keeps exception class and sanitized message`() {
        val error = IllegalStateException("GET https://oauth.alor.ru/refresh?token=abc123 failed")

        assertEquals(
            "IllegalStateException: GET https://oauth.alor.ru/refresh?token=*** failed",
            TokenRedactor.describe(error),
        )
    }
}
