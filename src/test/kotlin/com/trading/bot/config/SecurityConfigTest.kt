package com.trading.bot.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.provisioning.InMemoryUserDetailsManager

/**
 * [SecurityConfig] — fail-closed админские креды, опциональный ANALYTICS-пользователь
 * и конвертер ролей из JWT (включая отсутствующий claim).
 */
class SecurityConfigTest {
    @Test
    fun `blank admin credentials reject startup`() {
        val empty = SecurityConfig("", "", "", "", "")
        assertThrows(IllegalArgumentException::class.java) { empty.userDetailsService() }

        val noPassword = SecurityConfig("admin", "", "", "", "")
        assertThrows(IllegalArgumentException::class.java) { noPassword.userDetailsService() }
    }

    @Test
    fun `admin only and admin plus analytics are registered`() {
        val adminOnly = SecurityConfig("admin", "secret", "", "", "").userDetailsService()
        val adminManager = adminOnly as InMemoryUserDetailsManager
        assertTrue(adminManager.userExists("admin"))
        assertFalse(adminManager.userExists("analyst"))

        val withAnalytics: UserDetailsService = SecurityConfig("admin", "secret", "analyst", "secret2", "").userDetailsService()
        val analyticsManager = withAnalytics as InMemoryUserDetailsManager
        assertTrue(analyticsManager.userExists("analyst"))
    }

    @Test
    fun `jwt converter maps absent roles to empty and present roles to authorities`() {
        val converter = SecurityConfig("admin", "secret", "", "", "").jwtAuthenticationConverter()
        val jwt = mock<Jwt>()
        whenever(jwt.tokenValue).thenReturn("token")
        whenever(jwt.headers).thenReturn(mapOf("alg" to "HS256"))
        whenever(jwt.claims).thenReturn(mapOf("sub" to "admin"))
        whenever(jwt.getClaimAsStringList("roles")).thenReturn(null)

        assertTrue(converter.convert(jwt)!!.authorities.none { it.authority?.startsWith("ROLE_") == true })

        whenever(jwt.getClaimAsStringList("roles")).thenReturn(listOf("ADMIN", "ANALYTICS"))
        val authorities = converter.convert(jwt)!!.authorities.mapNotNull { it.authority }
        assertEquals(setOf("ROLE_ADMIN", "ROLE_ANALYTICS"), authorities.filter { it.startsWith("ROLE_") }.toSet())
    }
}
