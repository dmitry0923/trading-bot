package com.trading.bot.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SecurityConfigValidatorTest {
    @Test
    fun `empty secret rejects startup`() {
        val validator = SecurityConfigValidator(jwtSecret = "")
        assertThrows(IllegalArgumentException::class.java) { validator.validate() }
    }

    @Test
    fun `short secret rejects startup`() {
        val validator = SecurityConfigValidator(jwtSecret = "abc123")
        assertThrows(IllegalArgumentException::class.java) { validator.validate() }
    }

    @Test
    fun `valid 32-byte secret passes`() {
        val validator = SecurityConfigValidator(jwtSecret = "a".repeat(32))
        assertDoesNotThrow { validator.validate() }
    }

    @Test
    fun `longer secret passes`() {
        val validator = SecurityConfigValidator(jwtSecret = "super-secret-key-for-hmac-signing-0123456789")
        assertDoesNotThrow { validator.validate() }
    }

    @Test
    fun `31-byte secret rejects`() {
        val validator = SecurityConfigValidator(jwtSecret = "a".repeat(31))
        assertThrows(IllegalArgumentException::class.java) { validator.validate() }
    }
}
