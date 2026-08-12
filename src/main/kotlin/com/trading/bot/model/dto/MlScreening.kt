package com.trading.bot.model.dto

import java.time.LocalDateTime

/**
 * Результат ML-скрининга кандидатов (roadmap v2.4, раздел 13.11.4).
 *
 * На каждый тикер модель прогоняется в обоих направлениях (LONG/SHORT — на момент
 * скрининга направление ещё не определено), для тикера остаётся лучшее.
 * [MlScreeningResult.candidates] отсортированы по [MlScreeningCandidate.probability]
 * убыванию и ограничены topN.
 */
data class MlScreeningCandidate(
    val ticker: String,
    val direction: String,
    val probability: Double,
    val inBlindSpotHour: Int,
    val hourOfDay: Int,
)

data class MlScreeningResult(
    val mode: String,
    val generatedAt: LocalDateTime,
    val topN: Int,
    val candidates: List<MlScreeningCandidate>,
    /** Тикеры, пропущенные из-за недостатка данных (< 30 свечей для индикаторов). */
    val skipped: List<String>,
)
