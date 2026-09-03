package com.trading.bot.service

import com.trading.bot.backtest.FrozenStrategy
import org.springframework.stereotype.Component

/**
 * Единая точка резолва «активной» ЗАМОРОЖЕННОЙ стратегии для LIVE-тикера.
 *
 * Возвращает frozen-стратегию только если тикер LIVE-одобрен с ИМЕННО этой
 * стратегией (fingerprint совпадает с [DeploymentApprovalService]). В противном
 * случае — null (paper/SIM, неодобренный тикер, неготовность, mismatch) и исполнение
 * продолжает использовать обычный runtime-конфиг. Fail-closed: не выдумываем frozen.
 *
 * Используется на всех исполнительных уровнях: confidence (StrategyService),
 * SL/TP/leverage/risk/size (EntryProfile/DecisionEngine), fingerprint (транспорт).
 */
@Component
class LiveFrozenStrategyResolver(
    private val frozenStrategyStore: FrozenStrategyStore,
    private val deploymentApprovalService: DeploymentApprovalService,
    private val fingerprintProvider: LiveStrategyFingerprintProvider,
) {
    /** Активная замороженная стратегия тикера (LIVE-approved, fingerprint совпадает). */
    fun resolveActive(ticker: String): FrozenStrategy? {
        val frozen = frozenStrategyStore.current(ticker) ?: return null
        if (!deploymentApprovalService.isLiveAllowed(ticker, fingerprintProvider.fingerprint(frozen))) return null
        return frozen
    }
}
