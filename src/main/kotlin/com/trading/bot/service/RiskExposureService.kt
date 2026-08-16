package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.PositionExposure
import com.trading.bot.model.dto.RiskExposureReport
import com.trading.bot.model.dto.SectorExposure
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Live-снимок портфельного риска для Correlation Engine.
 *
 * Строит агрегированный отчёт по ОТКРЫТЫМ позициям: Gross/Net Exposure в % AUM,
 * секторная экспозиция, корреляционная матрица (общий [CorrelationMatrixProvider]),
 * эффективное число независимых ставок, VaR95 и единый **Exposure Score (0..100)**.
 *
 * В отличие от входных фильтров ([com.trading.bot.application.risk.PortfolioRiskEngineImpl]),
 * которые отвечают «можно ли добавить кандидата», этот сервис описывает ТЕКУЩЕЕ
 * состояние портфеля — информационно. Все метрики публикуются в Prometheus
 * (risk.exposure.*) и отдаются через GET /api/v1/risk/exposure.
 *
 * Нотионал позиции в рублях = entryPrice × qty × pointValue(ticker) для фьючерсов
 * (13.28.2, MR-Z): точка цены фьючерса ≠ рубль, pointValue масштабирует пункты в ₽
 * (Si: 1000 ₽ на 1.0 цены). Акции: entryPrice × qty (qty в штуках, pointValue = 1)
 * — единая семантика с P&L ([DrawdownProtectionService.unrealizedPnl]). Это исправляет
 * занижение фьючерсной экспозиции в отчёте; входные гейты (RiskManagementService/
 * PortfolioRiskEngineImpl) сознательно НЕ масштабируются (см. 13.28.2).
 *
 * Exposure Score = 100 · (0.25·концентрация + 0.25·(1/effN нормализовано) +
 * 0.25·(VaR%/лимит) + 0.125·(gross%/лимит) + 0.125·(|net%|/лимит)), каждый член в [0,1].
 */
@Service
class RiskExposureService(
    private val riskConfig: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val positionRepo: PositionRepository,
    private val correlationProvider: CorrelationMatrixProvider,
    private val candleCache: CandleCacheService,
    private val meterRegistry: MeterRegistry,
    private val aumProvider: AumProvider,
) {
    private val logger = KotlinLogging.logger {}

    private val scoreGauge = AtomicLong()
    private val grossPercentGauge = AtomicLong()
    private val netPercentGauge = AtomicLong()
    private val var95PercentGauge = AtomicLong()
    private val effectivePositionsGauge = AtomicLong()
    private val sectorGauges = ConcurrentHashMap<String, AtomicLong>()

    init {
        meterRegistry.gauge("risk.exposure.score", scoreGauge) { it.get().toDouble() }
        meterRegistry.gauge("risk.exposure.gross_percent", grossPercentGauge) { it.get().toDouble() }
        meterRegistry.gauge("risk.exposure.net_percent", netPercentGauge) { it.get().toDouble() }
        meterRegistry.gauge("risk.exposure.var95_percent", var95PercentGauge) { it.get().toDouble() }
        meterRegistry.gauge("risk.exposure.effective_positions", effectivePositionsGauge) { it.get().toDouble() }
    }

    /**
     * Строит live-снимок портфельного риска по открытым позициям.
     *
     * @return [RiskExposureReport] (без БД-записи)
     */
    suspend fun buildSnapshot(): RiskExposureReport {
        val open = positionRepo.findByStatus(PositionStatus.OPEN)
        val aum = aumProvider.currentAum()
        val timestamp = LocalDateTime.now()

        val perPosition = open.map { toPositionExposure(it, aum) }
        val perSector = aggregateBySector(open, aum)

        val gross = open.sumOf { abs(signedNotional(it)) }
        val net = open.sumOf { signedNotional(it) }
        val grossPercent = percentOf(gross, aum)
        val netPercent = percentOf(net, aum)

        val tickers = open.map { it.ticker }.distinct()
        val corrMatrix = correlationProvider.correlations(tickers, "MINUTE_10", riskConfig.portfolioCorrelationLookbackPeriod)
        val maxPair = maxPairCorrelation(tickers, corrMatrix)

        val effectiveN = if (gross > 0.0) effectivePositions(open, gross) else 0.0
        val var95Rub = if (gross > 0.0 && aum > BigDecimal.ZERO) var95(open, gross) else 0.0
        val var95PercentVal = if (aum > BigDecimal.ZERO) var95Rub / aum.toDouble() * 100.0 else 0.0

        val exposureScore =
            if (open.isEmpty()) {
                0
            } else {
                computeScore(
                    directionalConcentrationPercent = if (gross > 0.0) abs(net) / gross * 100.0 else 0.0,
                    effectivePositions = effectiveN,
                    varPercent = var95PercentVal,
                    grossPercent = grossPercent,
                    netPercent = netPercent,
                )
            }

        registerMetrics(exposureScore, grossPercent, netPercent, var95PercentVal, effectiveN, perSector)
        logger.info {
            "Exposure snapshot: score=$exposureScore gross=${"%.1f".format(grossPercent)}% net=${"%.1f".format(netPercent)}% " +
                "eff=${"%.2f".format(effectiveN)} var95=${"%.2f".format(var95PercentVal)}% positions=${open.size}"
        }

        return RiskExposureReport(
            aum = aum,
            exposureScore = exposureScore,
            grossExposureRub = BigDecimal(gross).setScale(2, RoundingMode.HALF_UP),
            grossExposurePercent = BigDecimal(grossPercent).setScale(2, RoundingMode.HALF_UP),
            grossLimitPercent = BigDecimal(riskConfig.maxGrossExposurePercent).setScale(2, RoundingMode.HALF_UP),
            netExposureRub = BigDecimal(net).setScale(2, RoundingMode.HALF_UP),
            netExposurePercent = BigDecimal(netPercent).setScale(2, RoundingMode.HALF_UP),
            netLimitPercent = BigDecimal(riskConfig.maxNetExposurePercent).setScale(2, RoundingMode.HALF_UP),
            perPositionExposure = perPosition,
            perSectorExposure = perSector,
            correlationMatrix = corrMatrix,
            maxPairCorrelation = maxPair,
            effectivePositions = BigDecimal(effectiveN).setScale(4, RoundingMode.HALF_UP),
            var95Rub = BigDecimal(var95Rub).setScale(2, RoundingMode.HALF_UP),
            var95Percent = BigDecimal(var95PercentVal).setScale(4, RoundingMode.HALF_UP),
            timestamp = timestamp,
        )
    }

    /**
     * Полная корреляционная матрица для произвольного списка тикеров
     * (heatmap watchlist). Делегирование в общий [CorrelationMatrixProvider].
     *
     * @param tickers тикеры
     * @param timeframe таймфрейм свечей
     * @param period глубина расчёта
     */
    fun correlationMatrix(
        tickers: List<String>,
        timeframe: String = "MINUTE_10",
        period: Int = riskConfig.portfolioCorrelationLookbackPeriod,
    ): Map<String, Map<String, Double?>> = correlationProvider.correlations(tickers, timeframe, period)

    private fun toPositionExposure(
        pos: Position,
        aum: BigDecimal,
    ): PositionExposure {
        val notional = signedNotional(pos)
        val pct = if (aum > BigDecimal.ZERO) abs(notional) / aum.toDouble() * 100.0 else 0.0
        return PositionExposure(
            ticker = pos.ticker,
            direction = pos.direction.name,
            sector = sectorOf(pos.ticker),
            notionalRub = BigDecimal(notional).setScale(2, RoundingMode.HALF_UP),
            exposurePercentAum = BigDecimal(pct).setScale(2, RoundingMode.HALF_UP),
        )
    }

    private fun aggregateBySector(
        open: List<Position>,
        aum: BigDecimal,
    ): List<SectorExposure> =
        open
            .groupBy { sectorOf(it.ticker) }
            .map { (sector, positions) ->
                val grossPct = positions.sumOf { abs(signedNotional(it)) }
                val netPct = positions.sumOf { signedNotional(it) }
                SectorExposure(
                    sector = sector,
                    positionCount = positions.size,
                    grossPercentAum = percentOf(grossPct, aum).let { BigDecimal(it).setScale(2, RoundingMode.HALF_UP) },
                    netPercentAum = percentOf(netPct, aum).let { BigDecimal(it).setScale(2, RoundingMode.HALF_UP) },
                )
            }.sortedByDescending { it.grossPercentAum }

    private fun signedNotional(pos: Position): Double =
        pos.entryPrice.toDouble() * pos.quantity * pointValue(pos) * directionSign(pos.direction)

    private fun pointValue(pos: Position): Double =
        if (pos.instrumentType == InstrumentType.FUTURES) instrumentsConfig.pointValue(pos.ticker).toDouble() else 1.0

    private fun directionSign(direction: PositionDirection): Double = if (direction == PositionDirection.LONG) 1.0 else -1.0

    private fun sectorOf(ticker: String): String = riskConfig.sectors[ticker] ?: "UNKNOWN"

    private fun percentOf(
        value: Double,
        aum: BigDecimal,
    ): Double = if (aum > BigDecimal.ZERO) value / aum.toDouble() * 100.0 else 0.0

    /**
     * Эффективное число независимых ставок (correlation-adjusted HHI):
     * eff = (Σ|wᵢ|)² / Σᵢⱼ|wᵢ||wⱼ|ρᵢⱼ. Идеально коррелированный кластер -> 1.
     */
    private fun effectivePositions(
        open: List<Position>,
        gross: Double,
    ): Double {
        val tickers = open.map { it.ticker }.distinct()
        val weights = open.map { abs(signedNotional(it)) / gross }
        val raw =
            correlationProvider.correlations(
                tickers,
                "MINUTE_10",
                riskConfig.portfolioCorrelationLookbackPeriod,
            )
        var sum = 0.0
        for (i in weights.indices) {
            for (j in weights.indices) {
                val rho =
                    raw[open[i].ticker]?.get(open[j].ticker)
                        ?: (if (open[i].ticker == open[j].ticker) 1.0 else 0.0)
                sum += weights[i] * weights[j] * rho
            }
        }
        return if (sum > 1e-9) 1.0 / sum else 1_000_000.0
    }

    /**
     * VaR95 портфеля в рублях: 1.645 · σp · gross. Дневная волатильность — realized
     * vol по DAY_1 с внутридневным fallback (как в PortfolioRiskEngineImpl).
     */
    private fun var95(
        open: List<Position>,
        gross: Double,
    ): Double {
        val tickers = open.map { it.ticker }.distinct()
        val dailyVols = resolveDailyVolPercent(tickers)
        if (dailyVols.isEmpty()) return 0.0
        val corr = resolveCorrelationMatrix(tickers)
        val weights = open.map { signedNotional(it) / gross }
        var variance = 0.0
        for (i in open.indices) {
            val wi = weights[i]
            val si = (dailyVols[open[i].ticker] ?: 0.0) / 100.0
            variance += wi * wi * si * si
            for (j in i + 1 until open.size) {
                val wj = weights[j]
                val sj = (dailyVols[open[j].ticker] ?: 0.0) / 100.0
                variance += 2.0 * wi * wj * si * sj * corr[i][j]
            }
        }
        return 1.645 * sqrt(variance.coerceAtLeast(0.0)) * gross
    }

    private fun resolveDailyVolPercent(tickers: List<String>): Map<String, Double> {
        val raw =
            tickers.associateWith { t ->
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
        val vol = candleCache.calculateRealizedVolatility(ticker, "MINUTE_10", riskConfig.volatilityLookbackDays) ?: return null
        return vol * sqrt(n.toDouble())
    }

    private fun resolveCorrelationMatrix(tickers: List<String>): List<List<Double>> =
        correlationProvider.resolved(tickers, "MINUTE_10", riskConfig.portfolioCorrelationLookbackPeriod)

    private fun maxPairCorrelation(
        tickers: List<String>,
        matrix: Map<String, Map<String, Double?>>,
    ): Double {
        if (tickers.size < 2) return 0.0
        return tickers
            .flatMap { a -> tickers.mapNotNull { b -> if (a == b) null else matrix[a]?.get(b) } }
            .maxOrNull() ?: 0.0
    }

    private fun computeScore(
        directionalConcentrationPercent: Double,
        effectivePositions: Double,
        varPercent: Double,
        grossPercent: Double,
        netPercent: Double,
    ): Int {
        if (effectivePositions <= 0.0) return 0
        val concScore = (directionalConcentrationPercent / 100.0).coerceIn(0.0, 1.0)
        val maxEffectiveDenominator = riskConfig.maxOpenPositions * 2.0
        val effScore =
            (1.0 - (effectivePositions - 1.0) / maxEffectiveDenominator).coerceIn(0.0, 1.0)
        val varScore = (varPercent / riskConfig.maxPortfolioVaRPercent).coerceIn(0.0, 1.0)
        val grossScore = (grossPercent / riskConfig.maxGrossExposurePercent).coerceIn(0.0, 1.0)
        val netScore = (abs(netPercent) / riskConfig.maxNetExposurePercent).coerceIn(0.0, 1.0)

        val score =
            100.0 * (0.25 * concScore + 0.25 * effScore + 0.25 * varScore + 0.125 * grossScore + 0.125 * netScore)
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun registerMetrics(
        score: Int,
        grossPercent: Double,
        netPercent: Double,
        var95Percent: Double,
        effectiveN: Double,
        sectors: List<SectorExposure>,
    ) {
        scoreGauge.set(score.toLong())
        grossPercentGauge.set((grossPercent * 100).toLong())
        netPercentGauge.set((netPercent * 100).toLong())
        var95PercentGauge.set((var95Percent * 100).toLong())
        effectivePositionsGauge.set((effectiveN * 100).toLong())
        sectors.forEach { s ->
            sectorGauges
                .computeIfAbsent(s.sector) { sector ->
                    AtomicLong().also { gauge ->
                        meterRegistry.gauge("risk.exposure.sector_percent", Tags.of("sector", sector), gauge) { it.get().toDouble() }
                    }
                }.set((s.grossPercentAum.toDouble() * 100).toLong())
        }
    }
}
