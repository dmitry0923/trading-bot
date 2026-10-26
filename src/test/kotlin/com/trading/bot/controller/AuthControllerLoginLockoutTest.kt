package com.trading.bot.controller

import com.trading.bot.config.security.JwtProperties
import com.trading.bot.config.security.JwtService
import com.trading.bot.config.security.LoginAttemptTracker
import com.trading.bot.service.RefreshTokenService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Регрессия безопасности: блокировка после неудачных логинов считается по паре
 * `username|IP`, поэтому атакующий, набравший 10 неверных паролей за оператора,
 * НЕ запирает его (раньше ключом был только username → 429 у оператора, включая
 * force-close/emergency-stop).
 */
class AuthControllerLoginLockoutTest {
    private val authenticationManager = mock<AuthenticationManager>()

    private val refreshTokenService = mock<RefreshTokenService>()

    private val jwtProperties =
        JwtProperties().apply {
            secret = "unit-test-secret-key-at-least-32-bytes!!"
        }

    private val tracker = LoginAttemptTracker(Clock.fixed(Instant.parse("2026-10-26T09:00:00Z"), ZoneOffset.UTC))

    private val controller =
        AuthController(
            authenticationManager,
            JwtService(jwtProperties),
            refreshTokenService,
            tracker,
            jwtProperties,
        )

    private val response = mock<HttpServletResponse>()

    @Test
    fun `ten failures for operator from attacker ip do not block operator from own ip`() {
        runBlocking {
            whenever(authenticationManager.authenticate(any())).doThrow(BadCredentialsException("bad credentials"))

            val attacker = request("203.0.113.7")
            repeat(10) {
                val failure =
                    assertThrows(ResponseStatusException::class.java) {
                        runBlocking { controller.login(LoginRequest("operator", "guess"), response, attacker) }
                    }
                assertEquals(HttpStatus.UNAUTHORIZED, failure.statusCode)
            }
            // Атакующий исчерпал лимит со своего IP...
            val blocked =
                assertThrows(ResponseStatusException::class.java) {
                    runBlocking { controller.login(LoginRequest("operator", "guess"), response, attacker) }
                }
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.statusCode)

            // ...но оператор со своего IP входит нормально.
            whenever(authenticationManager.authenticate(any())).doReturn(authenticated())
            whenever(refreshTokenService.issue(any(), any())).thenReturn("refresh-token")

            val tokens = controller.login(LoginRequest("operator", "correct"), response, request("10.0.0.5"))

            assertEquals("operator", tokens["username"])
            assertTrue((tokens["accessToken"] as String).isNotBlank())
        }
    }

    @Test
    fun `successful login resets only its own username ip key`() {
        runBlocking {
            whenever(authenticationManager.authenticate(any())).doThrow(BadCredentialsException("bad credentials"))
            val ownIp = request("10.0.0.5")
            repeat(9) {
                assertThrows(ResponseStatusException::class.java) {
                    runBlocking { controller.login(LoginRequest("operator", "guess"), response, ownIp) }
                }
            }

            whenever(authenticationManager.authenticate(any())).doReturn(authenticated())
            whenever(refreshTokenService.issue(any(), any())).thenReturn("refresh-token")
            controller.login(LoginRequest("operator", "correct"), response, ownIp)

            assertEquals(0, tracker.trackedKeys(), "успешный вход очищает счётчик пары username|ip")
        }
    }

    private fun authenticated(): UsernamePasswordAuthenticationToken {
        val authority = SimpleGrantedAuthority("ROLE_ADMIN")
        return UsernamePasswordAuthenticationToken(
            User("operator", "pw", listOf(authority)),
            null,
            listOf(authority),
        )
    }

    private fun request(clientIp: String): HttpServletRequest = mock { on { remoteAddr } doReturn clientIp }
}
