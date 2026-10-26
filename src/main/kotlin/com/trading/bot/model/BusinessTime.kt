package com.trading.bot.model

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Бизнес-время (МСК) для wall-clock значений по умолчанию в сущностях.
 *
 * `LocalDateTime.now()` берёт зону JVM: в Docker/CI это UTC, поэтому `created_at`
 * записывался на 3 часа назад против московских `closed_at`/`opened_at`. Из-за этого
 * метрики «за сегодня» (dashboard) и point-in-time выборки макро-снапшотов уезжали на
 * 3 часа в окне 00:00–03:00 МСК (скан-6 P3).
 *
 * Осознанно простой объект: сущности не должны тащить зависимость на `Clock`, а сервисы
 * с тестируемым временем продолжают использовать инъекцию `Clock`.
 */
object BusinessTime {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    fun now(): LocalDateTime = LocalDateTime.now(zone)

    fun zone(): ZoneId = zone
}
