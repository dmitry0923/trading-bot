package com.trading.bot.model.dto

import java.time.LocalDateTime

/**
 * Результат ML-прогноза удержания тренда (roadmap v2.4, раздел 13.11.7).
 *
 * Для каждого тикера модель прогоняется в обоих направлениях, для тикера остаётся
 * направление с лучшей [MlTrendCandidate.trendScore] (оценка удержания тренда —
 * модель + детерминированная сила тренда по индикаторам, [MlTrendScore]).
 * Кандидаты отсортированы по trendScore убыванию и ограничены topN.
 */
data class MlTrendCandidate(
    val ticker: String,
    val direction: String,
    /** Прогноз модели (P win для направления), 0..1. */
    val probability: Double,
    /** Оценка удержания тренда (0..1), по ней ранжируется топ. */
    val trendScore: Double,
    val inBlindSpotHour: Int,
    val hourOfDay: Int,
)

data class MlTrendResult(
    val mode: String,
    val generatedAt: LocalDateTime,
    /** Горизонт прогноза в барах (интерпретация оценок, конфиг ml.trend.horizon-bars). */
    val horizonBars: Int,
    val topN: Int,
    val candidates: List<MlTrendCandidate>,
    /** Тикеры, пропущенные из-за недостатка данных (< 30 свечей для индикаторов). */
    val skipped: List<String>,
)
