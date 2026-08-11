package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация распределённого лока (prefix = "distributed-lock").
 *
 * Лок работает поверх Redis (`SET key NX EX` + owner-токен) и позволяет запускать
 * несколько реплик бота без гонок: входы в позиции и критические планировщики
 * выполняются только на одной реплике (лидере).
 *
 * @property enabled включает лок. При `false` (одиночная инсталляция, Redis не
 *   обязателен) все вызовы выполняются без обращения к Redis.
 * @property schedulerTtlSeconds TTL лока для фоновых планировщиков (срок жизни
 *   «критической секции»). Должен быть больше максимальной длительности прогона.
 * @property positionOpenTtlSeconds TTL лока на один вход в позицию (тикер).
 *   Должен быть больше максимального времени открытия позиции.
 */
@Component
@ConfigurationProperties(prefix = "distributed-lock")
class DistributedLockConfig {
    var enabled: Boolean = false
    var schedulerTtlSeconds: Long = 60
    var positionOpenTtlSeconds: Long = 30
}
