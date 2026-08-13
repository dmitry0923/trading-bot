package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.ConfidenceCalibrator
import com.trading.bot.domain.risk.MarketRegime
import com.trading.bot.domain.risk.MarketRegimeProvider
import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDetector
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.math.sqrt

/**
 * Адаптивный риск-менеджмент на основе статистики сделок (Kelly).
 *
 * - calculateOptimalPositionSize(): размер позиции по критерию Келли с робастной
 *   статистикой (Wilson lower bound win rate, минимум сделок, консервативный fallback),
 *   volatility targeting по ДНЕВНОЙ волатильности (realized-vol / ATR%) и непрерывной
 *   деградацией по глубине просадки от пика AUM
 * - Market regime: режим VOLATILE урезает размер на regimeVolatileSizeMultiplier,
 *   STRESS обнуляет размер (входы блокирует FuturesRiskEngine)
 * - Адаптивные SL/TP: множитель ATR зависит от sl/tp hit rate тикера
 * - Адаптивный порог уверенности арбитра: онлайн-калибровка по исходам сделок
 *   (ConfidenceCalibrator), fallback — правила по win rate
 * - Confidence-aware сайзинг: размер позиции масштабируется по уверенности сигнала
 *   относительно адаптивного порога (порог -> min factor, ceiling -> max factor)
 * - shouldPauseTrading()/isInDrawdownRecovery(): пауза при серии убытков
 * - exceedsCorrelationLimit()/exceedsSectorCorrelationLimit(): корреляционные
 *   фильтры по закрытиям из Redis (математика — общий [CorrelationMatrixProvider])
 * - Все решения логируются в метрики adaptive.*
 */
@Service
class AdaptiveRiskService(
    private val riskConfig: RiskConfig,
    private val tradeAnalysisService: TradeAnalysisService,
    private val positionRepo: PositionRepository,
    private val candleCache: CandleCacheService,
    private val drawdownProtection: DrawdownProtectionService,
    private val meterRegistry: MeterRegistry,
    private val correlationProvider: CorrelationMatrixProvider,
    private val marketRegimeProvider: MarketRegimeProvider,
    private val aumProvider: AumProvider,
    private val agentLogRepository: AgentLogRepository,
) {
    private val logger = KotlinLogging.logger {}
    private val correlationThreshold = 0.8

    /**
     * Коэффициент корреляции Пирсона между ценами закрытия двух тикеров.
     *
     * Делегирование в общий [CorrelationMatrixProvider] — единая математика
     * для корреляционных фильтров и портфельного риск-движка.
     */
    fun correlationOf(
        a: String,
        b: String,
        timeframe: String = "MINUTE_10",
        period: Int = 50,
    ): Double? = correlationProvider.correlationOf(a, b, timeframe, period)

    /**
     * Корреляционный фильтр: запрещает открытие позиции, если корреляция
     * с любой открытой позицией превышает [correlationThreshold].
     *
     * При недостатке данных (менее 30 свечей в кэше) фильтр пропускает сделку.
     *
     * @param candidateTicker тикер входа
     * @param openPositions открытые позиции
     * @return true, если вход запрещён по корреляции
     */
    fun exceedsCorrelationLimit(
        candidateTicker: String,
        openPositions: List<Position>,
    ): Boolean {
        if (candidateTicker == "Si") return false // фьючерсный хедж не фильтруется
        val blocked =
            openPositions.any { pos ->
                if (pos.ticker == candidateTicker || pos.ticker == "Si") return@any false
                (correlationOf(candidateTicker, pos.ticker) ?: 0.0) > correlationThreshold
            }
        if (blocked) {
            meterRegistry.counter("adaptive.correlation.blocked", Tags.of("ticker", candidateTicker)).increment()
        }
        return blocked
    }

    /**
     * Секторный корреляционный фильтр: запрещает вторую позицию в том же секторе,
     * если корреляция с уже открытой позицией превышает riskConfig.maxSectorCorrelation (0.7 = 70%).
     *
     * В отличие от [exceedsCorrelationLimit] (глобальный порог 0.8) этот фильтр учитывает
     * корреляцию ТОЛЬКО внутри одного сектора — защита от концентрированных коррелированных движений.
     *
     * @param candidateTicker тикер входа
     * @param openPositions открытые позиции
     * @return true, если вход запрещён по внутрисекторной корреляции
     */
    suspend fun exceedsSectorCorrelationLimit(
        candidateTicker: String,
        openPositions: List<Position>,
    ): Boolean {
        if (candidateTicker == "Si") return false // фьючерсный хедж не фильтруется
        val candidateSector = riskConfig.sectors[candidateTicker] ?: return false
        val blocked =
            openPositions.any { pos ->
                if (pos.ticker == candidateTicker || pos.ticker == "Si") return@any false
                val sameSector = riskConfig.sectors[pos.ticker] == candidateSector
                sameSector && (correlationOf(candidateTicker, pos.ticker) ?: 0.0) > riskConfig.maxSectorCorrelation
            }
        if (blocked) {
            meterRegistry
                .counter(
                    "adaptive.sector_correlation.blocked",
                    Tags.of("ticker", candidateTicker),
                ).increment()
        }
        return blocked
    }

    /**
     * Оптимальный размер позиции по критерию Келли для тикера.
     *
     * Порядок расчёта:
     * 1. Kelly (fraction = Quarter/Half) -> базовый размер от AUM ([AumProvider]).
     *    Статистика робастная: win rate заменяется Wilson lower bound (шринкейдж
     *    при малой выборке), минимум сделок [RiskConfig.kellyMinTrades], при
     *    недостатке данных — консервативная доля [RiskConfig.kellyNoDataFraction]
     *    вместо 100% депозита. Кап — [RiskConfig.kellyMaxPositionFraction] (10% от AUM).
     * 2. Volatility targeting (ДНЕВНОЙ горизонт): множитель =
     *    volatilityTargetPercent / dailyVolPercent, где dailyVolPercent — realized
     *    volatility (stddev лог-доходностей по DAY_1) либо дневной эквивалент
     *    ATR (10-мин ATR% * sqrt(свечей в дне)). Высокая волатильность режет
     *    размер, низкая — (в пределах clamp) увеличивает. Без данных — нейтрально.
     * 3. Confidence-aware сайзинг: множитель по уверенности сигнала относительно
     *    адаптивного порога — маржинальный сигнал (confidence = порог) режет размер
     *    до [RiskConfig.confidenceSizingMinFactor], высокая уверенность
     *    (>= [RiskConfig.confidenceSizingCeiling]) — полный размер.
     * 4. Drawdown degradation: непрерывный множитель по глубине просадки от пика
     *    AUM (drawdownScaleTiers) плюс fallback-множитель при серии убытков подряд.
     *
     * @param ticker тикер инструмента
     * @param atr дневной ATR (если null — берётся/масштабируется из кэша свечей)
     * @param currentPrice текущая цена (если null — последнее закрытие из кэша)
     * @param confidence уверенность сигнала (0..1); null — без confidence-сайзинга
     * @return рекомендуемый размер позиции в рублях (0 при невыгодной статистике)
     */
    suspend fun calculateOptimalPositionSize(
        ticker: String,
        atr: BigDecimal? = null,
        currentPrice: BigDecimal? = null,
        confidence: Double? = null,
    ): BigDecimal {
        val aum = aumProvider.currentAum()
        val stats = tradeAnalysisService.analyzeLastNDays(30)[ticker]
        // No-data fallback тоже ограничен жёстким капом: min(noDataFraction, kellyMaxPositionFraction).
        val fallbackFraction = minOf(riskConfig.kellyNoDataFraction, riskConfig.kellyMaxPositionFraction)
        val base =
            if (stats == null || stats.totalTrades < riskConfig.kellyMinTrades) {
                aum.multiply(BigDecimal(fallbackFraction.toString()))
            } else {
                val w = wilsonLowerBound(stats.winRate, stats.totalTrades, riskConfig.kellyWilsonZ)
                val avgLossAbs = kotlin.math.abs(stats.avgLoss.toDouble()).coerceAtLeast(0.01)
                val r = stats.avgWin.toDouble() / avgLossAbs
                val kelly = (w * r - (1 - w)) / r

                // Дробный (Quarter/Half) Kelly с жёстким капом от депозита.
                val safeKelly =
                    (kelly * riskConfig.kellyFraction)
                        .coerceIn(0.0, riskConfig.kellyMaxPositionFraction)
                if (safeKelly > 0) aum.multiply(BigDecimal(safeKelly)) else BigDecimal.ZERO
            }

        var size = base

        // Volatility targeting: размер обратно пропорционален дневной волатильности.
        val resolvedAtr = atr ?: resolveAtr(ticker)
        val resolvedPrice = currentPrice ?: resolvePrice(ticker)
        val volMultiplier = resolveVolatilityMultiplier(ticker, resolvedAtr, resolvedPrice)
        if (volMultiplier != null) {
            size = size.multiply(BigDecimal(volMultiplier))
        }

        // Confidence-aware сайзинг: маржинальный сигнал (confidence == порог) —
        // минимальный размер, высокая уверенность — полный. Не раздувает baseline.
        val confidenceFactor = confidenceSizingFactor(ticker, confidence)
        size = size.multiply(BigDecimal(confidenceFactor))

        // Drawdown degradation: непрерывный множитель по глубине просадки + серия убытков.
        val drawdownFactor = drawdownScaleMultiplier().coerceAtMost(recoveryReductionFactor())
        size = size.multiply(BigDecimal(drawdownFactor))

        // Market regime: рыночный overlay (RVI: VOLATILE урезает, STRESS обнуляет)
        // × per-ticker режим (RegimeDetector: HIGH урезает, Crash/Pump/THIN/EXTREME
        // обнуляет — страховка на случай, если сигнал прошёл стратегический фильтр).
        val marketRegimeFactor =
            if (riskConfig.marketRegimeEnabled) {
                when (marketRegimeProvider.currentRegime()) {
                    MarketRegime.VOLATILE -> riskConfig.regimeVolatileSizeMultiplier
                    MarketRegime.STRESS -> 0.0
                    MarketRegime.LOW, MarketRegime.NORMAL -> 1.0
                }
            } else {
                1.0
            }
        val perTickerRegimeFactor = if (riskConfig.perTickerRegimeEnabled) perTickerRegimeSizeMultiplier(ticker) else 1.0
        val regimeFactor = marketRegimeFactor * perTickerRegimeFactor
        size = size.multiply(BigDecimal(regimeFactor))

        val finalSize = size.coerceAtLeast(BigDecimal.ZERO)
        meterRegistry.gauge("adaptive.position_size", Tags.of("ticker", ticker), finalSize.toDouble())
        logger.info {
            "Kelly size for $ticker: ${finalSize.toInt()} (base=$base, volTarget=${volMultiplier ?: "N/A"}, " +
                "confidenceFactor=$confidenceFactor, drawdownFactor=$drawdownFactor, regimeFactor=$regimeFactor)"
        }
        return finalSize
    }

    /**
     * Wilson lower bound для win rate — консервативный шринкейдж при малой выборке.
     *
     * p_lower = (p + z²/2n - z*sqrt((p(1-p) + z²/4n)/n)) / (1 + z²/n)
     *
     * Защита от «галлюцинирующего» Kelly: win rate из 5-15 сделок завышен,
     * нижняя граница интервала приближает его к 50% при n -> мал.
     *
     * @param p сырой win rate (0..1)
     * @param n количество сделок
     * @param z z-score (1.0 = ~84% односторонний интервал)
     * @return нижняя граница Wilson-интервала (0..1)
     */
    private fun wilsonLowerBound(
        p: Double,
        n: Int,
        z: Double,
    ): Double {
        if (n <= 0) return 0.0
        val pNorm = p.coerceIn(0.0, 1.0)
        val z2 = z * z
        val center = pNorm + z2 / (2 * n)
        val margin = z * sqrt((pNorm * (1 - pNorm) + z2 / (4 * n)) / n)
        return (center - margin) / (1 + z2 / n)
    }

    /**
     * Множитель volatility targeting по дневной волатильности.
     *
     * 1. Явные atr/price (например из теста или дневного ATR): множитель =
     *    target / atrPercent, где atrPercent = atr/price*100.
     * 2. Иначе дневная realized volatility (DAY_1 свечи).
     * 3. Fallback: внутридневная волатильность, масштабированная к дневной
     *    sqrt(свечей в сессии).
     * 4. Нет данных -> null (нейтрально, позиция не раздувается).
     *
     * @return множитель или null, если волатильность неизвестна
     */
    private fun resolveVolatilityMultiplier(
        ticker: String,
        atr: BigDecimal?,
        currentPrice: BigDecimal?,
    ): Double? {
        if (atr != null && atr > BigDecimal.ZERO && currentPrice != null && currentPrice > BigDecimal.ZERO) {
            val atrPercent =
                atr
                    .multiply(BigDecimal("100"))
                    .divide(currentPrice, 4, RoundingMode.HALF_UP)
                    .toDouble()
            return (riskConfig.volatilityTargetPercent / atrPercent)
                .coerceIn(riskConfig.minVolatilitySizeMultiplier, riskConfig.maxVolatilitySizeMultiplier)
        }

        val dailyVolPercent = resolveDailyVolPercent(ticker)
        if (dailyVolPercent != null && dailyVolPercent > 0.0) {
            return (riskConfig.volatilityTargetPercent / dailyVolPercent)
                .coerceIn(riskConfig.minVolatilitySizeMultiplier, riskConfig.maxVolatilitySizeMultiplier)
        }
        return null
    }

    /**
     * Множитель размера позиции по уверенности сигнала (roadmap 13.11.9).
     *
     * Линейная интерполяция между [RiskConfig.confidenceSizingMinFactor] (при
     * confidence == адаптивный порог тикера) и [RiskConfig.confidenceSizingMaxFactor]
     * (при confidence >= [RiskConfig.confidenceSizingCeiling]). Множитель только
     * урезает размер относительно baseline (max factor = 1.0) и никогда не раздувает
     * его. При confidence == null (API/нет сигнала) или выключенном сайте возвращается
     * 1.0 (нейтрально, поведение прежнее).
     *
     * @param ticker тикер инструмента
     * @param confidence уверенность сигнала (0..1) или null
     * @return множитель размера (0..1)
     */
    private suspend fun confidenceSizingFactor(
        ticker: String,
        confidence: Double?,
    ): Double {
        if (!riskConfig.confidenceSizingEnabled || confidence == null) return 1.0
        val normalized = confidence.coerceIn(0.0, 1.0)
        val threshold = getAdaptiveConfidenceThreshold(ticker)
        val span = riskConfig.confidenceSizingCeiling - threshold
        // Порог близко к ceiling (строгая калибровка) — любой прошедший сигнал полный.
        if (span <= 1e-9) {
            meterRegistry.gauge("adaptive.confidence_factor", Tags.of("ticker", ticker), 1.0)
            return riskConfig.confidenceSizingMaxFactor
        }
        val t = ((normalized - threshold) / span).coerceIn(0.0, 1.0)
        val factor =
            riskConfig.confidenceSizingMinFactor +
                (riskConfig.confidenceSizingMaxFactor - riskConfig.confidenceSizingMinFactor) * t
        val clamped = factor.coerceIn(0.0, 1.0)
        meterRegistry.gauge("adaptive.confidence_factor", Tags.of("ticker", ticker), clamped)
        return clamped
    }

    /**
     * Дневная волатильность в % (realized vol по DAY_1 свечам).
     *
     * Fallback при отсутствии дневных свечей: внутридневная волатильность
     * (ATR% или realized vol с MINUTE_10), масштабированная к дневному горизонту
     * sqrt(свечей в сессии). null — если данных нет вовсе.
     */
    private fun resolveDailyVolPercent(ticker: String): Double? {
        candleCache.calculateRealizedVolatility(ticker, "DAY_1", riskConfig.volatilityLookbackDays)?.let { return it }

        val n = riskConfig.volatilityFallbackCandlesPerDay.coerceAtLeast(1)
        val atr = resolveAtr(ticker)
        val price = resolvePrice(ticker)
        if (atr != null && price != null && atr > BigDecimal.ZERO && price > BigDecimal.ZERO) {
            val atrPercent =
                atr
                    .multiply(BigDecimal("100"))
                    .divide(price, 4, RoundingMode.HALF_UP)
                    .toDouble()
            return atrPercent * sqrt(n.toDouble())
        }
        val intradayVol = candleCache.calculateRealizedVolatility(ticker, "MINUTE_10", riskConfig.volatilityLookbackDays)
        if (intradayVol != null) {
            return intradayVol * sqrt(n.toDouble())
        }
        return null
    }

    /**
     * Множитель Kelly по глубине просадки от пика AUM (непрерывная деградация).
     *
     * Берётся ближайший не превышающий просадку tier из [RiskConfig.drawdownScaleTiers]:
     * просадка 0% -> 1.0, 3% -> 0.75, 6% -> 0.5, 10% -> 0.25, 15% -> 0.0.
     */
    private fun drawdownScaleMultiplier(): Double {
        val drawdownPercent = drawdownProtection.cachedOrNeutral().drawdownPercent
        var factor = 1.0
        for ((tier, scale) in riskConfig.drawdownScaleTiers) {
            if (drawdownPercent >= tier) factor = scale
        }
        return factor.coerceIn(0.0, 1.0)
    }

    /**
     * Fallback-множитель при drawdown-recovery: серия убыточных сделок подряд
     * режет размер на [RiskConfig.kellyDrawdownReduction].
     */
    private suspend fun recoveryReductionFactor(): Double = if (isInDrawdownRecovery()) riskConfig.kellyDrawdownReduction else 1.0

    /**
     * Текущее ATR тикера из кэша свечей (MINUTE_10), иначе null.
     *
     * @param ticker тикер инструмента
     * @return ATR или null при недостатке данных
     */
    private fun resolveAtr(ticker: String): BigDecimal? = candleCache.calculateAtr(ticker, "MINUTE_10", 14)

    /**
     * Текущая цена тикера (последнее закрытие из кэша MINUTE_10), иначе null.
     *
     * @param ticker тикер инструмента
     * @return последняя цена закрытия или null
     */
    private fun resolvePrice(ticker: String): BigDecimal? = candleCache.getRecentCandles(ticker, "MINUTE_10", 1).lastOrNull()?.closePrice

    /**
     * Множитель размера позиции по per-ticker рыночному режиму (MINUTE_10).
     *
     * @param ticker тикер инструмента
     * @return [PerTickerRegime.sizeMultiplier] или 1.0 при недостатке данных
     */
    private fun perTickerRegimeSizeMultiplier(ticker: String): Double {
        val candles = candleCache.getRecentCandles(ticker, "MINUTE_10", 200)
        if (candles.size < riskConfig.regimeMinBars) return 1.0
        val regime = RegimeDetector.detect(candles, riskConfig.toRegimeDetectionConfig())
        return regime.sizeMultiplier()
    }

    /**
     * Адаптивный порог уверенности для арбитра по тикеру.
     *
     * Онлайн-калибровка (roadmap 13.11.8): по закрытым сделкам тикера за
     * [RiskConfig.confidenceCalibrationDays] и уверенности стратега на входе
     * (agent_logs) подбирается самая низкая граница уверенности, при которой выборка
     * `confidence >= c` даёт win rate >= [RiskConfig.confidenceCalibrationTargetWinRate]
     * (см. [ConfidenceCalibrator]). При недостатке данных — fallback на правила по
     * win rate (0.55 при сильной статистике, 0.80 при слабой).
     *
     * @param ticker тикер инструмента
     * @return порог уверенности
     */
    suspend fun getAdaptiveConfidenceThreshold(ticker: String): Double {
        val calibrated = calibrateConfidenceThreshold(ticker)
        if (calibrated != null) {
            meterRegistry.gauge("adaptive.confidence_threshold", Tags.of("ticker", ticker), calibrated)
            meterRegistry.counter("adaptive.confidence_calibrated", Tags.of("ticker", ticker)).increment()
            return calibrated
        }
        val stats = tradeAnalysisService.analyzeLastNDays(14)[ticker]
        val fallback =
            when {
                stats == null -> 0.60
                stats.winRate < 0.35 -> 0.80
                stats.winRate < 0.45 -> 0.70
                stats.winRate > 0.60 -> 0.55
                else -> 0.60
            }
        meterRegistry.gauge("adaptive.confidence_threshold", Tags.of("ticker", ticker), fallback)
        meterRegistry.counter("adaptive.confidence_fallback", Tags.of("ticker", ticker)).increment()
        return fallback
    }

    /**
     * Калибровка порога уверенности по фактическим исходам тикера.
     *
     * Закрытые позиции за окно калибровки джойнятся с уверенностью стратега на входе
     * (agent_logs, agent Agent-3-Strategist по cycleId). Позиции без cycleId или без
     * лога стратега (детерминированные стратегии) в выборку не попадают. Возвращает null,
     * если калибровка выключена, данных недостаточно или ни одна граница не даёт целевой
     * win rate.
     */
    private suspend fun calibrateConfidenceThreshold(ticker: String): Double? {
        if (!riskConfig.confidenceCalibrationEnabled) return null
        val since = LocalDateTime.now().minusDays(riskConfig.confidenceCalibrationDays.toLong())
        val closedWithOutcome =
            positionRepo
                .findClosedByTickerSince(ticker, since)
                .filter { it.cycleId != null && it.pnl != null }
                .mapNotNull { pos ->
                    val cycleId = pos.cycleId ?: return@mapNotNull null
                    val pnl = pos.pnl ?: return@mapNotNull null
                    cycleId to pnl
                }
        if (closedWithOutcome.size < riskConfig.confidenceCalibrationMinTrades) return null
        val confidenceByCycleId =
            agentLogRepository.findStrategyConfidenceByCycleIds(closedWithOutcome.map { it.first })
        if (confidenceByCycleId.isEmpty()) return null
        return ConfidenceCalibrator
            .calibrate(
                outcomes =
                    closedWithOutcome.mapNotNull { (cycleId, pnl) ->
                        val confidence = confidenceByCycleId[cycleId] ?: return@mapNotNull null
                        confidence to (pnl > BigDecimal.ZERO)
                    },
                targetWinRate = riskConfig.confidenceCalibrationTargetWinRate,
                minTrades = riskConfig.confidenceCalibrationMinTrades,
                minThreshold = riskConfig.confidenceCalibrationMinThreshold,
                maxThreshold = riskConfig.confidenceCalibrationMaxThreshold,
                step = riskConfig.confidenceCalibrationStep,
            )?.threshold
    }

    /**
     * Проверяет, находится ли бот в режиме восстановления после просадки.
     *
     * @return true, если за последние 3 дня было >= 3 убыточных сделок подряд
     */
    suspend fun isInDrawdownRecovery(): Boolean {
        val recent = positionRepo.findClosedSince(LocalDateTime.now().minusDays(3))
        val consecutiveLosses =
            recent
                .reversed()
                .takeWhile {
                    (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO
                }.count()
        val result = consecutiveLosses >= 3
        meterRegistry.gauge("adaptive.drawdown_recovery", if (result) 1.0 else 0.0)
        return result
    }

    /**
     * Проверяет, стоит ли приостановить торговлю по тикеру.
     *
     * @param ticker тикер инструмента
     * @return true при серии >= 4 убытков или очень низком profit factor
     */
    suspend fun shouldPauseTrading(ticker: String): Boolean {
        val stats = tradeAnalysisService.analyzeLastNDays(7)[ticker]
        val result =
            when {
                stats == null -> false
                stats.maxConsecutiveLosses >= 4 -> true
                stats.profitFactor in 0.0..0.5 && stats.totalTrades >= 5 -> true
                else -> false
            }
        meterRegistry.gauge("adaptive.pause", Tags.of("ticker", ticker), if (result) 1.0 else 0.0)
        return result
    }
}
