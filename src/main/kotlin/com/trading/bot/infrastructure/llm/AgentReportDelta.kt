package com.trading.bot.infrastructure.llm

import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.TechnicalReport
import java.util.Locale

/**
 * Дельта-компрессор отчётов агентов (roadmap 13.8, «дельта-промпты»).
 *
 * Сокращает число входных токенов у стратега/контрариана: вместо полного текста
 * `reasoning` каждого анализа в промпт уходит только то, что ИЗМЕНИЛОСЬ с прошлой
 * оценки того же тикера. Числовые поля (conclusion/confidence/trend/rsi/atr/macd)
 * приводятся всегда — они короткие и несут основной сигнал.
 *
 * Семантика возвращаемого значения:
 *  - `null` — предыдущего отчёта нет (первая оценка) → агент использует полный текст;
 *  - строка `"NO_CHANGE"` — значения идентичны → контекст «рынок не изменился»;
 *  - строка с перечислением изменённых полей — компактная замена полного `reasoning`.
 */
object AgentReportDelta {
    private const val NO_CHANGE = "NO_CHANGE"
    private const val REASONING_MAX_CHARS = 120
    private const val EPS = 1e-6

    fun technical(
        previous: TechnicalReport?,
        current: TechnicalReport,
    ): String? =
        delta(previous, current) {
            val parts =
                listOfNotNull(
                    changed("conclusion", it.conclusion, current.conclusion),
                    changed("confidence", fmt(it.confidence), fmt(current.confidence)),
                    changed("trend", it.trend, current.trend),
                    changed("rsi", fmt(it.rsi), fmt(current.rsi)),
                    changed("atr", fmt(it.atr), fmt(current.atr)),
                    changed("macd", fmt(it.macd), fmt(current.macd)),
                    changed("reasoning", truncate(it.reasoning), truncate(current.reasoning)),
                )
            join(parts)
        }

    fun fundamental(
        previous: FundamentalReport?,
        current: FundamentalReport,
    ): String? =
        delta(previous, current) {
            val parts =
                listOfNotNull(
                    changed("conclusion", it.conclusion, current.conclusion),
                    changed("confidence", fmt(it.confidence), fmt(current.confidence)),
                    changed("reasoning", truncate(it.reasoning), truncate(current.reasoning)),
                )
            join(parts)
        }

    private inline fun <T> delta(
        previous: T?,
        current: T,
        build: (T) -> String,
    ): String? {
        if (previous == null) return null
        return build(previous)
    }

    private fun join(parts: List<String>): String =
        if (parts.isEmpty()) {
            NO_CHANGE
        } else {
            parts.joinToString("; ")
        }

    private fun changed(
        field: String,
        oldValue: Any,
        newValue: Any,
    ): String? =
        if (oldValue == newValue) {
            null
        } else {
            "$field: $oldValue→$newValue"
        }

    private fun fmt(v: Double): String =
        if (kotlin.math.abs(v - kotlin.math.round(v * 100) / 100.0) < EPS) {
            String.format(Locale.ROOT, "%.2f", v)
        } else {
            String.format(Locale.ROOT, "%.1f", v)
        }

    private fun truncate(text: String): String =
        if (text.length <= REASONING_MAX_CHARS) {
            text
        } else {
            text.take(REASONING_MAX_CHARS) + "..."
        }
}
