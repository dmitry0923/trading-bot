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
        /**
         * Время последнего обращения к провайдеру (в т.ч. неудачного) — негативный
         * кэш: после ошибки API повторный запрос не отправляется раньше
         * [NEGATIVE_CACHE_TTL_MS]. Отдельно от [updatedAt], который подтверждает
         * РЕАЛЬНЫЙ AUM (fail-closed гейты продолжают его проверять).
         */
        @Volatile var lastAttemptAt: Long = 0,
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
        val now = System.currentTimeMillis()
        if (cacheFresh(entry, now)) {
            return entry.aum
        }
        if (negativeCacheActive(entry, now)) {
            // Окно негативного кэша: Alor не дёргаем, отдаём последний известный баланс.
            return failOpenValue(entry)
        }
        val override = tradingAccountService.aumRubOverrideFor(accountId)
        if (override != null) {
            return storeConfirmed(k, accountId, override)
        }
        return try {
            val money = alorFuturesClient.getPortfolioMoney(tradingAccountService.portfolioOf(accountId))
            if (money != null && money > BigDecimal.ZERO) {
                storeConfirmed(k, accountId, money)
            } else {
                // null/нулевой баланс — тоже недоступность источника: без штампа
                // попытки каждый рендер снова ходил бы в Alor (retry storm).
                entry.lastAttemptAt = System.currentTimeMillis()
                logger.warn { "AUM unavailable for accountId=$accountId (got $money), using fallback ${failOpenValue(entry)}" }
                failOpenValue(entry)
            }
        } catch (e: Exception) {
            // Негативный кэш: штампуем попытку, иначе каждый тик/рендер дашборда
            // снова дёргает Alor (retry storm при недоступном API).
            entry.lastAttemptAt = now
            logger.warn(e) { "AUM fetch failed for accountId=$accountId, using fallback ${failOpenValue(entry)}" }
            failOpenValue(entry)
        }
    }

    /**
     * Сохраняет AUM, подтверждённый источником (баланс Alor / персональный override),
     * и публикует метрику `portfolio.aum`.
     */
    private fun storeConfirmed(
        key: Long,
        accountId: Long?,
        value: BigDecimal,
    ): BigDecimal {
        cache[key] = CacheEntry(value, System.currentTimeMillis())
        MutableGauges.set(
            meterRegistry,
            "portfolio.aum",
            value.toDouble(),
            Tags.of("account", accountId?.toString() ?: "default"),
        )
        return value
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
        if (cacheFresh(entry, now)) {
            return AumResult.Available(entry.aum, now - entry.updatedAt)
        }
        if (negativeCacheActive(entry, now)) {
            // Окно негативного кэша: сетевого вызова нет, но и конфиг-сид НЕ выдаётся
            // за реальный AUM — в LIVE ответ остаётся fail-closed.
            return liveOrConfigFallback()
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
                entry.lastAttemptAt = now
                logger.warn {
                    "AUM unavailable for accountId=$accountId (got $money) — " +
                        if (isLive) "DENY (fail-closed)" else "fallback ${riskConfig.maxPositionRub} (SIM)"
                }
                liveOrConfigFallback()
            }
        } catch (e: Exception) {
            // Негативный кэш: штампуем попытку, иначе каждый запрос дашборда/входа
            // повторяет обращение к Alor (retry storm).
            entry.lastAttemptAt = now
            logger.warn(e) { "AUM fetch failed for accountId=$accountId" }
            liveOrConfigFallback()
        }
    }

    /**
     * Fail-closed синхронная версия [latestAum] для exposure-гейтов. В LIVE без
     * кэшированного значения (до первого успешного обновления) — [AumResult.Unavailable].
     * Кэш старше [AUM_MAX_AGE_MS] реальным AUM не считается — после переставания API
     * exposure-гейты перестают полагаться на устаревший депозит.
     */
    fun latestAumResult(accountId: Long? = null): AumResult {
        val entry = cache[key(accountId)]
        val now = System.currentTimeMillis()
        val ageMs = if (entry != null) now - entry.updatedAt else Long.MAX_VALUE
        // updatedAt > 0 = кэш подтверждён РЕАЛЬНЫМ источником (баланс Alor или
        // персональный override). Сид [RiskConfig.maxPositionRub] (updatedAt = 0)
        // реальным AUM не является — в LIVE это ещё недоступные данные.
        return if (entry != null && entry.updatedAt > 0 && ageMs <= AUM_MAX_AGE_MS) {
            AumResult.Available(entry.aum, ageMs)
        } else if (isLive) {
            logger.warn { "No confirmed AUM for accountId=$accountId in LIVE — DENY (fail-closed)" }
            AumResult.Unavailable
        } else {
            AumResult.Available(riskConfig.maxPositionRub, now)
        }
    }

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"

    /**
     * Кэш подтверждён реальным источником и ещё не устарел. Неудачная попытка
     * (lastAttemptAt > updatedAt) свежим кэшем не считается даже в пределах TTL.
     */
    private fun cacheFresh(
        entry: CacheEntry,
        now: Long,
    ): Boolean = entry.lastAttemptAt <= entry.updatedAt && now - entry.updatedAt < CACHE_TTL_MS

    /** Идёт окно негативного кэша после последней неудачи — провайдер не вызывается. */
    private fun negativeCacheActive(
        entry: CacheEntry,
        now: Long,
    ): Boolean = entry.lastAttemptAt > entry.updatedAt && now - entry.lastAttemptAt < NEGATIVE_CACHE_TTL_MS

    /**
     * Значение для fail-open методов (отчёты/дашборд): последний подтверждённый
     * баланс, а до первого успеха — конфигурационный депозит.
     */
    private fun failOpenValue(entry: CacheEntry): BigDecimal = if (entry.updatedAt > 0) entry.aum else riskConfig.maxPositionRub

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

        /**
         * Негативный кэш после неудачного обращения, мс. Короткий: ограничивает
         * retry storm (дашборд + входной конвейер), но не «замораживает» AUM.
         */
        private const val NEGATIVE_CACHE_TTL_MS = 10_000L

        /** Максимальный возраст кэша для exposure-гейтов, мс. 5 мин = 5× refresh TTL. */
        private const val AUM_MAX_AGE_MS = 5 * 60_000L
    }
}
