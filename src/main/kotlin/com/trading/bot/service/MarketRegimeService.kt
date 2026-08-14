package com.trading.bot.service

import com.trading.bot.client.MoexClient
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.MarketRegime
import com.trading.bot.domain.risk.MarketRegimeClassifier
import com.trading.bot.domain.risk.MarketRegimeProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Движок рыночного режима волатильности (Volatility Engine 2.0).
 *
 * Определяет режим рынка по перцентильному рангу текущей волатильности
 * относительно исторического распределения индекса MOEX (RVI):
 *   < p40 → LOW, < p70 → NORMAL, < p90 → VOLATILE, >= p90 → STRESS.
 *
 * Текущим значением служит RVI; при его недоступности — подразумеваемая
 * волатильность Si (ImpliedVolatilityService). Если история слишком мала или
 * нет ни одного источника — режим NORMAL (fail-safe, не блокирует торговлю).
 *
 * Реализует [MarketRegimeProvider]: STRESS блокирует входы в FuturesRiskEngine,
 * VOLATILE урезает размер позиции в AdaptiveRiskService.
 */
@Service
class MarketRegimeService(
    private val riskConfig: RiskConfig,
    private val moexClient: MoexClient,
    private val impliedVolatilityService: ImpliedVolatilityService,
    private val meterRegistry: MeterRegistry,
) : MarketRegimeProvider {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @PreDestroy
    fun close() {
        scope.cancel()
    }

    private val cacheTtl: Duration = Duration.ofMinutes(15)

    @Volatile
    private var regime: MarketRegime = MarketRegime.NORMAL

    @Volatile
    private var lastFetchedAt: Instant? = null

    /** Текущий режим волатильности (NORMAL, пока не был ни один refresh). */
    override fun currentRegime(): MarketRegime = regime

    /** Стрессовый ли рынок (новые входы запрещены). */
    fun isStress(): Boolean = regime == MarketRegime.STRESS

    /**
     * Множитель размера позиции по режиму: VOLATILE — [RiskConfig.regimeVolatileSizeMultiplier],
     * STRESS — 0 (страховка, входы и так блокируются риск-движком), иначе 1.
     */
    fun sizeMultiplier(): Double =
        when (regime) {
            MarketRegime.VOLATILE -> riskConfig.regimeVolatileSizeMultiplier
            MarketRegime.STRESS -> 0.0
            MarketRegime.LOW, MarketRegime.NORMAL -> 1.0
        }

    /**
     * Обновляет режим рынка (не чаще [cacheTtl]).
     */
    suspend fun refresh() {
        val lastFetch = lastFetchedAt
        if (lastFetch != null && Duration.between(lastFetch, Instant.now()) < cacheTtl) return
        lastFetchedAt = Instant.now()
        if (!riskConfig.marketRegimeEnabled) {
            regime = MarketRegime.NORMAL
            return
        }

        val history =
            moexClient.getVolatilityIndexDailyCloses(
                riskConfig.volatilityIndexTicker,
                LocalDate.now().minusDays(riskConfig.regimeLookbackDays.toLong()),
            )
        val current = currentMetric()
        val newRegime =
            if (history.size < riskConfig.regimeMinHistorySamples || current == null) {
                MarketRegime.NORMAL
            } else {
                MarketRegimeClassifier.classify(
                    history = history,
                    current = current,
                    pLow = riskConfig.regimePercentileLow,
                    pNormal = riskConfig.regimePercentileNormal,
                    pVolatile = riskConfig.regimePercentileVolatile,
                ) ?: MarketRegime.NORMAL
            }
        regime = newRegime

        meterRegistry.gauge("risk.market.regime.stress", if (newRegime == MarketRegime.STRESS) 1.0 else 0.0)
        meterRegistry.gauge("risk.market.regime.level", newRegime.ordinal.toDouble())
        logger.info {
            "Market regime: $newRegime (history=${history.size} samples, current=${current ?: "N/A"})"
        }
    }

    /**
     * Текущее значение волатильности: RVI, при недоступности — IV Si.
     */
    private suspend fun currentMetric(): Double? {
        val rvi = moexClient.getVolatilityIndex(riskConfig.volatilityIndexTicker)
        if (rvi != null && rvi > BigDecimal.ZERO) return rvi.toDouble()
        return impliedVolatilityService.impliedVolatilityPercent()
    }

    /**
     * Фоновая подкачка режима рынка.
     */
    @Scheduled(fixedDelay = 900_000)
    fun scheduledRefresh() {
        scope.launch {
            try {
                refresh()
            } catch (e: Exception) {
                logger.warn(e) { "Market regime refresh failed" }
            }
        }
    }
}
