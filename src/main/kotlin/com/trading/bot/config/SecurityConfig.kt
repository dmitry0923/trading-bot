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
 * /actuator/health stays public (needed for Docker healthcheck).
 * All /api/v1/ paths and the rest of /actuator/ require Basic Auth.
 * Credentials come from env: AUTH_USER / AUTH_PASSWORD.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${security.auth.user:admin}") private val authUser: String,
    @Value("\${security.auth.password:}") private val authPassword: String,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/api/v1/**", "/actuator/**").authenticated()
                    .anyRequest().permitAll()
            }
            .httpBasic(Customizer.withDefaults())
        return http.build()
    }

    @Bean
    fun userDetailsService(): UserDetailsService {
        val effectivePassword = authPassword.ifBlank { "change-me-now" }
        val user =
            User.withUsername(authUser)
                .password(passwordEncoder().encode(effectivePassword))
                .roles("USER")
                .build()
        return InMemoryUserDetailsManager(user)
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
