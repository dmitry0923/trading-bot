package com.trading.bot.service

import com.trading.bot.client.MoexClient
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.VolatilityFilter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Фильтр волатильности по индексу волатильности MOEX (RVI).
 *
 * RVI отражает ожидаемую волатильность рынка: при аномальном скачке индекса
 * (значение > [RiskConfig.maxVolatilityIndexPercent]) входы в новые позиции
 * ставятся на паузу — в стрессовых условиях стопы пробиваются шумом, а Kelly-сайзинг
 * недооценивает риск. Значение кэшируется (TTL [cacheTtl]) и обновляется фоном.
 */
@Service
class VolatilityIndexService(
    private val riskConfig: RiskConfig,
    private val moexClient: MoexClient,
    private val meterRegistry: MeterRegistry,
) : VolatilityFilter {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheTtl: Duration = Duration.ofMinutes(15)

    @Volatile
    private var lastValue: BigDecimal? = null

    @Volatile
    private var lastFetchedAt: Instant? = null

    /**
     * Обновляет кэш индекса волатильности (не чаще [cacheTtl]).
     */
    suspend fun refresh() {
        val lastFetch = lastFetchedAt
        if (lastFetch != null && Duration.between(lastFetch, Instant.now()) < cacheTtl) return
        val value = moexClient.getVolatilityIndex(riskConfig.volatilityIndexTicker)
        lastFetchedAt = Instant.now()
        if (value != null && value > BigDecimal.ZERO) {
            lastValue = value
            meterRegistry.gauge("risk.volatility.index", value.toDouble())
        } else {
            logger.warn { "Volatility index unavailable; keeping last known value=$lastValue" }
        }
    }

    /**
     * Аномален ли текущий уровень индекса волатильности (торговля на паузе).
     * Если индекс недоступен — фильтр не блокирует (fail-open).
     */
    override fun isVolatilityAnomalous(): Boolean {
        if (!riskConfig.volatilityIndexEnabled) return false
        val value = lastValue ?: return false
        val anomalous = value.toDouble() > riskConfig.maxVolatilityIndexPercent
        meterRegistry.gauge("risk.volatility.anomalous", if (anomalous) 1.0 else 0.0)
        if (anomalous) {
            logger.warn { "VOLATILITY INDEX PAUSE: $value > ${riskConfig.maxVolatilityIndexPercent}%" }
        }
        return anomalous
    }

    /**
     * Фоновая подкачка индекса волатильности.
     */
    @Scheduled(fixedDelay = 900_000)
    fun scheduledRefresh() {
        scope.launch {
            try {
                refresh()
            } catch (e: Exception) {
                logger.warn(e) { "Volatility index refresh failed" }
            }
        }
    }
}
