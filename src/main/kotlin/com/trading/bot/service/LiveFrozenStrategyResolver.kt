package com.trading.bot.service

import com.trading.bot.backtest.FrozenStrategy
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
 */
@Component
class LiveFrozenStrategyResolver(
    private val frozenStrategyStore: FrozenStrategyStore,
    private val deploymentApprovalService: DeploymentApprovalService,
    private val fingerprintProvider: LiveStrategyFingerprintProvider,
    private val buildIdentity: BuildIdentity,
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
}
