package com.trading.bot.service

import com.trading.bot.config.MlConfig
import com.trading.bot.domain.ml.MlFeatureExtractor
import com.trading.bot.domain.ml.MlFeatureVector
import com.trading.bot.domain.ml.MlTrendScore
import com.trading.bot.model.dto.MlTrendCandidate
import com.trading.bot.model.dto.MlTrendResult
import com.trading.bot.model.entity.MacroSnapshot
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.MacroSnapshotRepository
import com.trading.bot.service.ml.MlModel
import com.trading.bot.service.ml.MlModelProvider
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * ML-прогноз удержания тренда (roadmap v2.4, раздел 13.11.7).
 *
 * Для каждого тикера строятся признаки на ТЕКУЩИЙ момент (как в [MlScreeningService]:
 * свечи + последний макро-снапшот без lookahead + слепая зона текущего часа), модель
 * прогоняется в обоих направлениях, и оценка удержания тренда [MlTrendScore] =
 * взвесь модели и детерминированной силы тренда по индикаторам. Для тикера остаётся
 * направление с лучшим trendScore; результат ранжируется по trendScore и урезается topN.
 *
 * В отличие от скрининга ([MlScreeningService], ранжирование по сырой вероятности),
 * здесь ранжирование идёт по оценке удержания тренда — «в какую сторону рынок скорее
 * продолжит движение» на горизонте [MlConfig.trend].horizonBars.
 *
 * Если модель недоступна ([MlModelProvider]) — 503 SERVICE_UNAVAILABLE.
 */
@Service
class MlTrendForecastService(
    private val mlConfig: MlConfig,
    private val mlModelProvider: MlModelProvider,
    private val candleRepository: CandleRepository,
    private val blindSpotRepository: BlindSpotRepository,
    private val macroSnapshotRepository: MacroSnapshotRepository,
    private val macroContextService: MacroContextService,
    private val meterRegistry: MeterRegistry,
) {
    suspend fun forecast(
        tickers: List<String>,
        topN: Int?,
    ): MlTrendResult {
        val generatedAt = LocalDateTime.now()
        val requested = tickers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (requested.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "tickers required")
        }
        val model = requireAvailableModel()
        val limit = (topN ?: mlConfig.trend.topN).coerceIn(1, MAX_TOP_N)

        val timeframe = mlConfig.dataset.timeframe
        val lookbackBars = mlConfig.dataset.lookbackBars
        val barMinutes = barMinutes(timeframe)
        val candlesFrom = generatedAt.minusMinutes((lookbackBars + LOOKBACK_WARMUP_BARS) * barMinutes)
        val snapshots = macroSnapshotRepository.findBetween(generatedAt.minusDays(SNAPSHOT_LOOKBACK_DAYS), generatedAt)
        val blindSpotsByTicker = blindSpotRepository.findByIsActiveTrue().groupBy { it.ticker }
        var currentMacro: MacroContextService.MacroContext? = null

        val candidates = mutableListOf<MlTrendCandidate>()
        val skipped = mutableListOf<String>()
        for (ticker in requested) {
            val features =
                MlFeatureExtractor.extract(
                    candleRepository.findByTickerAndTimeframeAndTimeBetween(
                        ticker = ticker,
                        timeframe = timeframe,
                        from = candlesFrom,
                        to = generatedAt,
                    ),
                    lookbackBars,
                ) ?: run {
                    skipped += ticker
                    continue
                }
            val macro =
                latestSnapshotAtOrBefore(snapshots, generatedAt)?.let {
                    MacroContextService.MacroContext(it.cbrRate, it.brentPrice, it.usdRub)
                } ?: (currentMacro ?: macroContextService.fetch().also { currentMacro = it })
            val inBlindSpot =
                blindSpotsByTicker[ticker]?.any { it.conditionPattern == "Entry at hour ${generatedAt.hour} for $ticker" } ?: false
            candidates += bestTrend(model, ticker, features, macro, inBlindSpot, generatedAt.hour)
        }

        candidates.sortByDescending { it.trendScore }
        val top = candidates.take(limit)
        meterRegistry.gauge("ml.trend.candidates", top.size)
        meterRegistry.gauge("ml.trend.skipped", skipped.size)
        meterRegistry.counter("ml.trend.forecast", Tags.of("status", "OK")).increment()
        return MlTrendResult(
            mode = "OK",
            generatedAt = generatedAt,
            horizonBars = mlConfig.trend.horizonBars,
            topN = limit,
            candidates = top,
            skipped = skipped,
        )
    }

    private suspend fun requireAvailableModel(): MlModel {
        val model = mlModelProvider.model
        if (!model.available) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ML model unavailable: ${model.unavailableReason}")
        }
        return model
    }

    private fun bestTrend(
        model: MlModel,
        ticker: String,
        features: MlFeatureExtractor.Features,
        macro: MacroContextService.MacroContext,
        inBlindSpot: Boolean,
        hourOfDay: Int,
    ): MlTrendCandidate =
        listOf("LONG", "SHORT")
            .map { direction ->
                val vector =
                    MlFeatureVector.from(
                        features = features,
                        cbrRate = macro.cbrRate,
                        brentPrice = macro.brentPrice,
                        usdRub = macro.usdRub,
                        inBlindSpotHour = inBlindSpot,
                        hourOfDay = hourOfDay,
                        strategyAction = "",
                        strategySignalStrength = null,
                        direction = direction,
                    )
                val probability = model.probability(vector.numericFeatures(), vector.categoricalFeatures())
                MlTrendCandidate(
                    ticker = ticker,
                    direction = direction,
                    probability = round(probability),
                    trendScore = round(MlTrendScore.score(vector, probability)),
                    inBlindSpotHour = vector.inBlindSpotHour,
                    hourOfDay = vector.hourOfDay,
                )
            }.maxBy { it.trendScore }

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

    private fun round(value: Double): Double = BigDecimal(value).setScale(4, RoundingMode.HALF_UP).toDouble()

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
        const val MAX_TOP_N = 100
        const val LOOKBACK_WARMUP_BARS = 30
        const val SNAPSHOT_LOOKBACK_DAYS = 1L
    }
}
