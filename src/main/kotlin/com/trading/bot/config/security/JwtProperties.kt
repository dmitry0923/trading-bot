package com.trading.bot.config.security

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Настройки self-issued JWT.
 *
 * @property secret подписывающий секрет HS256 (min 32 байта), обязателен — иначе контекст не стартует
 * @property accessTtlMinutes срок жизни access-токена
 * @property refreshTtlDays срок жизни refresh-токена
 * @property issuer заявленный issuer (проверяется при валидации)
 * @property cookieSecure Secure-флаг для httpOnly cookie refresh-токена (включать за TLS)
 */
@Component
@ConfigurationProperties(prefix = "security.jwt")
class JwtProperties : InitializingBean {
    var secret: String = ""
    var accessTtlMinutes: Long = 15
    var refreshTtlDays: Long = 30
    var issuer: String = "trading-bot"
    var cookieSecure: Boolean = false

    override fun afterPropertiesSet() {
        require(secret.isNotBlank()) {
            "security.jwt.secret (JWT_SECRET) is not set — refusing to start with an insecure signing key"
        }
        require(secret.toByteArray(Charsets.UTF_8).size >= 32) {
            "security.jwt.secret (JWT_SECRET) must be at least 32 bytes for HS256"
        }
    }
}
