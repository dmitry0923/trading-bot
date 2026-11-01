package com.trading.bot.controller

import com.trading.bot.config.security.JwtProperties
import com.trading.bot.config.security.JwtService
import com.trading.bot.config.security.LoginAttemptTracker
import com.trading.bot.service.RefreshTokenService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * [AuthController] — guard-ветви: principal не `UserDetails`, refresh без body/cookie,
 * logout без токена и logout с cookie-токеном.
 */
class AuthControllerBranchesTest {
    private val authenticationManager = mock<org.springframework.security.authentication.AuthenticationManager>()
    private val jwtService = mock<JwtService>()
    private val refreshTokenService = mock<RefreshTokenService>()
    private val tracker = mock<LoginAttemptTracker>()
    private val controller =
        AuthController(
            authenticationManager,
            jwtService,
            refreshTokenService,
            tracker,
            JwtProperties(),
        )
    private val response = mock<HttpServletResponse>()
    private val request =
        mock<HttpServletRequest>().also { servlet ->
            whenever(servlet.remoteAddr).thenReturn("127.0.0.1")
        }

    @Test
    fun `login with a non user details principal is rejected as unauthorized`(): Unit =
        runBlocking {
            val authentication =
                Mockito.mock(Authentication::class.java).also { auth ->
                    whenever(auth.principal).thenReturn("plain-principal")
                    whenever(auth.name).thenReturn("alice")
                }
            whenever(
                authenticationManager.authenticate(Mockito.any(UsernamePasswordAuthenticationToken::class.java)),
            ).thenReturn(authentication)

            val ex =
                assertThrows<ResponseStatusException> {
                    runBlocking { controller.login(LoginRequest("alice", "secret"), response, request) }
                }

            assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
        }

    @Test
    fun `refresh without a body and cookie is a bad request`(): Unit =
        runBlocking {
            val ex =
                assertThrows<ResponseStatusException> {
                    runBlocking { controller.refresh(null, null, response) }
                }

            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        }

    @Test
    fun `refresh falls back to the cookie token when the body is empty`(): Unit =
        runBlocking {
            whenever(refreshTokenService.rotate("cookie-token"))
                .thenReturn(RefreshTokenService.RotatedRefresh("new-token", "alice", listOf("USER")))
            whenever(jwtService.issueAccessToken("alice", listOf("USER")))
                .thenReturn(JwtService.AccessToken("access", Instant.now().plusSeconds(60)))

            val payload = controller.refresh(RefreshRequest(null), "cookie-token", response)

            assertEquals("access", payload["accessToken"])
            assertEquals("alice", payload["username"])
        }

    @Test
    fun `logout without any token only clears the cookie`(): Unit =
        runBlocking {
            val payload = controller.logout(null, null, response)

            assertEquals(true, payload["loggedOut"])
            verify(refreshTokenService, never()).revoke(anyStringArg())
        }

    @Test
    fun `logout revokes the cookie token`(): Unit =
        runBlocking {
            val payload = controller.logout(null, "cookie-token", response)

            assertEquals(true, payload["loggedOut"])
            verify(refreshTokenService).revoke("cookie-token")
            assertTrue(payload.isNotEmpty())
        }

    private fun anyStringArg(): String {
        Mockito.anyString()
        return ""
    }
}
