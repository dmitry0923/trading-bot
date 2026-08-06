package com.trading.bot.controller

import com.trading.bot.config.security.JwtProperties
import com.trading.bot.config.security.JwtService
import com.trading.bot.config.security.LoginAttemptTracker
import com.trading.bot.service.RefreshTokenService
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

/**
 * JWT-аутентификация: self-issued short-lived access-токены + ротируемые
 * refresh-токены (httpOnly cookie).
 *
 * - `POST /api/v1/auth/login` — проверка креды → access + refresh
 * - `POST /api/v1/auth/refresh` — ротация refresh (reuse = отзыв сессии)
 * - `POST /api/v1/auth/logout` — отзыв текущего refresh
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService,
    private val loginAttemptTracker: LoginAttemptTracker,
    private val jwtProperties: JwtProperties,
) {
    @PostMapping("/login")
    suspend fun login(
        @RequestBody request: LoginRequest,
        response: HttpServletResponse,
    ): Map<String, Any> {
        val key = request.username
        loginAttemptTracker.check(key)
        val authentication =
            try {
                authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken(request.username, request.password),
                )
            } catch (_: BadCredentialsException) {
                loginAttemptTracker.recordFailure(key)
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
            }
        loginAttemptTracker.reset(key)
        val principal =
            authentication.principal as? UserDetails
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        val roles = principal.authorities.map { it.authority.orEmpty().removePrefix("ROLE_") }
        val access = jwtService.issueAccessToken(authentication.name, roles)
        val refresh = refreshTokenService.issue(authentication.name, roles)
        setRefreshCookie(response, refresh)
        return tokenResponse(access, refresh, authentication.name, roles)
    }

    @PostMapping("/refresh")
    suspend fun refresh(
        @RequestBody(required = false) body: RefreshRequest?,
        @CookieValue(required = false) refreshToken: String?,
        response: HttpServletResponse,
    ): Map<String, Any> {
        val raw =
            body?.refreshToken ?: refreshToken
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken is required")
        val rotated =
            refreshTokenService.rotate(raw)
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token invalid, expired or revoked")
        val access = jwtService.issueAccessToken(rotated.username, rotated.roles)
        setRefreshCookie(response, rotated.value)
        return tokenResponse(access, rotated.value, rotated.username, rotated.roles)
    }

    @PostMapping("/logout")
    suspend fun logout(
        @RequestBody(required = false) body: RefreshRequest?,
        @CookieValue(required = false) refreshToken: String?,
        response: HttpServletResponse,
    ): Map<String, Any> {
        val raw = body?.refreshToken ?: refreshToken
        if (raw != null) refreshTokenService.revoke(raw)
        clearRefreshCookie(response)
        return mapOf("loggedOut" to true)
    }

    private fun tokenResponse(
        access: JwtService.AccessToken,
        refresh: String,
        username: String,
        roles: List<String>,
    ): Map<String, Any> =
        mapOf(
            "accessToken" to access.value,
            "tokenType" to "Bearer",
            "expiresIn" to Duration.between(java.time.Instant.now(), access.expiresAt).seconds,
            "refreshToken" to refresh,
            "username" to username,
            "roles" to roles.map { "ROLE_$it" },
        )

    private fun setRefreshCookie(
        response: HttpServletResponse,
        raw: String,
    ) {
        val cookie = Cookie(REFRESH_COOKIE, raw)
        cookie.isHttpOnly = true
        cookie.secure = jwtProperties.cookieSecure
        cookie.path = COOKIE_PATH
        cookie.maxAge = (jwtProperties.refreshTtlDays * 86_400).toInt()
        cookie.setAttribute("SameSite", "Strict")
        response.addCookie(cookie)
    }

    private fun clearRefreshCookie(response: HttpServletResponse) {
        val cookie = Cookie(REFRESH_COOKIE, "")
        cookie.isHttpOnly = true
        cookie.secure = jwtProperties.cookieSecure
        cookie.path = COOKIE_PATH
        cookie.maxAge = 0
        cookie.setAttribute("SameSite", "Strict")
        response.addCookie(cookie)
    }

    private companion object {
        const val REFRESH_COOKIE = "refresh_token"
        const val COOKIE_PATH = "/api/v1/auth"
    }
}

data class LoginRequest(
    val username: String,
    val password: String,
)

data class RefreshRequest(
    val refreshToken: String?,
)
