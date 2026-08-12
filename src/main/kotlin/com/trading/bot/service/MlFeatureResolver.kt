package com.trading.bot.service

import com.trading.bot.config.MlConfig
import com.trading.bot.domain.ml.MlFeatureExtractor
import com.trading.bot.domain.ml.MlFeatureVector
import com.trading.bot.model.entity.MacroSnapshot
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.MacroSnapshotRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Построение вектора признаков для ОДНОГО тикера на момент времени (roadmap v2.4,
 * 13.11.5). Используется ML-фильтром входа ([MlEntryFilter]); массовый вариант для
 * скрининга остаётся в [MlScreeningService] (там снапшоты/слепые зоны грузятся
 * батчем на все тикеры запроса).
 *
 * Макро берётся из исторических снапшотов — последний с `captured_at <= at`
 * (без lookahead), фолбэк на текущий контекст. Возвращает null при недостатке
 * свечей (< 30 баров для индикаторов).
 */
@Service
class MlFeatureResolver(
    private val mlConfig: MlConfig,
    private val candleRepository: CandleRepository,
    private val blindSpotRepository: BlindSpotRepository,
    private val macroSnapshotRepository: MacroSnapshotRepository,
    private val macroContextService: MacroContextService,
) {
    suspend fun resolve(
        ticker: String,
        at: LocalDateTime,
        strategyAction: String,
        strategyConfidence: Double?,
        direction: String,
    ): MlFeatureVector? {
        val timeframe = mlConfig.dataset.timeframe
        val lookbackBars = mlConfig.dataset.lookbackBars
        val candles =
            candleRepository.findByTickerAndTimeframeAndTimeBetween(
                ticker = ticker,
                timeframe = timeframe,
                from = at.minusMinutes((lookbackBars + LOOKBACK_WARMUP_BARS) * barMinutes(timeframe)),
                to = at,
            )
        val features = MlFeatureExtractor.extract(candles, lookbackBars) ?: return null

        val macro =
            latestSnapshotAtOrBefore(macroSnapshotRepository.findBetween(at.minusDays(SNAPSHOT_LOOKBACK_DAYS), at), at)
                ?.let { MacroContextService.MacroContext(it.cbrRate, it.brentPrice, it.usdRub) }
                ?: macroContextService.fetch()
        val inBlindSpot =
            blindSpotRepository.findByIsActiveTrue().any {
                it.ticker == ticker && it.conditionPattern == "Entry at hour ${at.hour} for $ticker"
            }
        return MlFeatureVector.from(
            features = features,
            cbrRate = macro.cbrRate,
            brentPrice = macro.brentPrice,
            usdRub = macro.usdRub,
            inBlindSpotHour = inBlindSpot,
            hourOfDay = at.hour,
            strategyAction = strategyAction,
            strategyConfidence = strategyConfidence,
            direction = direction,
        )
    }

    /** Последний снапшот с captured_at <= [time] (бинарный поиск по ASC-списку). */
    private fun latestSnapshotAtOrBefore(
        snapshots: List<MacroSnapshot>,
        time: LocalDateTime,
    ): MacroSnapshot? {
        if (snapshots.isEmpty()) return null
        val index = snapshots.binarySearchBy(time) { it.capturedAt }
        if (index >= 0) return snapshots[index]
        val insertion = -index - 1
        return if (insertion == 0) null else snapshots[insertion - 1]
    }

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
        const val LOOKBACK_WARMUP_BARS = 30
        const val SNAPSHOT_LOOKBACK_DAYS = 1L
    }
}
