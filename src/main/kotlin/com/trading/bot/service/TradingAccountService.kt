package com.trading.bot.service

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.entity.TradingAccount
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.TradingAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Реестр торговых аккаунтов (multi-account, roadmap v2.2).
 *
 * Пустая таблица `trading_accounts` = legacy single-account режим: выбранный аккаунт
 * отсутствует (accountId = null), портфель берётся из [AlorConfig.portfolio] —
 * поведение бота полностью идентично до-мультиаккаунтной версии.
 *
 * Распределение сигналов: весовой round-robin по [findEnabled] с учётом
 * ёмкости аккаунта (открытые позиции < персонального лимита [TradingAccount.maxOpenPositions]
 * или глобального [RiskConfig.maxOpenPositions]). Несколько аккаунтов одинакового
 * веса получают сигналы по очереди; вес 2 получает в среднем вдвое больше сигналов.
 */
@Service
class TradingAccountService(
    private val repository: TradingAccountRepository,
    private val positionRepository: PositionRepository,
    private val alorConfig: AlorConfig,
    private val riskConfig: RiskConfig,
) {
    private val log = LoggerFactory.getLogger(TradingAccountService::class.java)

    /** TTL кэша включённых аккаунтов (включение/выключение через API срабатывает быстро). */
    private var cachedEnabled: List<TradingAccount>? = null
    private var cacheLoadedAt: Long = 0
    private var rrCounter: Long = 0

    @Volatile
    private var rrSetFingerprint: Int = -1

    // ===== Read =====

    @Suppress("unused")
    suspend fun findAll(): List<TradingAccount> = repository.findAll()

    suspend fun findEnabled(): List<TradingAccount> {
        val now = System.currentTimeMillis()
        if (cachedEnabled == null || now - cacheLoadedAt > CACHE_TTL_MS) {
            cachedEnabled = repository.findEnabled()
            cacheLoadedAt = now
        }
        return cachedEnabled!!
    }

    suspend fun findById(id: Long): TradingAccount? = repository.findById(id)

    @Suppress("unused")
    suspend fun findByName(name: String): TradingAccount? = repository.findAll().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Портфель Alor для аккаунта. null (legacy) или неизвестный аккаунт → [AlorConfig.portfolio].
     */
    suspend fun portfolioOf(accountId: Long?): String {
        if (accountId == null) return alorConfig.portfolio
        return findById(accountId)?.alorPortfolio ?: alorConfig.portfolio
    }

    /** Персональный лимит открытых позиций; null → глобальный [RiskConfig.maxOpenPositions]. */
    suspend fun maxOpenPositionsFor(accountId: Long?): Int =
        accountId?.let { findById(it)?.maxOpenPositions } ?: riskConfig.maxOpenPositions

    /** Персональное переопределение AUM (руб); null → реальный баланс Alor. */
    suspend fun aumRubOverrideFor(accountId: Long?): BigDecimal? = accountId?.let { findById(it)?.aumRub }

    /** Персональный дневной лимит убытка (руб); null → расчёт % от AUM. */
    suspend fun maxDailyLossRubFor(accountId: Long?): BigDecimal? = accountId?.let { findById(it)?.maxDailyLossRub }

    /** Синхронные getters из кэша включённых аккаунтов (для non-suspend hot paths риска). */

    fun cachedMaxDailyLossRubFor(accountId: Long?): BigDecimal? =
        accountId?.let { id -> cachedEnabled?.firstOrNull { it.id == id }?.maxDailyLossRub }

    @Suppress("unused")
    fun cachedMaxOpenPositionsFor(accountId: Long?): Int? =
        accountId?.let { id -> cachedEnabled?.firstOrNull { it.id == id }?.maxOpenPositions }

    @Suppress("unused")
    fun cachedAumRubFor(accountId: Long?): BigDecimal? = accountId?.let { id -> cachedEnabled?.firstOrNull { it.id == id }?.aumRub }

    @Suppress("unused")
    fun cachedPortfolioFor(accountId: Long?): String? = accountId?.let { id -> cachedEnabled?.firstOrNull { it.id == id }?.alorPortfolio }

    /**
     * Выбор аккаунта для нового входа: весовой round-robin по включённым аккаунтам
     * с ёмкостью (открытых позиций < персонального лимита). Возвращает null, если
     * таблица пуста (legacy single-account) или все аккаунты переполнены.
     */
    suspend fun selectAccount(): Long? {
        val accounts = findEnabled()
        if (accounts.isEmpty()) return null

        val candidates = mutableListOf<TradingAccount>()
        for (account in accounts) {
            val limit = account.maxOpenPositions ?: riskConfig.maxOpenPositions
            val open = positionRepository.findOpenCountByAccount(account.id!!)
            if (open < limit) candidates.add(account)
        }
        if (candidates.isEmpty()) return null

        if (candidates.size == 1) return candidates[0].id

        val fingerprint = candidates.joinToString { it.id.toString() }.hashCode()
        if (fingerprint != rrSetFingerprint) {
            rrSetFingerprint = fingerprint
            rrCounter = 0
        }

        val totalWeight = candidates.sumOf { it.weight.coerceAtLeast(1) }
        var pick = (rrCounter++ % totalWeight).toInt()
        var selected: TradingAccount = candidates.last()
        for (account in candidates) {
            pick -= account.weight.coerceAtLeast(1)
            if (pick < 0) {
                selected = account
                break
            }
        }
        return selected.id
    }

    // ===== CRUD (API/UI) =====

    suspend fun create(
        name: String,
        alorPortfolio: String,
        exchange: String = "MOEX",
        enabled: Boolean = true,
        aumRub: BigDecimal? = null,
        maxOpenPositions: Int? = null,
        maxDailyLossRub: BigDecimal? = null,
        weight: Int = 1,
    ): TradingAccount {
        val account =
            TradingAccount(
                name = name,
                alorPortfolio = alorPortfolio,
                exchange = exchange,
                enabled = enabled,
                aumRub = aumRub,
                maxOpenPositions = maxOpenPositions,
                maxDailyLossRub = maxDailyLossRub,
                weight = weight,
            )
        return repository.save(account).also { invalidateCache() }
    }

    suspend fun update(
        id: Long,
        name: String? = null,
        alorPortfolio: String? = null,
        exchange: String? = null,
        enabled: Boolean? = null,
        aumRub: BigDecimal? = null,
        maxOpenPositions: Int? = null,
        maxDailyLossRub: BigDecimal? = null,
        weight: Int? = null,
    ): TradingAccount? {
        val existing = repository.findById(id) ?: return null
        val updated =
            existing.copy(
                name = name ?: existing.name,
                alorPortfolio = alorPortfolio ?: existing.alorPortfolio,
                exchange = exchange ?: existing.exchange,
                enabled = enabled ?: existing.enabled,
                aumRub = aumRub,
                maxOpenPositions = maxOpenPositions,
                maxDailyLossRub = maxDailyLossRub,
                weight = weight ?: existing.weight,
                updatedAt = LocalDateTime.now(),
            )
        return repository.save(updated).also { invalidateCache() }
    }

    suspend fun delete(id: Long): Boolean {
        val existing = repository.findById(id) ?: return false
        repository.deleteById(id)
        invalidateCache()
        log.info("Deleted trading account '{}' (portfolio {})", existing.name, existing.alorPortfolio)
        return true
    }

    private fun invalidateCache() {
        cachedEnabled = null
        cacheLoadedAt = 0
        rrSetFingerprint = -1
        rrCounter = 0
    }

    private companion object {
        const val CACHE_TTL_MS = 30_000L
    }
}
