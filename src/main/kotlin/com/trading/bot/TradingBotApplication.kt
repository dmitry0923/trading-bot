package com.trading.bot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * Точка входа Spring Boot приложения торгового бота (MMVB Trading Bot v2).
 *
 * - Планировщик @Scheduled включается через [com.trading.bot.config.SchedulingConfig]
 * - `@EnableConfigurationProperties` — все `@ConfigurationProperties`-бинды
 *
 * Режим торговли задаётся через `trading.mode` (SIMULATION | LIVE).
 */
@SpringBootApplication
@EnableConfigurationProperties
class TradingBotApplication

fun main(args: Array<String>) {
    runApplication<TradingBotApplication>(*args)
}
