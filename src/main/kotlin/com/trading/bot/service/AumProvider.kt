package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.infrastructure.metrics.MutableGauges
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
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
 *
 * P1-аудит (fail-closed для LIVE): [currentAumChecked] / [latestAumResult] возвращают
 * [AumResult.Unavailable], когда в LIVE-режиме реальный баланс не определён (ошибка API,
 * null/нулевой баланс, пустой кэш до первого обновления). В SIMULATION-режиме (или при
 * персональном переопределении аккаунта) недоступность не является критичной — там
 * используется конфигурационный депозит [RiskConfig.maxPositionRub], как раньше.
 * Входные/экспозиционные гейты (StockEntryProfile, RiskManagementService) трактуют
 * [AumResult.Unavailable] как DENY (fail-closed), тогда как legacy-методы [currentAum] /
 * [latestAum] для отчётов сохраняют старое fail-open поведение.
 */
@Service
class AumProvider(
    private val alorFuturesClient: AlorFuturesClient,
    private val riskConfig: RiskConfig,
    private val tradingConfig: TradingConfig,
    private val tradingAccountService: TradingAccountService,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /** Результат определения AUM для fail-closed гейтов входа. */
    sealed interface AumResult {
        /** Реальный AUM доступен. [value] — депозит аккаунта, [ageMs] — возраст кэша. */
        data class Available(
            val value: BigDecimal,
            val ageMs: Long,
        ) : AumResult

        /** AUM не определён (LIVE-ошибка API / null / нулевой баланс / нет кэша). */
        data object Unavailable : AumResult
    }

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
            MutableGauges.set(
                meterRegistry,
                "portfolio.aum",
                effective.toDouble(),
                io.micrometer.core.instrument.Tags
                    .of("account", accountId?.toString() ?: "default"),
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

    /**
     * Fail-closed вариант [currentAum] для гейтов входа. При недоступности реального
     * AUM в LIVE-режиме возвращает [AumResult.Unavailable] (вход блокируется), а не
     * подменяет депозит конфигурационным [RiskConfig.maxPositionRub]. В SIMULATION
     * (или при персональном переопределении аккаунта) недоступность не критична —
     * возвращается конфигурационный депозит как раньше.
     *
     * @return [AumResult.Available] с AUM аккаунта, [AumResult.Unavailable] в LIVE
     *   при сбое API / null / нулевом балансе
     */
    suspend fun currentAumChecked(accountId: Long? = null): AumResult {
        val k = key(accountId)
        val entry = cache.computeIfAbsent(k) { CacheEntry(riskConfig.maxPositionRub, 0) }
        val now = System.currentTimeMillis()
        if (now - entry.updatedAt < CACHE_TTL_MS) {
            return AumResult.Available(entry.aum, now - entry.updatedAt)
        }
        val override = tradingAccountService.aumRubOverrideFor(accountId)
        if (override != null) {
            cache[k] = CacheEntry(override, now)
            return AumResult.Available(override, 0)
        }
        return try {
            val money = alorFuturesClient.getPortfolioMoney(tradingAccountService.portfolioOf(accountId))
            if (money != null && money > BigDecimal.ZERO) {
                cache[k] = CacheEntry(money, now)
                MutableGauges.set(
                    meterRegistry,
                    "portfolio.aum",
                    money.toDouble(),
                    Tags.of("account", accountId?.toString() ?: "default"),
                )
                AumResult.Available(money, 0)
            } else {
                // null / нулевой баланс: в LIVE это сигнал недоступных данных — fail-closed.
                logger.warn {
                    "AUM unavailable for accountId=$accountId (got $money) — " +
                        if (isLive) "DENY (fail-closed)" else "fallback ${riskConfig.maxPositionRub} (SIM)"
                }
                liveOrConfigFallback()
            }
        } catch (e: Exception) {
            logger.warn(e) { "AUM fetch failed for accountId=$accountId" }
            liveOrConfigFallback()
        }
    }

    /**
     * Fail-closed синхронная версия [latestAum] для exposure-гейтов. В LIVE без
     * кэшированного значения (до первого успешного обновления) — [AumResult.Unavailable].
     */
    fun latestAumResult(accountId: Long? = null): AumResult {
        val entry = cache[key(accountId)]
        val now = System.currentTimeMillis()
        // updatedAt > 0 = кэш подтверждён РЕАЛЬНЫМ источником (баланс Alor или
        // персональный override). Сид [RiskConfig.maxPositionRub] (updatedAt = 0)
        // реальным AUM не является — в LIVE это ещё недоступные данные.
        return if (entry != null && entry.updatedAt > 0) {
            AumResult.Available(entry.aum, now - entry.updatedAt)
        } else if (isLive) {
            logger.warn { "No confirmed AUM for accountId=$accountId in LIVE — DENY (fail-closed)" }
            AumResult.Unavailable
        } else {
            AumResult.Available(riskConfig.maxPositionRub, now)
        }
    }

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"

    private fun liveOrConfigFallback(): AumResult =
        if (isLive) {
            AumResult.Unavailable
        } else {
            AumResult.Available(riskConfig.maxPositionRub, 0)
        }

    companion object {
        /** Ключ кэша legacy single-account (accountId = null). */
        private const val NULL_ACCOUNT: Long = -1L

        /** TTL кэша баланса Alor, мс. 60с = не дёргать API на каждый тик/проверку. */
        private const val CACHE_TTL_MS = 60_000L
    }
}
