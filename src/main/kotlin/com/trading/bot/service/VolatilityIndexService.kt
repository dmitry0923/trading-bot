package com.trading.bot.service

import com.trading.bot.client.MoexClient
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.VolatilityFilter
import com.trading.bot.infrastructure.metrics.MutableGauges
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
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

    @PreDestroy
    fun close() {
        scope.cancel()
    }

    private val cacheTtl: Duration = Duration.ofMinutes(15)

    @Volatile
    private var lastValue: BigDecimal? = null

    @Volatile
    private var lastFetchedAt: Instant? = null

    /** Момент последнего УСПЕШНОГО значения (для проверки устаревания). */
    @Volatile
    private var lastValueAt: Instant? = null

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
            lastValueAt = Instant.now()
            MutableGauges.set(meterRegistry, "risk.volatility.index", value.toDouble())
        } else {
            // Значение не обновилось: lastValueAt намеренно НЕ двигаем, иначе устаревшее
            // значение считалось бы свежим (скан-15 P2).
            logger.warn { "Volatility index unavailable; keeping last known value=$lastValue" }
            meterRegistry.counter("risk.volatility.index.fetch_failed").increment()
        }
    }

    /**
     * Аномален ли текущий уровень индекса волатильности (торговля на паузе).
     *
     * Fail-closed (решение пользователя 2026-10-28): индекс недоступен или его значение
     * старше [RiskConfig.volatilityIndexTtlMinutes] → считаем условие аномальным и
     * блокируем входы. Раньше отсутствие данных молча выключало фильтр (fail-open).
     */
    override fun isVolatilityAnomalous(): Boolean {
        if (!riskConfig.volatilityIndexEnabled) return false
        val value = lastValue
        val valueAt = lastValueAt
        if (value == null || valueAt == null) {
            meterRegistry.counter("risk.volatility.index.stale", Tags.of("cause", "missing")).increment()
            logger.warn {
                "VOLATILITY INDEX UNAVAILABLE: fail-closed = ${riskConfig.volatilityIndexFailClosed}"
            }
            return riskConfig.volatilityIndexFailClosed
        }
        val age = Duration.between(valueAt, Instant.now())
        if (age > Duration.ofMinutes(riskConfig.volatilityIndexTtlMinutes)) {
            meterRegistry.counter("risk.volatility.index.stale", Tags.of("cause", "aged")).increment()
            logger.warn {
                "VOLATILITY INDEX STALE: value=$value age=${age.toMinutes()}min > " +
                    "${riskConfig.volatilityIndexTtlMinutes}min — fail-closed=${riskConfig.volatilityIndexFailClosed}"
            }
            return riskConfig.volatilityIndexFailClosed
        }
        val anomalous = value.toDouble() > riskConfig.maxVolatilityIndexPercent
        MutableGauges.set(meterRegistry, "risk.volatility.anomalous", if (anomalous) 1.0 else 0.0)
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
