package com.trading.bot.infrastructure.llm

import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.TechnicalReport
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Хранилище последних отчётов агентов для дельта-промптов (roadmap 13.8).
 *
 * In-memory per-ticker: для каждого тикера держит последний [TechnicalReport] и
 * [FundamentalReport]. При вызове [techDelta]/[fundDelta] сравнивает текущий отчёт
 * с предыдущим через [AgentReportDelta]; предыдущий отчёт обновляется в [update]
 * уже ПОСЛЕ того, как дельта была передана агенту (внутри одного цикла стратег и
 * контрариан получают одинаковую дельту).
 *
 * Конкурентность: ConcurrentHashMap — цикл обрабатывает тикеры параллельно.
 * При перезапуске состояние пустое → первая оценка по каждому тикеру идёт с полным
 * текстом (`null`-дельта), что эквивалентно поведению до включения фичи.
 */
@Component
class DeltaPromptStore {
    private data class Snapshot(
        val tech: TechnicalReport,
        val fund: FundamentalReport,
    )

    private val lastByTicker = ConcurrentHashMap<String, Snapshot>()

    fun techDelta(
        ticker: String,
        current: TechnicalReport,
    ): String? = lastByTicker[ticker]?.let { AgentReportDelta.technical(it.tech, current) }

    fun fundDelta(
        ticker: String,
        current: FundamentalReport,
    ): String? = lastByTicker[ticker]?.let { AgentReportDelta.fundamental(it.fund, current) }

    fun update(
        ticker: String,
        tech: TechnicalReport,
        fund: FundamentalReport,
    ) {
        lastByTicker[ticker] = Snapshot(tech, fund)
    }

    fun clear() {
        lastByTicker.clear()
    }
}
