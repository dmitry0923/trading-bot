package com.trading.bot.service

import com.trading.bot.model.entity.DeploymentApprovalRecord
import com.trading.bot.repository.DeploymentApprovalRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/**
 * Per-ticker LIVE-одобрение (execution interlock, P1 аудит).
 *
 * - Горячие проверки [isLiveAllowed] идут ТОЛЬКО по in-memory кэшу (без БД),
 *   как [TradingHaltService.last] в [com.trading.bot.application.TradingGate].
 * - Кэш восстанавливается из deployment_approval на старте ([ApplicationReadyEvent])
 *   — одобрение переживает рестарт.
 * - [approve]/[revoke] пишут в БД синхронно и обновляют кэш.
 *
 * fail-closed: тикера нет в кэше -> [isLiveAllowed] == false -> вход в live невозможен.
 */
@Service
class DeploymentApprovalService(
    private val repository: DeploymentApprovalRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var approved: Map<String, DeploymentApprovalRecord> = emptyMap()

    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        try {
            approved = runBlocking { repository.latest() }.associateBy { it.ticker }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load deployment approvals from DB — no ticker approved for LIVE (fail-closed)" }
        }
        logger.info { "Restored ${approved.size} LIVE-approved ticker(s): ${approved.keys.sorted()}" }
    }

    /**
     * Быстрая (без БД) проверка: допущен ли тикер к LIVE-торговле.
     */
    fun isLiveAllowed(ticker: String): Boolean = approved[ticker]?.status == "LIVE_ALLOWED"

    /**
     * Одобряет тикер к LIVE (вызывается ТОЛЬКО из DeploymentGate при LIVE_ALLOWED).
     */
    suspend fun approve(
        ticker: String,
        status: String,
        frozenConfidenceThreshold: Double?,
        paramsHash: String?,
    ) {
        val record = DeploymentApprovalRecord(ticker, status, frozenConfidenceThreshold, paramsHash)
        approved = approved + (ticker to record)
        try {
            repository.save(record)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist approval for $ticker (in-memory only)" }
        }
        logger.info { "Deployment approved for $ticker: status=$status confidence=$frozenConfidenceThreshold" }
    }

    /**
     * Отзывает одобрение (статус != LIVE_ALLOWED -> тикер снова заблокирован).
     */
    suspend fun revoke(ticker: String) {
        approved = approved - ticker
        try {
            repository.delete(ticker)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete approval for $ticker (in-memory only)" }
        }
        logger.info { "Deployment revoked for $ticker" }
    }

    fun allApproved(): Map<String, DeploymentApprovalRecord> = approved
}
