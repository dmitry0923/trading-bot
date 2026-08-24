package com.trading.bot.service

import com.trading.bot.backtest.KellyMath
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.ConfidenceCalibrator
import com.trading.bot.domain.risk.MarketRegime
import com.trading.bot.domain.risk.MarketRegimeProvider
import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDetector
import com.trading.bot.infrastructure.metrics.MutableGauges
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
     * 3. Signal-strength-aware сайзинг: множитель по силе сигнала относительно
     *    адаптивного порога — маржинальный сигнал (signalStrength = порог) режет размер
     *    до [RiskConfig.confidenceSizingMinFactor], высокая уверенность
     *    (>= [RiskConfig.confidenceSizingCeiling]) — полный размер.
     * 4. Drawdown degradation: непрерывный множитель по глубине просадки от пика
     *    AUM (drawdownScaleTiers) плюс fallback-множитель при серии убытков подряд.
     *
     * @param ticker тикер инструмента
     * @param atr дневной ATR (если null — берётся/масштабируется из кэша свечей)
     * @param currentPrice текущая цена (если null — последнее закрытие из кэша)
     * @param signalStrength сила сигнала (0..1); null — без confidence-сайзинга
     * @param accountId аккаунт (F-12, roadmap 13.25): AUM берётся по аккаунту,
     *   а не глобально; null = legacy single-account
     * @return рекомендуемый размер позиции в рублях (0 при невыгодной статистике)
     */
    suspend fun calculateOptimalPositionSize(
        ticker: String,
        atr: BigDecimal? = null,
        currentPrice: BigDecimal? = null,
        signalStrength: Double? = null,
        accountId: Long? = null,
    ): BigDecimal {
        val aum = aumProvider.currentAum(accountId)
        val stats = tradeAnalysisService.analyzeLastNDays(30, accountId)[ticker]
        val totalTrades = stats?.totalTrades ?: 0
        val sampleMultiplier = sampleSizeMultiplier(totalTrades)
        val base =
            if (stats == null) {
                // Совсем нет данных: используем жёсткий fallback, ограниченный капом.
                val fallbackFraction = minOf(riskConfig.kellyNoDataFraction, riskConfig.kellyMaxPositionFraction)
                aum.multiply(BigDecimal(fallbackFraction.toString()))
            } else {
                val w = wilsonLowerBound(stats.winRate, stats.totalTrades, riskConfig.kellyWilsonZ)
                val avgLossAbs = kotlin.math.abs(stats.avgLoss.toDouble()).coerceAtLeast(0.01)
                val r = stats.avgWin.toDouble() / avgLossAbs
                val kelly = (w * r - (1 - w)) / r

                // Staged Kelly: sampleMultiplier масштабирует kellyFraction по размеру выборки.
                // 0-4 сделки: ×0.20, 5-14: ×0.50, 15-29: ×0.80, 30+: ×1.00.
                val effectiveFraction = riskConfig.kellyFraction * sampleMultiplier
                val safeKelly =
                    (kelly * effectiveFraction)
                        .coerceIn(0.0, riskConfig.kellyMaxPositionFraction)
                if (safeKelly > 0) aum.multiply(BigDecimal(safeKelly)) else BigDecimal.ZERO
            }

        var size = base

        // Volatility targeting: размер обратно пропорционален дневной волатильности.
        val resolvedAtr = atr ?: resolveDailyAtr(ticker)
        val resolvedPrice = currentPrice ?: resolvePrice(ticker)
        val volMultiplier = resolveVolatilityMultiplier(ticker, resolvedAtr, resolvedPrice)
        if (volMultiplier != null) {
            size = size.multiply(BigDecimal(volMultiplier))
        }

        // Signal-strength-aware сайзинг: маржинальный сигнал (signalStrength == порог) —
        // минимальный размер, высокая уверенность — полный. Не раздувает baseline.
        val confidenceFactor = confidenceSizingFactor(ticker, signalStrength)
        size = size.multiply(BigDecimal(confidenceFactor))

        // Drawdown degradation: непрерывный множитель по глубине просадки + серия убытков.
        // (F-13, roadmap 13.25.6) Множитель считается по статусу аккаунта, а не глобальному.
        val drawdownFactor = drawdownScaleMultiplier(accountId).coerceAtMost(recoveryReductionFactor(accountId))
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
        MutableGauges.set(meterRegistry, "adaptive.position_size", finalSize.toDouble(), Tags.of("ticker", ticker))
        logger.info {
            "Kelly size for $ticker: ${finalSize.toInt()} (base=$base, volTarget=${volMultiplier ?: "N/A"}, " +
                "confidenceFactor=$confidenceFactor, drawdownFactor=$drawdownFactor, regimeFactor=$regimeFactor)"
        }
        return finalSize
    }

    /**
     * Множитель Kelly fraction по размеру выборки (P1#8).
     * Плавная шкала вместо бинарного порога kellyMinTrades:
     *   0-4 сделки:   ×0.20 (очень консервативно, «цифровой лото-билет»)
     *   5-14 сделок:  ×0.50 (осторожный рост)
     *   15-29 сделок: ×0.80 (почти полный Kelly)
     *   30+ сделок:   ×1.00 (полный Kelly)
     *
     * Wilson lower bound уже шринкает win rate, а staging дополнительно
     * режет размер на малых выборках — двойная защита от overfitting.
     */
    fun sampleSizeMultiplier(totalTrades: Int): Double = KellyMath.sampleSizeMultiplier(totalTrades, riskConfig.kellySampleSizeTiers)

    /**
     * Ожидаемый чистый прибыль на 1 лот (RUB), рассчитанный из статистики сделок.
     * Уже включает комиссию (PnlCalculator вычитает её из realized P&L).
     *
     * expectedNet = Wilson(winRate) × avgWin − (1 − Wilson(winRate)) × avgLoss
     *
     * @return чистый прибыль на лот, или null при недостатке статистики.
     */
    suspend fun expectedNetProfitPerLot(
        ticker: String,
        accountId: Long? = null,
    ): BigDecimal? {
        val stats = tradeAnalysisService.analyzeLastNDays(30, accountId)[ticker] ?: return null
        if (stats.totalTrades < riskConfig.kellyMinTrades) return null
        if (stats.avgWin <= BigDecimal.ZERO && stats.avgLoss <= BigDecimal.ZERO) return null

        val w = wilsonLowerBound(stats.winRate, stats.totalTrades, riskConfig.kellyWilsonZ)
        val wBd = BigDecimal(w)
        val oneMinusW = BigDecimal(1 - w)

        // avgWin и avgLoss — положительные величины (средний выигрыш/проигрыш в RUB).
        // PnlCalculator уже вычел комиссию из realized P&L.
        val expectedNetProfit =
            wBd
                .multiply(stats.avgWin)
                .subtract(oneMinusW.multiply(stats.avgLoss))
        return expectedNetProfit
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
    ): Double = KellyMath.wilsonLowerBound(p, n, z)

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
     * Множитель размера позиции по силе сигнала (roadmap 13.11.9).
     *
     * Линейная интерполяция между [RiskConfig.confidenceSizingMinFactor] (при
     * signalStrength == адаптивный порог тикера) и [RiskConfig.confidenceSizingMaxFactor]
     * (при signalStrength >= [RiskConfig.confidenceSizingCeiling]). Множитель только
     * урезает размер относительно baseline (max factor = 1.0) и никогда не раздувает
     * его. При signalStrength == null (API/нет сигнала) или выключенном сайте
     * возвращается 1.0 (нейтрально, поведение прежнее).
     *
     * @param ticker тикер инструмента
     * @param signalStrength сила сигнала (0..1) или null
     * @return множитель размера (0..1)
     */
    private suspend fun confidenceSizingFactor(
        ticker: String,
        signalStrength: Double?,
    ): Double {
        if (!riskConfig.confidenceSizingEnabled || signalStrength == null) return 1.0
        val normalized = signalStrength.coerceIn(0.0, 1.0)
        val threshold = getAdaptiveConfidenceThreshold(ticker)
        val span = riskConfig.confidenceSizingCeiling - threshold
        // Порог близко к ceiling (строгая калибровка) — любой прошедший сигнал полный.
        if (span <= 1e-9) {
            MutableGauges.set(meterRegistry, "adaptive.confidence_factor", 1.0, Tags.of("ticker", ticker))
            return riskConfig.confidenceSizingMaxFactor
        }
        val t = ((normalized - threshold) / span).coerceIn(0.0, 1.0)
        val factor =
            riskConfig.confidenceSizingMinFactor +
                (riskConfig.confidenceSizingMaxFactor - riskConfig.confidenceSizingMinFactor) * t
        val clamped = factor.coerceIn(0.0, 1.0)
        MutableGauges.set(meterRegistry, "adaptive.confidence_factor", clamped, Tags.of("ticker", ticker))
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
    private fun drawdownScaleMultiplier(accountId: Long? = null): Double {
        val drawdownPercent = drawdownProtection.cachedOrNeutral(accountId).drawdownPercent
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
    private suspend fun recoveryReductionFactor(accountId: Long? = null): Double =
        if (isInDrawdownRecovery(accountId)) riskConfig.kellyDrawdownReduction else 1.0

    /**
     * Текущее ATR тикера из кэша свечей (MINUTE_10), иначе null.
     *
     * @param ticker тикер инструмента
     * @return ATR или null при недостатке данных
     */
    private fun resolveAtr(ticker: String): BigDecimal? = candleCache.calculateAtr(ticker, "MINUTE_10", 14)

    /**
     * Дневной эквивалент ATR тикера из кэша свечей (MINUTE_10), иначе null.
     *
     * Внутридневной ATR масштабируется к дневному горизонту sqrt(свечей в сессии)
     * ([RiskConfig.volatilityFallbackCandlesPerDay]) — единая математика с
     * [resolveDailyVolPercent] и документированным volatility targeting: волатильность
     * растёт как sqrt(t), 10-мин ATR% нельзя сравнивать с дневным таргетом напрямую.
     *
     * @param ticker тикер инструмента
     * @return дневной эквивалент ATR или null при недостатке данных
     */
    private fun resolveDailyAtr(ticker: String): BigDecimal? {
        val atr = resolveAtr(ticker) ?: return null
        val n = riskConfig.volatilityFallbackCandlesPerDay.coerceAtLeast(1).toDouble()
        return atr.multiply(BigDecimal(sqrt(n).toString()))
    }

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
            MutableGauges.set(meterRegistry, "adaptive.confidence_threshold", calibrated, Tags.of("ticker", ticker))
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
        MutableGauges.set(meterRegistry, "adaptive.confidence_threshold", fallback, Tags.of("ticker", ticker))
        meterRegistry.counter("adaptive.confidence_fallback", Tags.of("ticker", ticker)).increment()
        return fallback
    }

    /**
     * Калибровка порога уверенности по фактическим исходам тикера.
     *
     * Закрытые позиции за окно калибровки джойнятся с силой сигнала стратега на входе
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
            agentLogRepository.findStrategySignalStrengthByCycleIds(ticker, closedWithOutcome.map { it.first })
        if (confidenceByCycleId.isEmpty()) return null
        return ConfidenceCalibrator
            .calibrate(
                outcomes =
                    closedWithOutcome.mapNotNull { (cycleId, pnl) ->
                        val signalStrength = confidenceByCycleId[cycleId] ?: return@mapNotNull null
                        signalStrength to (pnl > BigDecimal.ZERO)
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
     * Считается серия убыточных сделок подряд, считая от ПОСЛЕДНЕГО закрытия
     * (findClosedByAccountSince уже возвращает newest-first, поэтому список НЕ
     * разворачивается — разворот привёл бы к подсчёту серии от САМОЙ СТАРОЙ сделки окна).
     *
     * (F-1, roadmap 13.25.6) Оконный запрос скоупирован по [accountId] — серия сделок
     * одного аккаунта не считается по общему пулу.
     *
     * @return true, если за последние 3 дня было >= 3 убыточных сделок подряд
     */
    suspend fun isInDrawdownRecovery(accountId: Long? = null): Boolean {
        val recent = positionRepo.findClosedByAccountSince(accountId, LocalDateTime.now().minusDays(3))
        val consecutiveLosses =
            recent
                .takeWhile {
                    (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO
                }.count()
        val result = consecutiveLosses >= 3
        MutableGauges.set(meterRegistry, "adaptive.drawdown_recovery", if (result) 1.0 else 0.0)
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
        MutableGauges.set(meterRegistry, "adaptive.pause", if (result) 1.0 else 0.0, Tags.of("ticker", ticker))
        return result
    }
}
