package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Единый источник AUM (активы под управлением) для всех риск-расчётов.
 *
 * Раньше размер позиций, лимиты exposure и просадки считались от константы
 * [RiskConfig.maxPositionRub]. Теперь база — реальный баланс портфеля из
 * [AlorFuturesClient.getPortfolioMoney] (LIVE), который кэшируется на
 * [CACHE_TTL_MS] и служит актуальным депозитом для Kelly, Gross/Net exposure
 * и Multi-Tier drawdown лимитов.
 *
 * Multi-account (roadmap v2.2): AUM считается по аккаунту:
 * - accountId = null → legacy single-account (портфель из AlorConfig.portfolio);
 * - персональное переопределение [TradingAccountService.aumRubOverrideFor] —
 *   фиксированный депозит аккаунта без обращения к бирже;
 * - иначе реальный баланс портфеля аккаунта [TradingAccountService.portfolioOf].
 *
 * Фолбэк (SIMULATION / ошибка API / нулевой баланс): [RiskConfig.maxPositionRub].
 * Синхронные горячие пути ([latestAum]) используют последнее кэшированное
 * значение без сетевых вызовов; асинхронные циклы обновляют кэш через [currentAum].
 */
@Service
class AumProvider(
    private val alorFuturesClient: AlorFuturesClient,
    private val riskConfig: RiskConfig,
    private val tradingAccountService: TradingAccountService,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    private class CacheEntry(
        @Volatile var aum: BigDecimal,
        @Volatile var updatedAt: Long,
    )

    /** Кэш legacy (accountId = null) и per-account кэши. ConcurrentHashMap не допускает
     *  null-ключей — legacy аккаунт нормализуется в [NULL_ACCOUNT]. */
    private val cache: ConcurrentHashMap<Long, CacheEntry> = ConcurrentHashMap()

    private fun key(accountId: Long?): Long = accountId ?: NULL_ACCOUNT

    /**
     * Текущий AUM (кэшированный) для аккаунта. При устаревшем кэше — перечитывает
     * баланс из Alor (или берёт персональное переопределение аккаунта).
     *
     * @return AUM в рублях (всегда > 0: нулевой/негативный результат не принимается)
     */
    suspend fun currentAum(accountId: Long? = null): BigDecimal {
        val k = key(accountId)
        val entry = cache.computeIfAbsent(k) { CacheEntry(riskConfig.maxPositionRub, 0) }
        val cached = entry.aum
        if (System.currentTimeMillis() - entry.updatedAt < CACHE_TTL_MS) {
            return cached
        }
        return try {
            val override = tradingAccountService.aumRubOverrideFor(accountId)
            val effective =
                if (override != null) {
                    override
                } else {
                    val money = alorFuturesClient.getPortfolioMoney(tradingAccountService.portfolioOf(accountId))
                    if (money != null && money > BigDecimal.ZERO) money else riskConfig.maxPositionRub
                }
            cache[k] = CacheEntry(effective, System.currentTimeMillis())
            meterRegistry.gauge(
                "portfolio.aum",
                io.micrometer.core.instrument.Tags
                    .of("account", accountId?.toString() ?: "default"),
                effective.toDouble(),
            )
            effective
        } catch (e: Exception) {
            logger.warn(e) { "AUM fetch failed for accountId=$accountId, using config fallback ${riskConfig.maxPositionRub}" }
            riskConfig.maxPositionRub
        }
    }

    /**
     * Последнее кэшированное значение AUM без сетевых вызовов (для синхронных
     * горячих проверок входа). До первого обновления — конфигурационный депозит.
     */
    fun latestAum(accountId: Long? = null): BigDecimal = cache[key(accountId)]?.aum ?: riskConfig.maxPositionRub

    companion object {
        /** Ключ кэша legacy single-account (accountId = null). */
        private const val NULL_ACCOUNT: Long = -1L

        /** TTL кэша баланса Alor, мс. 60с = не дёргать API на каждый тик/проверку. */
        private const val CACHE_TTL_MS = 60_000L
    }
}
