package com.trading.bot.model.dto

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Строка обучающего датасета (roadmap v2.4, раздел 13.11).
 *
 * Одна строка = одна закрытая позиция: метка исхода + технические признаки
 * на момент входа + макро-контекст + решение LLM-стратега из `agent_logs`
 * (по [cycleId] позиции) + признак слепой зоны тикера на час входа.
 *
 * [MlDatasetRow.toCsvLine] — экспорт в CSV для обучения на CI.
 */
data class MlDatasetRow(
    val positionId: Long,
    val ticker: String,
    val direction: String,
    val openedAt: LocalDateTime,
    val closedAt: LocalDateTime?,
    val durationMinutes: Long,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal?,
    val pnlRub: BigDecimal,
    val pnlPercent: Double,
    val closeReason: String?,
    val win: Int,
    val hourOfDay: Int,
    val rsi14: Double,
    val atrPercent: Double,
    val macdHistogramPercent: Double,
    val bbPercentB: Double,
    val emaSlopePercent: Double,
    val volatility20Percent: Double,
    val return3: Double,
    val return10: Double,
    val return20: Double,
    val cbrRate: BigDecimal,
    val brentPrice: BigDecimal,
    val usdRub: BigDecimal,
    val macroSource: String,
    val strategyAction: String?,
    val strategyConfidence: Double?,
    val inBlindSpotHour: Int,
) {
    fun toCsvLine(): String {
        val parts =
            listOf(
                positionId.toString(),
                esc(ticker),
                direction,
                openedAt.toString(),
                closedAt?.toString() ?: "",
                durationMinutes.toString(),
                entryPrice.toPlainString(),
                exitPrice?.toPlainString() ?: "",
                pnlRub.toPlainString(),
                fmt(pnlPercent),
                esc(closeReason),
                win.toString(),
                hourOfDay.toString(),
                fmt(rsi14),
                fmt(atrPercent),
                fmt(macdHistogramPercent),
                fmt(bbPercentB),
                fmt(emaSlopePercent),
                fmt(volatility20Percent),
                fmt(return3),
                fmt(return10),
                fmt(return20),
                cbrRate.toPlainString(),
                brentPrice.toPlainString(),
                usdRub.toPlainString(),
                macroSource,
                esc(strategyAction),
                strategyConfidence?.let(::fmt) ?: "",
                inBlindSpotHour.toString(),
            )
        return parts.joinToString(",")
    }

    companion object {
        val CSV_HEADER =
            listOf(
                "position_id",
                "ticker",
                "direction",
                "opened_at",
                "closed_at",
                "duration_min",
                "entry_price",
                "exit_price",
                "pnl_rub",
                "pnl_percent",
                "close_reason",
                "win",
                "hour_of_day",
                "rsi14",
                "atr_percent",
                "macd_hist_percent",
                "bb_percent_b",
                "ema_slope_percent",
                "volatility20_percent",
                "ret_3",
                "ret_10",
                "ret_20",
                "cbr_rate",
                "brent",
                "usd_rub",
                "macro_source",
                "strategy_action",
                "strategy_confidence",
                "in_blind_spot_hour",
            ).joinToString(",")

        fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

        fun esc(value: String?): String {
            if (value.isNullOrEmpty()) return ""
            return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"${value.replace("\"", "\"\"")}\""
            } else {
                value
            }
        }
    }
}

/**
 * Результат экспорта датасета: строки + статистика отбракованных сделок.
 *
 * [mode] = "OK" | "DISABLED" (повторяет паттерн RAG-анализа: при `ml.enabled=false`
 * возвращаем пустой экспорт, а не ошибку, чтобы мониторинг видел состояние).
 */
data class MlDatasetExport(
    val mode: String,
    val positionsCount: Int,
    val rows: List<MlDatasetRow>,
    val skippedInsufficientData: Int,
    val generatedAt: LocalDateTime,
) {
    fun toCsv(): String {
        if (rows.isEmpty()) return MlDatasetRow.CSV_HEADER
        return buildString {
            appendLine(MlDatasetRow.CSV_HEADER)
            rows.forEach { appendLine(it.toCsvLine()) }
        }
    }
}

/** Продолжительность удержания позиции в минутах (закрытая = closedAt - openedAt). */
fun positionDurationMinutes(
    openedAt: LocalDateTime,
    closedAt: LocalDateTime?,
): Long = ChronoUnit.MINUTES.between(openedAt, closedAt ?: openedAt)
