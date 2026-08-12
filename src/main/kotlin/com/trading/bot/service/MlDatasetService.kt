package com.trading.bot.service

import com.trading.bot.config.MlConfig
import com.trading.bot.domain.ml.MlFeatureExtractor
import com.trading.bot.model.dto.MlDatasetExport
import com.trading.bot.model.dto.MlDatasetRow
import com.trading.bot.model.dto.positionDurationMinutes
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * Экспорт обучающего датасета для ML-агентов (roadmap v2.4, раздел 13.11, шаг 1
 * «Сбор датасета»).
 *
 * Каждая строка = закрытая позиция из `positions` + признаки на момент входа из
 * `candles` ([MlFeatureExtractor]) + решение LLM-стратега из `agent_logs`
 * (по `cycleId`) + макро-контекст + признак слепой зоны тикера на час входа.
 *
 * Выключен ([MlConfig.enabled]=false) — экспорт возвращается в режиме DISABLED,
 * БД не читается (паттерн RAG: раздел 13.18).
 */
@Service
class MlDatasetService(
    private val mlConfig: MlConfig,
    private val positionRepository: PositionRepository,
    private val candleRepository: CandleRepository,
    private val agentLogRepository: AgentLogRepository,
    private val blindSpotRepository: BlindSpotRepository,
    private val macroContextService: MacroContextService,
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Формирует датасет по закрытым позициям (самые свежие первые), до [maxRows] строк.
     *
     * @param since фильтр по [Position.closedAt]
     * @param ticker фильтр по тикеру
     * @param maxRows лимит строк экспорта (переопределяет [MlConfig.dataset].maxRows)
     */
    suspend fun export(
        since: LocalDateTime? = null,
        ticker: String? = null,
        maxRows: Int? = null,
    ): MlDatasetExport {
        val generatedAt = LocalDateTime.now()
        if (!mlConfig.enabled) {
            meterRegistry.counter("ml.dataset.export", Tags.of("mode", "DISABLED")).increment()
            return MlDatasetExport(
                mode = "DISABLED",
                positionsCount = 0,
                rows = emptyList(),
                skippedInsufficientData = 0,
                generatedAt = generatedAt,
            )
        }
        meterRegistry.counter("ml.dataset.export", Tags.of("mode", "OK")).increment()

        val limit = (maxRows ?: mlConfig.dataset.maxRows).coerceIn(1, MAX_ROWS_HARD_LIMIT)
        val closed = positionRepository.findClosed(ticker, since)
        val macro = macroContextService.fetch()
        val blindSpotsByTicker = blindSpotRepository.findByIsActiveTrue().groupBy { it.ticker }
        val timeframe = mlConfig.dataset.timeframe
        val lookbackBars = mlConfig.dataset.lookbackBars
        val barMinutes = barMinutes(timeframe)

        val rows = mutableListOf<MlDatasetRow>()
        var skipped = 0
        for (position in closed) {
            if (rows.size >= limit) break
            val features = entryFeatures(position, timeframe, lookbackBars, barMinutes)
            if (features == null) {
                skipped++
                continue
            }
            val strategyLog = position.cycleId?.let { agentLogRepository.findStrategyDecision(it) }
            rows +=
                buildRow(
                    position = position,
                    features = features,
                    macro = macro,
                    strategyLog = strategyLog,
                    inBlindSpot = inBlindSpotHour(blindSpotsByTicker[position.ticker], position),
                )
        }

        meterRegistry.gauge("ml.dataset.export.rows", rows.size)
        meterRegistry.gauge("ml.dataset.export.skipped", skipped)
        meterRegistry.gauge("ml.dataset.export.positions", closed.size)
        return MlDatasetExport(
            mode = "OK",
            positionsCount = closed.size,
            rows = rows,
            skippedInsufficientData = skipped,
            generatedAt = generatedAt,
        )
    }

    /**
     * Статистика датасета для контроля качества данных (без feature-инжиниринга):
     * количество закрытых позиций, win rate, разбивка по тикерам и направлению.
     */
    suspend fun stats(
        since: LocalDateTime? = null,
        ticker: String? = null,
    ): Map<String, Any?> {
        val generatedAt = LocalDateTime.now()
        if (!mlConfig.enabled) {
            return mapOf("mode" to "DISABLED", "generatedAt" to generatedAt.toString())
        }
        val closed = positionRepository.findClosed(ticker, since)
        val wins = closed.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        return mapOf(
            "mode" to "OK",
            "positionsCount" to closed.size,
            "winRate" to if (closed.isEmpty()) null else wins.toDouble() / closed.size,
            "wins" to wins,
            "losses" to closed.size - wins,
            "byTicker" to closed.groupBy { it.ticker }.mapValues { (_, positions) -> tickerStats(positions) },
            "byDirection" to closed.groupBy { it.direction }.mapValues { (_, positions) -> tickerStats(positions) },
            "generatedAt" to generatedAt.toString(),
        )
    }

    private fun tickerStats(positions: List<Position>): Map<String, Any?> {
        val wins = positions.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        return mapOf(
            "positions" to positions.size,
            "wins" to wins,
            "winRate" to if (positions.isEmpty()) null else wins.toDouble() / positions.size,
            "totalPnlRub" to positions.sumOf { (it.pnl ?: BigDecimal.ZERO).toDouble() },
        )
    }

    private suspend fun entryFeatures(
        position: Position,
        timeframe: String,
        lookbackBars: Int,
        barMinutes: Long,
    ): MlFeatureExtractor.Features? {
        val candles =
            candleRepository.findByTickerAndTimeframeAndTimeBetween(
                ticker = position.ticker,
                timeframe = timeframe,
                from = position.openedAt.minusMinutes((lookbackBars + LOOKBACK_WARMUP_BARS) * barMinutes),
                to = position.openedAt,
            )
        return MlFeatureExtractor.extract(candles, lookbackBars)
    }

    private fun buildRow(
        position: Position,
        features: MlFeatureExtractor.Features,
        macro: MacroContextService.MacroContext,
        strategyLog: AgentLog?,
        inBlindSpot: Boolean,
    ): MlDatasetRow {
        val pnlRub = position.pnl ?: BigDecimal.ZERO
        val invested = position.entryPrice.toDouble() * position.quantity
        val pnlPercent = if (invested > 0.0) pnlRub.toDouble() / invested * 100.0 else 0.0
        return MlDatasetRow(
            positionId = position.id!!,
            ticker = position.ticker,
            direction = position.direction.name,
            openedAt = position.openedAt,
            closedAt = position.closedAt,
            durationMinutes = positionDurationMinutes(position.openedAt, position.closedAt),
            entryPrice = position.entryPrice,
            exitPrice = position.closePrice,
            pnlRub = pnlRub,
            pnlPercent = BigDecimal(pnlPercent).setScale(4, RoundingMode.HALF_UP).toDouble(),
            closeReason = position.closeReason,
            win = if (pnlRub > BigDecimal.ZERO) 1 else 0,
            hourOfDay = position.openedAt.hour,
            rsi14 = features.rsi14,
            atrPercent = features.atrPercent,
            macdHistogramPercent = features.macdHistogramPercent,
            bbPercentB = features.bbPercentB,
            emaSlopePercent = features.emaSlopePercent,
            volatility20Percent = features.volatility20Percent,
            return3 = features.return3,
            return10 = features.return10,
            return20 = features.return20,
            cbrRate = macro.cbrRate,
            brentPrice = macro.brentPrice,
            usdRub = macro.usdRub,
            strategyAction = strategyLog?.action,
            strategyConfidence = strategyLog?.confidence,
            inBlindSpotHour = if (inBlindSpot) 1 else 0,
        )
    }

    /** Слепая зона тикера на час входа: активная запись `"Entry at hour H for TICKER"`. */
    private fun inBlindSpotHour(
        spots: List<com.trading.bot.model.entity.BlindSpotEntity>?,
        position: Position,
    ): Boolean = spots?.any { it.conditionPattern == "Entry at hour ${position.openedAt.hour} for ${position.ticker}" } ?: false

    private fun barMinutes(timeframe: String): Long =
        when (timeframe) {
            "MINUTE_1" -> 1L
            "MINUTE_5" -> 5L
            "MINUTE_10" -> 10L
            "MINUTE_15" -> 15L
            "MINUTE_30" -> 30L
            "HOUR_1" -> 60L
            "HOUR_4" -> 240L
            "DAY_1" -> 1440L
            else -> 10L
        }

    private companion object {
        const val MAX_ROWS_HARD_LIMIT = 50_000
        const val LOOKBACK_WARMUP_BARS = 30
    }
}
