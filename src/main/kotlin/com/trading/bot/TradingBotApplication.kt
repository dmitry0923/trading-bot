package com.trading.bot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Точка входа Spring Boot приложения торгового бота (MMVB Trading Bot v2).
 *
 * - `@EnableScheduling` — стратегический цикл и fallback-поллинг котировок
 * - `@EnableConfigurationProperties` — все `@ConfigurationProperties`-бинды
 *
 * Режим торговли задаётся через `trading.mode` (SIMULATION | LIVE).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties
class TradingBotApplication

fun main(args: Array<String>) {
    runApplication<TradingBotApplication>(*args)
}
