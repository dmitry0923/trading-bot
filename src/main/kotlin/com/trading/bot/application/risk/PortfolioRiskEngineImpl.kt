package com.trading.bot.application.risk

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.PortfolioRiskEngine
import com.trading.bot.domain.risk.PortfolioRiskReport
import com.trading.bot.domain.risk.PortfolioRiskRequest
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
 *    внутридневная * sqrt(свечей в сессии); без данных — консервативный max по портфелю).
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
 *   концентрация > [RiskConfig.maxDirectionalConcentrationPercent]% -> запрет входа.
 * - SCALE: при умеренном превышении warn-порогов размер кандидата умножается на
 *   scaleDownFactor (линейная интерполяция warn -> block -> minPortfolioScaleFactor).
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

        val dailyVols = resolveDailyVolPercent(tickers)
        if (dailyVols.isEmpty()) return PortfolioRiskReport(allowed = true)

        val corrMatrix = resolveCorrelationMatrix(tickers)
        val maxPairCorrelation = tickers.indices.flatMap { i -> tickers.indices.map { j -> corrMatrix[i][j] } }
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
        factor = minOf(factor, scaleHigherIsWorse(directionalConcentration, riskConfig.portfolioConcentrationWarnPercent, riskConfig.maxDirectionalConcentrationPercent))
        if (tickers.size >= 2) {
            factor = minOf(
                factor,
                scaleLowerIsWorse(effectivePositions, riskConfig.portfolioEffectiveWarnPositions, riskConfig.minEffectivePositions),
            )
        }

        // BLOCK.
        val reasons = mutableListOf<String>()
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
                .coerceIn(BigDecimal(riskConfig.minPortfolioScaleFactor.toString()), BigDecimal.ONE)

        val allowed = reasons.isEmpty()
        when {
            !allowed -> {
                meterRegistry.counter("portfolio.entry.blocked", Tags.of("reasons", reasons.joinToString("|"))).increment()
                logger.warn {
                    "Portfolio risk BLOCK ${request.candidateTicker}: ${reasons.joinToString("|")} " +
                        "var95=${"%.2f".format(varPercent)}% eff=${"%.2f".format(effectivePositions)} " +
                        "conc=${"%.2f".format(directionalConcentration)}% maxCorr=${"%.2f".format(maxPairCorrelation)}"
                }
            }
            scaleDown < BigDecimal.ONE -> logger.info {
                "Portfolio risk SCALE ${request.candidateTicker}: factor=$scaleDown " +
                    "var95=${"%.2f".format(varPercent)}% eff=${"%.2f".format(effectivePositions)} " +
                    "conc=${"%.2f".format(directionalConcentration)}%"
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
        )
    }

    /**
     * Подписанный нотионал позиции в рублях: LONG -> +entryPrice*qty, SHORT -> -entryPrice*qty.
     */
    private fun signedNotional(pos: Position): Double = pos.entryPrice.toDouble() * pos.quantity * directionSign(pos.direction)

    private fun directionSign(direction: PositionDirection): Double = if (direction == PositionDirection.LONG) 1.0 else -1.0

    /**
     * Дневная волатильность (%) для каждого тикера: DAY_1 realized-vol; fallback —
     * внутридневная * sqrt(свечей в сессии); при отсутствии данных у тикера —
     * консервативный max по доступным. Пустая карта — если данных нет вовсе.
     */
    private fun resolveDailyVolPercent(tickers: List<String>): Map<String, Double> {
        val raw =
            tickers.distinct().associateWith { t ->
                candleCache.calculateRealizedVolatility(t, "DAY_1", riskConfig.volatilityLookbackDays)
                    ?: intradayScaledVol(t)
            }
        val available = raw.values.filterNotNull()
        if (available.isEmpty()) return emptyMap()
        val fallback = available.maxOrNull() ?: 0.0
        return raw.mapValues { it.value ?: fallback }
    }

    private fun intradayScaledVol(ticker: String): Double? {
        val n = riskConfig.volatilityFallbackCandlesPerDay.coerceAtLeast(1)
        val intraday = candleCache.calculateRealizedVolatility(ticker, "MINUTE_10", riskConfig.volatilityLookbackDays)
        return intraday?.let { it * sqrt(n.toDouble()) }
    }

    /**
     * Матрица корреляций (индекс = позиция в списке тикеров) с консервативным fallback:
     * отсутствующая пара заменяется максимальной наблюдаемой корреляцией (без данных — 0).
     */
    private fun resolveCorrelationMatrix(tickers: List<String>): List<List<Double>> {
        val distinct = tickers.distinct()
        val raw = correlationProvider.correlations(distinct, "MINUTE_10", riskConfig.portfolioCorrelationLookbackPeriod)
        val observed = distinct.flatMap { a -> distinct.map { b -> raw[a]?.get(b) } }.filterNotNull()
        val fallback = observed.maxOrNull() ?: 0.0
        return tickers.map { a ->
            tickers.map { b ->
                if (a == b) 1.0 else raw[a]?.get(b) ?: fallback
            }
        }
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
