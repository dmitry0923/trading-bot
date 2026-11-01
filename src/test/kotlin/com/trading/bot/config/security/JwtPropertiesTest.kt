package com.trading.bot.config.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [JwtProperties] — fail-closed проверка секрета при старте: пустой и короткий
 * секрет запрещены, корректный проходит.
 */
class JwtPropertiesTest {
    @Test
    fun `blank secret is rejected`() {
        val properties = JwtProperties()

        val failure = assertThrows(IllegalArgumentException::class.java) { properties.afterPropertiesSet() }

        assertEquals(true, failure.message!!.contains("not set"))
    }

    @Test
    fun `short secret is rejected`() {
        val properties = JwtProperties().apply { secret = "too-short" }

        val failure = assertThrows(IllegalArgumentException::class.java) { properties.afterPropertiesSet() }

        assertEquals(true, failure.message!!.contains("32 bytes"))
    }

    @Test
    fun `valid secret passes`() {
        val properties = JwtProperties().apply { secret = "x".repeat(32) }

        properties.afterPropertiesSet()

        assertEquals(32, properties.secret.length)
    }
}
