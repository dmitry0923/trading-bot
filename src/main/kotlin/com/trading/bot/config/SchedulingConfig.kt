package com.trading.bot.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Включает планировщик @Scheduled (стратегический цикл, поллинг котировок,
 * outbox-воркер, авто-закрытие по времени) только при app.scheduling.enabled=true.
 *
 * В интеграционных тестах фоновые задачи отключены через src/test/resources/application.yml,
 * чтобы тесты были детерминированными и не ходили во внешние сервисы.
 */
@Configuration
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
@EnableScheduling
class SchedulingConfig
