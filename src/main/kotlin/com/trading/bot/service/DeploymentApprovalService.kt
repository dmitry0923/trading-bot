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
 * Fail-closed гарантии:
 * - Горячие проверки [isLiveAllowed] идут ТОЛЬКО по in-memory кэшу (без БД),
 *   как [com.trading.bot.application.TradingGate].
 * - [ready] == false при неинициализированности/ошибке загрузки из БД → ВСЕ
 *   тикеры DENY (placement блокируется), пока состояние не восстановится из БД.
 * - Требуется совпадающий strategy fingerprint: даже при status == LIVE_ALLOWED
 *   ордер допускается только если runtime-фрintprиnt == сохранённому при approve.
 * - [approve] сначала КОММИТИТ в БД, и только при успехе обновляет кэш; ошибка БД
 *   → метод завершается ошибкой, approval НЕ активируется.
 * - [revoke] — персистентный переход состояния в REVOKED через UPSERT (НЕ DELETE):
 *   после рестарта [init] не «воскресит» старый LIVE_ALLOWED. При невозможности
 *   записи revoke в БД процесс переводится в глобальный NOT_READY (ready=false).
 *
 * Остаточный риск осознан и задокументирован (см. [revoke]): если revoke НЕ удалось
 * персистентно записать, а процесс был завершён ДО восстановления persistence, БД всё
 * ещё содержит LIVE_ALLOWED и может воскресить тикер после рестарта. Митигация —
 * перевод в NOT_READY в текущем процессе (запрет новых входов до ручного вмешательства).
 */
@Service
class DeploymentApprovalService(
    private val repository: DeploymentApprovalRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var approved: Map<String, DeploymentApprovalRecord> = emptyMap()

    @Volatile
    private var ready: Boolean = false

    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        ready = false
        approved = emptyMap()
        try {
            approved =
                runBlocking { repository.latest() }
                    .filter { it.status == LIVE_ALLOWED }
                    .associateBy { it.ticker }
            ready = true
        } catch (e: Exception) {
            logger.error(e) { "Failed to load deployment approvals from DB — readiness=false, ALL LIVE entries DENIED (fail-closed)" }
        }
        logger.info { "Restored ${approved.size} LIVE-approved ticker(s) from DB (ready=$ready)" }
    }

    /** Реди-кэш восстановлен из БД; false => глобальный deny (fail-closed). */
    fun isReady(): Boolean = ready

    /**
     * Быстрая (без БД) проверка: допущен ли тикер к LIVE-торговле С ИМЕННО ЭТОЙ
     * стратегией.
     *
     * @param expectedFingerprint runtime-фрintprиnt стратегии (см. [LiveStrategyFingerprintProvider]).
     *   null (не удалось вычислить) => DENY (fail-closed: мы не знаем, что стратегия совпадает).
     */
    fun isLiveAllowed(
        ticker: String,
        expectedFingerprint: String?,
    ): Boolean {
        if (!ready) return false
        if (expectedFingerprint == null) return false
        val record = approved[ticker] ?: return false
        if (record.status != LIVE_ALLOWED) return false
        val stored = record.paramsHash ?: return false
        return stored == expectedFingerprint
    }

    /**
     * Одобряет тикер (вызывается ТОЛЬКО из DeploymentGate при LIVE_ALLOWED).
     * Сначала персистентный коммит в БД; только при успехе — атомарная активация кэша.
     * Ошибка БД выбрасывается наружу, approval остаётся неактивным (false).
     */
    suspend fun approve(
        ticker: String,
        status: String,
        frozenConfidenceThreshold: Double?,
        fingerprint: String?,
    ) {
        val record = DeploymentApprovalRecord(ticker, status, frozenConfidenceThreshold, fingerprint)
        repository.save(record)
        approved = approved + (ticker to record)
        logger.info { "Deployment approved for $ticker: status=$status confidence=$frozenConfidenceThreshold" }
    }

    /**
     * Отзывает одобрение. Сначала немедленно деним тикер в кэше (безопасное
     * направление), затем персистентно пишем статус REVOKED (UPSERT, НЕ DELETE),
     * чтобы рестарт не воскресил старый LIVE_ALLOWED.
     *
     * Если запись REVOKED в БД не удалась (persistence недоступна), переводим
     * процесс в глобальный NOT_READY (ready=false): лучший доступный гарант, что
     * входов не будет, пока persistence не восстановится. Не выполняем auto-reinit
     * из БД, чтобы случайно не воскресить всё ещё живую запись LIVE_ALLOWED.
     */
    suspend fun revoke(ticker: String) {
        val previous = approved[ticker]
        approved = approved - ticker
        try {
            repository.save(DeploymentApprovalRecord(ticker, REVOKED, previous?.frozenConfidenceThreshold, previous?.paramsHash))
            logger.info { "Deployment revoked for $ticker (persistent REVOKED)" }
        } catch (e: Exception) {
            ready = false
            logger.error(e) {
                "FAILED to persist REVOKED for $ticker — entering global NOT_READY (fail-closed), " +
                    "ALL LIVE entries denied until persistence restored"
            }
        }
    }

    fun allApproved(): Map<String, DeploymentApprovalRecord> = approved

    companion object {
        const val LIVE_ALLOWED = "LIVE_ALLOWED"
        const val REVOKED = "REVOKED"
    }
}
