package com.trading.bot.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Гейт восстановления входа после потери lease распределённого лока входа
 * (P1-аудит 2026-09-07, ференсинг).
 *
 * После [DistributedLockService.LockExecutionResult.LEASE_LOST] критическая секция
 * входа могла выполниться частично (резервация слота / outbox / отправка ордера).
 * Пока не завершился reconciliation (State Reconciliation / per-position reconcile),
 * НОВЫЕ ENTRY в пострадавший скоуп (аккаунт/тикер) блокируются: инстанс, не успевший
 * сверить своё состояние с брокером, не должен открывать новые позиции «вслепую».
 *
 * Риск-редуцирующие операции (CLOSE / SL / TP) НЕ блокируются — гейт затрагивает
 * только ENTRY. Абсолютную гарантию единичности входа по-прежнему даёт БД-адмиссия
 * ([PositionRepository.reserveEntry]); гейт — дополнительное ужесточение поверхности
 * «протухшей эры» до выполнения reconciliation.
 */
@Component
class EntryLeaseRecoveryGate(
    private val meterRegistry: MeterRegistry,
) {
    private val degradedScopes = ConcurrentHashMap.newKeySet<String>()

    /** Вход в [scope] запрещён до завершения reconciliation-цикла. */
    fun mark(
        scope: String,
        cause: String,
    ) {
        degradedScopes.add(scope)
        meterRegistry
            .counter("entry.lease.recovery_required", Tags.of("cause", cause))
            .increment()
    }

    /** Вход в [scope] в данный момент запрещён (после LEASE_LOST, до reconciliation). */
    fun isDegraded(scope: String): Boolean = degradedScopes.contains(scope)

    /** Reconciliation прошёл успешно — ENTRY по всем скоупам снова разрешены. */
    fun recoverAll() {
        if (degradedScopes.isEmpty()) return
        degradedScopes.clear()
        meterRegistry.counter("entry.lease.recovery_completed").increment()
    }

    /** Для наблюдения/тестов: набор запрещённых скоупов. */
    fun degradedScopes(): Set<String> = degradedScopes.toSet()
}
