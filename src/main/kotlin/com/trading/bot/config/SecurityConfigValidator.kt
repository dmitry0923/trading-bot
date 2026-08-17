package com.trading.bot.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

/**
 * Второй слой валидации JWT-секрета (после [com.trading.bot.config.security.JwtProperties]).
 *
 * JwtProperties.afterPropertiesSet() проверяет секрет на уровне Spring-компонента.
 * Этот валидатор — defense-in-depth: срабатывает на этапе @PostConstruct и
 * гарантирует, что ни один @Configuration-бин не создаст JWT-подпись пустой строкой.
 */
@Configuration
class SecurityConfigValidator(
    @Value("\${security.jwt.secret:}") private val jwtSecret: String,
) {
    @PostConstruct
    fun validate() {
        require(jwtSecret.length >= 32) {
            "JWT_SECRET must be >= 32 bytes. Current length: ${jwtSecret.length}"
        }
    }
}
