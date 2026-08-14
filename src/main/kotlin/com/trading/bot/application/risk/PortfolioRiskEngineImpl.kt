package com.trading.bot.application.risk

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.PortfolioDataQuality
import com.trading.bot.domain.risk.PortfolioRiskEngine
import com.trading.bot.domain.risk.PortfolioRiskReport
import com.trading.bot.domain.risk.PortfolioRiskRequest
import com.trading.bot.domain.risk.ResolvedCorrelationMatrix
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.CorrelationMatrixProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Агрегированный портфельный риск (Markowitz).
 *
 * В отличие от попарных корреляционных фильтров (AdaptiveRiskService) считает риск
 * ВСЕГО портфеля после добавления кандидата:
 *
 * 1. Веса по нотионалу (long > 0, short < 0; |веса| суммируются к 1 по gross).
 * 2. Дневная волатильность каждого тикера — realized-vol по DAY_1 (fallback:
 *    внутридневная * sqrt(свечей в сессии)). Качество данных отслеживается
 *    три-состоянием [PortfolioDataQuality] (KNOWN / ESTIMATED / INSUFFICIENT) и
 *    масштабирует размер кандидата через [PortfolioRiskReport.dataQualityScale]:
 *    KNOWN -> 100%, ESTIMATED -> *0.5, INSUFFICIENT -> *0.0 (fail-closed).
 * 3. Портфельная дисперсия: σp² = Σ wᵢ²σᵢ² + 2·Σᵢ<ⱼ wᵢwⱼσᵢσⱼρᵢⱼ.
 * 4. VaR95 = 1.645 · σp · grossExposure.
 * 5. effectivePositions = (Σ|wᵢ|)² / Σᵢⱼ|wᵢ||wⱼ|ρᵢⱼ — эффективное число НЕЗАВИСИМЫХ
 *    ставок: идеально коррелированный кластер -> 1 («три позиции = одна ставка на
 *    рынок»), независимые инструменты -> число позиций.
 * 6. Направленная концентрация = |net| / gross · 100%.
 *
 * Режимы реакции (BLOCK + SCALE):
 * - BLOCK ([RiskConfig.portfolioRiskBlocked]): VaR95 > [RiskConfig.maxPortfolioVaRPercent]%
 *   AUM; effectivePositions < [RiskConfig.minEffectivePositions]; направленная
 *   концентрация > [RiskConfig.maxDirectionalConcentrationPercent]%; отсутствие
 *   данных о волатильности -> PORTFOLIO_DATA_INSUFFICIENT (вместо прежнего
 *   fail-open «пропустить портфельную проверку без данных»).
 * - SCALE: при умеренном превышении warn-порогов размер кандидата умножается на
 *   scaleDownFactor (линейная интерполяция warn -> block -> minPortfolioScaleFactor),
 *   затем на [PortfolioRiskReport.dataQualityScale] (качество данных).
 *
 * Провайдеры данных — [CorrelationMatrixProvider] и [CandleCacheService] (без БД).
 */
@Service
class PortfolioRiskEngineImpl(
    private val riskConfig: RiskConfig,
    private val correlationProvider: CorrelationMatrixProvider,
    private val candleCache: CandleCacheService,
    private val meterRegistry: MeterRegistry,
) : PortfolioRiskEngine {
    private val logger = KotlinLogging.logger {}

    override suspend fun evaluate(request: PortfolioRiskRequest): PortfolioRiskReport {
        if (!riskConfig.portfolioRiskEnabled) return PortfolioRiskReport(allowed = true)

        val entries: List<Pair<String, Double>> =
            buildList {
                request.openPositions.forEach { add(it.ticker to signedNotional(it)) }
                val sign = if (request.candidateDirection == PositionDirection.LONG) 1.0 else -1.0
                add(request.candidateTicker to sign * request.candidateNotionalRub.toDouble())
            }
        val tickers = entries.map { it.first }
        val notionals = entries.map { it.second }
        val gross = notionals.sumOf { abs(it) }
        if (gross <= 0.0 || request.aum <= BigDecimal.ZERO) return PortfolioRiskReport(allowed = true)

        val weights = notionals.map { it / gross }
        val absWeights = weights.map { abs(it) }

        val (dailyVols, perTickerVolQuality) = resolveDailyVolPercent(tickers)
        val volQuality = aggregateQuality(perTickerVolQuality.values)
        val resolvedCorr = resolveCorrelationMatrix(tickers)
        val corrMatrix = resolvedCorr.matrix
        val corrQuality = resolvedCorr.quality

        val maxPairCorrelation =
            tickers.indices
                .flatMap { i -> tickers.indices.map { j -> corrMatrix[i][j] } }
                .filter { it != 1.0 }
                .maxOrNull() ?: 0.0

        val variance = portfolioVariance(tickers, weights, dailyVols, corrMatrix)
        val dailyVol = sqrt(variance.coerceAtLeast(0.0))
        val var95Rub = 1.645 * dailyVol * gross
        val effectivePositions = effectiveBets(absWeights, corrMatrix)
        val directionalConcentration = abs(notionals.sum()) / gross * 100.0

        val varPercent = var95Rub / request.aum.toDouble() * 100.0

        // SCALE: минимум факторов по всем метрикам (1.0 = без изменений).
        var factor = scaleHigherIsWorse(varPercent, riskConfig.portfolioVarWarnPercent, riskConfig.maxPortfolioVaRPercent)
        factor =
            minOf(
                factor,
                scaleHigherIsWorse(
                    directionalConcentration,
                    riskConfig.portfolioConcentrationWarnPercent,
                    riskConfig.maxDirectionalConcentrationPercent,
                ),
            )
        if (tickers.size >= 2) {
            factor =
                minOf(
                    factor,
                    scaleLowerIsWorse(effectivePositions, riskConfig.portfolioEffectiveWarnPositions, riskConfig.minEffectivePositions),
                )
        }

        // Качество данных: KNOWN -> 1.0, ESTIMATED -> 0.5, INSUFFICIENT -> 0.0 (vol)
        // или 0.25 (corr). Отсутствие данных о волатильности = fail-closed, а не fail-open.
        val dataQuality = dataQualityScale(volQuality, corrQuality)
        factor *= dataQuality

        // BLOCK.
        val reasons = mutableListOf<String>()
        if (riskConfig.portfolioRiskBlocked && volQuality == PortfolioDataQuality.INSUFFICIENT) {
            reasons += "PORTFOLIO_DATA_INSUFFICIENT"
        }
        if (riskConfig.portfolioRiskBlocked && varPercent > riskConfig.maxPortfolioVaRPercent) reasons += "PORTFOLIO_VAR"
        if (riskConfig.portfolioRiskBlocked && tickers.size >= 2 && effectivePositions < riskConfig.minEffectivePositions) {
            reasons += "PORTFOLIO_CONCENTRATION"
        }
        if (riskConfig.portfolioRiskBlocked && directionalConcentration > riskConfig.maxDirectionalConcentrationPercent) {
            reasons += "PORTFOLIO_DIRECTIONAL"
        }

        val scaleDown =
            BigDecimal(factor.toString())
                .setScale(4, RoundingMode.HALF_UP)
                .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)

        val allowed = reasons.isEmpty()
        when {
            !allowed -> {
                meterRegistry.counter("portfolio.entry.blocked", Tags.of("reasons", reasons.joinToString("|"))).increment()
                logger.warn {
                    "Portfolio risk BLOCK ${request.candidateTicker}: ${reasons.joinToString("|")} " +
                        "var95=${"%.2f".format(varPercent)}% eff=${"%.2f".format(effectivePositions)} " +
                        "conc=${"%.2f".format(directionalConcentration)}% maxCorr=${"%.2f".format(maxPairCorrelation)} " +
                        "data=$volQuality/$corrQuality"
                }
            }

            scaleDown < BigDecimal.ONE -> {
                logger.info {
                    "Portfolio risk SCALE ${request.candidateTicker}: factor=$scaleDown " +
                        "var95=${"%.2f".format(varPercent)}% eff=${"%.2f".format(effectivePositions)} " +
                        "conc=${"%.2f".format(directionalConcentration)}% data=$volQuality/$corrQuality"
                }
            }
        }

        meterRegistry.gauge("portfolio.var95_rub", var95Rub)
        meterRegistry.gauge("portfolio.daily_vol_percent", dailyVol * 100.0)
        meterRegistry.gauge("portfolio.effective_positions", effectivePositions)
        meterRegistry.gauge("portfolio.directional_concentration", directionalConcentration)

        return PortfolioRiskReport(
            allowed = allowed,
            reasons = reasons,
            scaleDownFactor = scaleDown,
            portfolioDailyVolPercent = BigDecimal(dailyVol * 100.0).setScale(4, RoundingMode.HALF_UP),
            var95Rub = BigDecimal(var95Rub).setScale(2, RoundingMode.HALF_UP),
            effectivePositions = BigDecimal(effectivePositions).setScale(4, RoundingMode.HALF_UP),
            directionalConcentrationPercent = BigDecimal(directionalConcentration).setScale(4, RoundingMode.HALF_UP),
            maxPairCorrelation = maxPairCorrelation,
            volatilityDataQuality = volQuality,
            correlationDataQuality = corrQuality,
            dataQualityScale = BigDecimal(dataQuality.toString()).setScale(4, RoundingMode.HALF_UP),
        )
    }

    /**
     * Подписанный нотионал позиции в рублях: LONG -> +entryPrice*qty, SHORT -> -entryPrice*qty.
     */
    private fun signedNotional(pos: Position): Double = pos.entryPrice.toDouble() * pos.quantity * directionSign(pos.direction)

    private fun directionSign(direction: PositionDirection): Double = if (direction == PositionDirection.LONG) 1.0 else -1.0

    /** Волатильности тикеров + качество данных по каждому из них. */
    private data class VolatilityData(
        val dailyVolPercent: Map<String, Double>,
        val perTickerQuality: Map<String, PortfolioDataQuality>,
    )

    /**
     * Дневная волатильность (%) и качество данных по каждому тикеру:
     * KNOWN — DAY_1 realized-vol; ESTIMATED — внутридневная * sqrt(свечей в сессии);
     * INSUFFICIENT — данных нет вовсе. Для тикеров без данных волатильность
     * заменяется консервативным максимумом по доступным (расчёт VaR не ломается),
     * но качество помечается INSUFFICIENT и масштабирует размер в 0.
     */
    private fun resolveDailyVolPercent(tickers: List<String>): VolatilityData {
        val day1 =
            tickers.distinct().associateWith { t ->
                candleCache.calculateRealizedVolatility(t, "DAY_1", riskConfig.volatilityLookbackDays)
            }
        val intraday = day1.mapValues { (t, v) -> v ?: intradayScaledVol(t) }
        val perTickerQuality =
            day1.mapValues { (t, v) ->
                when {
                    v != null -> PortfolioDataQuality.KNOWN
                    intraday[t] != null -> PortfolioDataQuality.ESTIMATED
                    else -> PortfolioDataQuality.INSUFFICIENT
                }
            }
        val available = intraday.values.filterNotNull()
        if (available.isEmpty()) return VolatilityData(emptyMap(), perTickerQuality)
        val fallback = available.maxOrNull()!!
        return VolatilityData(intraday.mapValues { it.value ?: fallback }, perTickerQuality)
    }

    /**
     * Агрегированное качество по всем тикерам: INSUFFICIENT, если хоть у одного
     * данных нет; иначе ESTIMATED, если хоть у одного данные оценены; иначе KNOWN.
     */
    private fun aggregateQuality(qualities: Collection<PortfolioDataQuality>): PortfolioDataQuality =
        when {
            qualities.any { it == PortfolioDataQuality.INSUFFICIENT } -> PortfolioDataQuality.INSUFFICIENT
            qualities.any { it == PortfolioDataQuality.ESTIMATED } -> PortfolioDataQuality.ESTIMATED
            else -> PortfolioDataQuality.KNOWN
        }

    private fun intradayScaledVol(ticker: String): Double? {
        val n = riskConfig.volatilityFallbackCandlesPerDay.coerceAtLeast(1)
        val intraday = candleCache.calculateRealizedVolatility(ticker, "MINUTE_10", riskConfig.volatilityLookbackDays)
        return intraday?.let { it * sqrt(n.toDouble()) }
    }

    /**
     * Матрица корреляций (индекс = позиция в списке тикеров) с консервативным
     * fallback и качеством данных ([CorrelationMatrixProvider.resolvedWithQuality]).
     */
    private fun resolveCorrelationMatrix(tickers: List<String>): ResolvedCorrelationMatrix =
        correlationProvider.resolvedWithQuality(tickers, "MINUTE_10", riskConfig.portfolioCorrelationLookbackPeriod)

    /**
     * Множитель размера по качеству данных — минимум шкалы волатильности и шкалы
     * корреляций: KNOWN -> 1.0; ESTIMATED (волатильность) ->
     * [RiskConfig.portfolioEstimatedVolScale]; INSUFFICIENT (волатильность) ->
     * [RiskConfig.portfolioInsufficientVolScale] (0.0 = fail-closed); INSUFFICIENT
     * (корреляции) -> [RiskConfig.portfolioInsufficientCorrelationScale].
     */
    private fun dataQualityScale(
        volQuality: PortfolioDataQuality,
        corrQuality: PortfolioDataQuality,
    ): Double {
        val volScale =
            when (volQuality) {
                PortfolioDataQuality.KNOWN -> 1.0
                PortfolioDataQuality.ESTIMATED -> riskConfig.portfolioEstimatedVolScale
                PortfolioDataQuality.INSUFFICIENT -> riskConfig.portfolioInsufficientVolScale
            }
        // ESTIMATED-уровень качества корреляций пока не используется (зарезервирован).
        val corrScale =
            when (corrQuality) {
                PortfolioDataQuality.KNOWN -> 1.0
                PortfolioDataQuality.ESTIMATED -> 1.0
                PortfolioDataQuality.INSUFFICIENT -> riskConfig.portfolioInsufficientCorrelationScale
            }
        return minOf(volScale, corrScale)
    }

    /**
     * Портфельная дисперсия (десятичные доли):
     * σp² = Σᵢ wᵢ²σᵢ² + 2·Σᵢ<ⱼ wᵢwⱼσᵢσⱼρᵢⱼ. Знаки весов учитывают хедж long/short.
     */
    private fun portfolioVariance(
        tickers: List<String>,
        weights: List<Double>,
        dailyVols: Map<String, Double>,
        corr: List<List<Double>>,
    ): Double {
        var variance = 0.0
        for (i in tickers.indices) {
            val wi = weights[i]
            val si = (dailyVols[tickers[i]] ?: 0.0) / 100.0
            variance += wi * wi * si * si
            for (j in i + 1 until tickers.size) {
                val wj = weights[j]
                val sj = (dailyVols[tickers[j]] ?: 0.0) / 100.0
                val rho = corr[i][j]
                variance += 2.0 * wi * wj * si * sj * rho
            }
        }
        return variance
    }

    /**
     * Эффективное число независимых ставок (correlation-adjusted HHI):
     * eff = (Σ|wᵢ|)² / Σᵢⱼ|wᵢ||wⱼ|ρᵢⱼ. Так как Σ|wᵢ| = 1, eff = 1 / Σᵢⱼ|wᵢ||wⱼ|ρᵢⱼ.
     * Идеально коррелированный кластер -> 1; отрицательные корреляции (хедж) раздувают
     * знаменатель -> большие значения, не блокируются.
     */
    private fun effectiveBets(
        absWeights: List<Double>,
        corr: List<List<Double>>,
    ): Double {
        var sum = 0.0
        for (i in absWeights.indices) {
            for (j in absWeights.indices) {
                sum += absWeights[i] * absWeights[j] * corr[i][j]
            }
        }
        return if (sum > 1e-9) 1.0 / sum else 1_000_000.0
    }

    /**
     * SCALE-фактор для метрики, где «хуже = больше» (VaR%, концентрация%):
     * value <= warn -> 1.0; value >= block -> floor; иначе линейная интерполяция.
     */
    private fun scaleHigherIsWorse(
        value: Double,
        warn: Double,
        block: Double,
    ): Double {
        if (block <= warn) return 1.0
        if (value <= warn) return 1.0
        if (value >= block) return riskConfig.minPortfolioScaleFactor
        val t = (value - warn) / (block - warn)
        return (1.0 - t * (1.0 - riskConfig.minPortfolioScaleFactor)).coerceIn(riskConfig.minPortfolioScaleFactor, 1.0)
    }

    /**
     * SCALE-фактор для метрики, где «хуже = меньше» (effectivePositions):
     * value >= warn -> 1.0; value <= block -> floor; иначе линейная интерполяция.
     */
    private fun scaleLowerIsWorse(
        value: Double,
        warn: Double,
        block: Double,
    ): Double {
        if (warn <= block) return 1.0
        if (value >= warn) return 1.0
        if (value <= block) return riskConfig.minPortfolioScaleFactor
        val t = (warn - value) / (warn - block)
        return (1.0 - t * (1.0 - riskConfig.minPortfolioScaleFactor)).coerceIn(riskConfig.minPortfolioScaleFactor, 1.0)
    }
}
