package com.trading.bot.config.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

/**
 * Выпуск и валидация короткоживущих access-токенов (JWT HS256).
 *
 * Токен не несёт refresh-логики: сессии управляются отдельными непрозрачными
 * refresh-токенами в БД ([com.trading.bot.service.RefreshTokenService]), поэтому
 * access-токен можно ревокировать только по короткому TTL.
 */
@Service
class JwtService(
    private val properties: JwtProperties,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(properties.secret.toByteArray(Charsets.UTF_8))

    fun issueAccessToken(
        username: String,
        roles: List<String>,
    ): AccessToken {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(properties.accessTtlMinutes * 60)
        val value =
            Jwts
                .builder()
                .subject(username)
                .issuer(properties.issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim("roles", roles)
                .signWith(key, Jwts.SIG.HS256)
                .compact()
        return AccessToken(value, expiresAt)
    }

    data class AccessToken(
        val value: String,
        val expiresAt: Instant,
    )
}
