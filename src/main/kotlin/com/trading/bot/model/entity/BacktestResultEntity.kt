package com.trading.bot.model.entity

import java.time.LocalDateTime

/**
 * Сохранённый результат прогона бэктеста (roadmap v2.2, раздел 13.7.3).
 *
 * Хранит параметры прогона и метрики результата как JSON (jsonb), а также
 * опциональную walk-forward OOS-сводку (эндпоинт `/api/v1/backtest/{ticker}/validate`).
 * Используется для сравнения итераций стратегии во времени.
 */
data class BacktestResultEntity(
    val id: Long? = null,
    val ticker: String,
    val params: String,
    val metrics: String,
    val oos: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
