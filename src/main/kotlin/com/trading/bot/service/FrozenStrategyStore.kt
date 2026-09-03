package com.trading.bot.service

import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.model.entity.FrozenStrategyRecord
import com.trading.bot.repository.FrozenStrategyRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/**
 * Per-ticker store замороженной стратегии (frozen_strategy) для LIVE-исполнения.
 *
 * Fail-closed: [ready] == false при неинициализированности/ошибке загрузки из БД →
 * [current] возвращает null для любого тикера (т.е. «нет замороженной стратегии»).
 * Транспорт в этом случае не сможет построить совпадающий fingerprint и заблокирует
 * real-ордер (наложившись на [DeploymentApprovalService]).
 *
 * [upsert] — сначала персистентный коммит в БД, затем кэш; при ошибке БД кэш не
 * активируется (брошенное исключение пробрасывается). [clear] — удаление из БД и кэша.
 */
@Service
class FrozenStrategyStore(
    private val repository: FrozenStrategyRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var cache: Map<String, FrozenStrategy> = emptyMap()

    @Volatile
    private var ready: Boolean = false

    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        ready = false
        cache = emptyMap()
        try {
            cache =
                runBlocking { repository.latest() }
                    .associate { it.ticker to it.toFrozenStrategy() }
            ready = true
        } catch (e: Exception) {
            logger.error(
                e,
            ) { "Failed to load frozen strategies from DB — readiness=false, LIVE entries treat as no frozen strategy (fail-closed)" }
        }
        logger.info { "Restored ${cache.size} frozen strateg(ies) from DB (ready=$ready)" }
    }

    fun isReady(): Boolean = ready

    /** Текущая замороженная стратегия тикера (fail-closed: null при неготовности). */
    fun current(ticker: String): FrozenStrategy? {
        if (!ready) return null
        return cache[ticker]
    }

    suspend fun upsert(frozen: FrozenStrategy) {
        val record = FrozenStrategyRecord.from(frozen)
        repository.save(record)
        cache = cache + (frozen.ticker to frozen)
        logger.info { "Frozen strategy stored for ${frozen.ticker} (version=${frozen.strategyVersion})" }
    }

    /** Удаляет замороженную стратегию тикера (БД + кэш). */
    suspend fun clear(ticker: String) {
        cache = cache - ticker
        repository.delete(ticker)
        logger.info { "Frozen strategy cleared for $ticker" }
    }

    fun all(): Map<String, FrozenStrategy> = cache
}
