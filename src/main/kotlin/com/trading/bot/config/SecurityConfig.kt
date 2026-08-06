package com.trading.bot.config

import com.trading.bot.config.security.JwtProperties
import com.trading.bot.config.security.ScrapeTokenFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import javax.crypto.SecretKey

/**
 * Spring Security with self-issued JWT (resource server).
 *
 * Два пользователя:
 *  - ADMIN (AUTH_USER / AUTH_PASSWORD, роль ADMIN) — полный доступ, включая изменение настроек
 *  - ANALYTICS (ANALYTICS_USER / ANALYTICS_PASSWORD, роль ANALYTICS) — только просмотр
 *
 * Креды обязательны: если AUTH_USER/AUTH_PASSWORD пустые — контекст не стартует
 * (никаких дефолтных паролей). ANALYTICS-пользователь создаётся только если
 * заданы оба значения.
 *
 * `actuator/health`, `error` и все endpoints `api/v1/auth` — публичные.
 * `actuator/prometheus` — `permitAll` в security-правилах, но фактически закрыт
 * отдельным токеном через [ScrapeTokenFilter] (см. METRICS_SCRAPE_TOKEN).
 * Все изменяющие POST-эндпоинты — только роль ADMIN (claim `roles` из JWT).
 * Все остальные запросы — denyAll (ничего не открываем по умолчанию).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    @param:Value("\${security.auth.user:}") private val authUser: String,
    @param:Value("\${security.auth.password:}") private val authPassword: String,
    @param:Value("\${security.analytics.user:}") private val analyticsUser: String,
    @param:Value("\${security.analytics.password:}") private val analyticsPassword: String,
    @param:Value("\${security.metrics.scrape-token:}") private val metricsScrapeToken: String,
) {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationConverter: JwtAuthenticationConverter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .addFilterBefore(ScrapeTokenFilter(metricsScrapeToken), BearerTokenAuthenticationFilter::class.java)
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/prometheus",
                        "/error",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                    ).permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/v1/**", "/actuator/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll()
            }.oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)
                }
            }
        return http.build()
    }

    @Bean
    fun authenticationManager(configuration: AuthenticationConfiguration): AuthenticationManager = configuration.authenticationManager

    @Bean
    fun userDetailsService(): UserDetailsService {
        require(authUser.isNotBlank() && authPassword.isNotBlank()) {
            "AUTH_USER / AUTH_PASSWORD must be set (security.auth.user/password) — refusing to start with empty credentials"
        }
        val admin =
            User
                .withUsername(authUser)
                .password(passwordEncoder().encode(authPassword))
                .roles("ADMIN")
                .build()
        val users = mutableListOf(admin)
        if (analyticsUser.isNotBlank() && analyticsPassword.isNotBlank()) {
            val analytics =
                User
                    .withUsername(analyticsUser)
                    .password(passwordEncoder().encode(analyticsPassword))
                    .roles("ANALYTICS")
                    .build()
            users.add(analytics)
        }
        return InMemoryUserDetailsManager(users)
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun jwtDecoder(jwtProperties: JwtProperties): JwtDecoder {
        val key: SecretKey = javax.crypto.spec.SecretKeySpec(jwtProperties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        return NimbusJwtDecoder.withSecretKey(key).build()
    }

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            val roles = jwt.getClaimAsStringList("roles") ?: emptyList()
            roles.map { SimpleGrantedAuthority("ROLE_$it") }
        }
        return converter
    }
}
