package com.trading.bot.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security with Basic Auth for all sensitive endpoints.
 *
 * /actuator/health stays public (needed for Docker healthcheck).
 * All /api/v1/ paths and the rest of /actuator/ require Basic Auth.
 * Credentials come from env: AUTH_USER / AUTH_PASSWORD.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${security.auth.user:admin}") private val authUser: String,
    @Value("\${security.auth.password:}") private val authPassword: String,
    @Value("\${trading.mode:SIMULATION}") private val tradingMode: String,
) {
    init {
        require(authUser.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            "AUTH_USER contains unsupported characters"
        }
        if (tradingMode.equals("LIVE", ignoreCase = true)) {
            require(authPassword.length >= 12 && authPassword != "change-me-now") {
                "LIVE mode requires a non-default AUTH_PASSWORD with at least 12 characters"
            }
        }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers("/api/v1/**", "/actuator/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll()
            }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic(Customizer.withDefaults())
        return http.build()
    }

    @Bean
    fun userDetailsService(): UserDetailsService {
        val effectivePassword = authPassword.ifBlank { "change-me-now" }
        val user =
            User
                .withUsername(authUser)
                .password(passwordEncoder().encode(effectivePassword))
                .roles("USER")
                .build()
        return InMemoryUserDetailsManager(user)
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
