package com.trading.bot.config

import com.trading.bot.domain.risk.FuturesStopResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Регистрация чисто-доменных риск-бинов (без Spring-аннотаций в domain-слое):
 * [FuturesStopResolver] инжектится в FuturesEntryProfile.
 */
@Configuration
class RiskBeansConfig {
    @Bean
    fun futuresStopResolver(): FuturesStopResolver = FuturesStopResolver()
}
