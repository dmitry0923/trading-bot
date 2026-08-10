package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Единый источник AUM (активы под управлением) для всех риск-расчётов.
 *
 * Раньше размер позиций, лимиты exposure и просадки считались от константы
 * [RiskConfig.maxPositionRub]. Теперь база — реальный баланс портфеля из
 * [AlorFuturesClient.getPortfolioMoney] (LIVE), который кэшируется на
 * [CACHE_TTL_MS] и служит актуальным депозитом для Kelly, Gross/Net exposure
 * и Multi-Tier drawdown лимитов.
 *
 * Фолбэк (SIMULATION / ошибка API / нулевой баланс): [RiskConfig.maxPositionRub].
 * Синхронные горячие пути ([latestAum]) используют последнее кэшированное
 * значение без сетевых вызовов; асинхронные циклы обновляют кэш через [currentAum].
 */
@Service
class AumProvider(
    private val alorFuturesClient: AlorFuturesClient,
    private val riskConfig: RiskConfig,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var cachedAum: BigDecimal? = null

    @Volatile
    private var lastUpdatedAt: Long = 0

    /**
     * Текущий AUM (кэшированный). При устаревшем кэше — перечитывает баланс из Alor.
     *
     * @return AUM в рублях (всегда > 0: нулевой/негативный результат не принимается)
     */
    suspend fun currentAum(): BigDecimal {
        val cached = cachedAum
        if (cached != null && System.currentTimeMillis() - lastUpdatedAt < CACHE_TTL_MS) {
            return cached
        }
        return try {
            val money = alorFuturesClient.getPortfolioMoney()
            val effective = if (money > BigDecimal.ZERO) money else riskConfig.maxPositionRub
            cachedAum = effective
            lastUpdatedAt = System.currentTimeMillis()
            meterRegistry.gauge("portfolio.aum", effective.toDouble())
            effective
        } catch (e: Exception) {
            logger.warn(e) { "AUM fetch failed, using config fallback ${riskConfig.maxPositionRub}" }
            riskConfig.maxPositionRub
        }
    }

    /**
     * Последнее кэшированное значение AUM без сетевых вызовов (для синхронных
     * горячих проверок входа). До первого обновления — конфигурационный депозит.
     */
    fun latestAum(): BigDecimal = cachedAum ?: riskConfig.maxPositionRub

    companion object {
        /** TTL кэша баланса Alor, мс. 60с = не дёргать API на каждый тик/проверку. */
        private const val CACHE_TTL_MS = 60_000L
    }
}
