package com.trading.bot.service

import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.client.OrderPurpose
import com.trading.bot.repository.PositionRepository
import org.springframework.stereotype.Component

/**
 * Единая точка резолва «активной» ЗАМОРОЖЕННОЙ стратегии для LIVE-тикера.
 *
 * Возвращает frozen-стратегию только если тикер LIVE-одобрен с ИМЕННО этой
 * стратегией (fingerprint совпадает с [DeploymentApprovalService]) И замороженный
 * build-identity [FrozenStrategy.gitCommitSha] совпадает с SHA текущего
 * выполняющегося процесса ([BuildIdentity.gitCommitSha]). В противном случае — null
 * (paper/SIM, неодобренный тикер, неготовность, mismatch, build-identity change) и
 * исполнение продолжает использовать обычный runtime-конфиг. Fail-closed: не
 * выдумываем frozen; отсутствие build-identity у текущего процесса => DENY.
 *
 * Инвариант LIVE: approval существует И fingerprint(()frozen) совпадает И frozen
 * build SHA == сейчас выполняемый build SHA. Так старый approval из сборки A не
 * «легитимизирует» исполнение ещё не валидированной логики сборки B (P1).
 *
 * Используется на всех исполнительных уровнях: confidence (StrategyService),
 * SL/TP/leverage/risk/size (EntryProfile/DecisionEngine), fingerprint (транспорт).
 *
 * Разделение по назначению ордера (P1-a): строгий [isOrderAllowed] только для
 * risk-INCREASING entry; risk-reducing закрытия (close/SL/TP) разрешены, если тикер
 * approved ИЛИ для него существует открытая позиция — иначе после revoke / смены
 * build SHA бот физически не смог бы выйти из открытой позиции.
 */
@Component
class LiveFrozenStrategyResolver(
    private val frozenStrategyStore: FrozenStrategyStore,
    private val deploymentApprovalService: DeploymentApprovalService,
    private val fingerprintProvider: LiveStrategyFingerprintProvider,
    private val buildIdentity: BuildIdentity,
    private val positionRepository: PositionRepository,
) {
    /** Активная замороженная стратегия тикера (LIVE-approved, fingerprint совпадает, build совпадает). */
    fun resolveActive(ticker: String): FrozenStrategy? {
        val frozen = frozenStrategyStore.current(ticker) ?: return null

        // Fail-closed: для LIVE обязателен build-identity текущего процесса.
        val currentBuild = buildIdentity.gitCommitSha() ?: return null

        // build SHA внутри frozen — доказательство того, что валидировалась ТО ЖЕ сборка.
        if (frozen.gitCommitSha != currentBuild) return null

        if (!deploymentApprovalService.isLiveAllowed(ticker, fingerprintProvider.fingerprint(frozen))) return null
        return frozen
    }

    /**
     * Execution interlock для конкретного назначения ордера (P1-a).
     *
     * - [OrderPurpose.ENTRY] — риск-увеличивающий: требуется активная frozen-стратегия
     *   (approved + fingerprint + build SHA). Позиции под неодобренным тикером открыть нельзя.
     * - [OrderPurpose.CLOSE]/[SL]/[TP] — риск-уменьшающие: разрешены, если тикер approved
     *   ЛИБО для тикера существует открытая позиция (открытая могла быть только под
     *   одобренным на момент входа тикером). Fail-closed: ошибка БД => deny.
     */
    suspend fun isOrderAllowed(
        ticker: String,
        purpose: OrderPurpose,
    ): Boolean =
        when (purpose) {
            OrderPurpose.ENTRY -> resolveActive(ticker) != null
            OrderPurpose.CLOSE, OrderPurpose.SL, OrderPurpose.TP -> isReducingOrderAllowed(ticker)
        }

    private suspend fun isReducingOrderAllowed(ticker: String): Boolean {
        if (resolveActive(ticker) != null) return true
        return try {
            positionRepository.hasOpenPosition(ticker)
        } catch (e: Exception) {
            false
        }
    }
}
