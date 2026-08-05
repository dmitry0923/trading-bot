package com.trading.bot.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security with Basic Auth for all sensitive endpoints.
 *
 * Два отдельных пользователя:
 *  - ADMIN (AUTH_USER / AUTH_PASSWORD, роль ADMIN) — полный доступ, включая изменение настроек
 *  - ANALYTICS (ANALYTICS_USER / ANALYTICS_PASSWORD, роль ANALYTICS) — только просмотр аналитики
 *
 * /actuator/health остаётся публичным (нужен Docker healthcheck).
 * Все endpoints /api/v1/ и /actuator/ требуют Basic Auth.
 * Изменение настроек (POST /api/v1/settings) доступно только ADMIN.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${security.auth.user:admin}") private val authUser: String,
    @Value("\${security.auth.password:}") private val authPassword: String,
    @Value("\${security.analytics.user:analytics}") private val analyticsUser: String,
    @Value("\${security.analytics.password:}") private val analyticsPassword: String,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/settings")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/v1/**", "/actuator/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll()
            }.httpBasic(Customizer.withDefaults())
        return http.build()
    }

    @Bean
    fun userDetailsService(): UserDetailsService {
        val effectiveAdminPassword = authPassword.ifBlank { "change-me-now" }
        val effectiveAnalyticsPassword = analyticsPassword.ifBlank { "analytics-view-only" }
        val admin =
            User
                .withUsername(authUser)
                .password(passwordEncoder().encode(effectiveAdminPassword))
                .roles("ADMIN")
                .build()
        val analytics =
            User
                .withUsername(analyticsUser)
                .password(passwordEncoder().encode(effectiveAnalyticsPassword))
                .roles("ANALYTICS")
                .build()
        return InMemoryUserDetailsManager(admin, analytics)
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
