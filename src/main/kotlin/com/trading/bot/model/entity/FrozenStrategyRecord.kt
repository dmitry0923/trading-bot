package com.trading.bot.model.entity

import com.trading.bot.backtest.FrozenStrategy
import java.time.Instant

/**
 * Персистентное per-ticker замороженной стратегии (таблица frozen_strategy, P1 аудит).
 *
 * Хранит реальные параметры одобренной стратегии (SL/TP/points/leverage/risk/max
 * contracts/confidence + strategy version + build identity) — читается live-execution,
 * участвует в fingerprint. Пишется из DeploymentGate при LIVE_ALLOWED, удаляется при
 * revoke. R2DBC-репозиторий — [com.trading.bot.repository.FrozenStrategyRepository].
 */
data class FrozenStrategyRecord(
    val ticker: String,
    val slPercent: Double? = null,
    val tpPercent: Double? = null,
    val slPoints: Int? = null,
    val tpPoints: Int? = null,
    val confidenceThreshold: Double? = null,
    val leverage: Double = 1.0,
    val riskPerTradePercent: Double? = null,
    val futuresMaxContractsPerPosition: Int? = null,
    val strategyVersion: String,
    val gitCommitSha: String? = null,
    val updatedAt: Instant = Instant.now(),
) {
    fun toFrozenStrategy(): FrozenStrategy =
        FrozenStrategy(
            ticker = ticker,
            strategyVersion = strategyVersion,
            gitCommitSha = gitCommitSha,
            slPercent = slPercent,
            tpPercent = tpPercent,
            slPoints = slPoints,
            tpPoints = tpPoints,
            confidenceThreshold = confidenceThreshold,
            leverage = leverage,
            riskPerTradePercent = riskPerTradePercent,
            futuresMaxContractsPerPosition = futuresMaxContractsPerPosition,
        )

    companion object {
        fun from(
            frozen: FrozenStrategy,
            now: Instant = Instant.now(),
        ): FrozenStrategyRecord =
            FrozenStrategyRecord(
                ticker = frozen.ticker,
                slPercent = frozen.slPercent,
                tpPercent = frozen.tpPercent,
                slPoints = frozen.slPoints,
                tpPoints = frozen.tpPoints,
                confidenceThreshold = frozen.confidenceThreshold,
                leverage = frozen.leverage,
                riskPerTradePercent = frozen.riskPerTradePercent,
                futuresMaxContractsPerPosition = frozen.futuresMaxContractsPerPosition,
                strategyVersion = frozen.strategyVersion,
                gitCommitSha = frozen.gitCommitSha,
                updatedAt = now,
            )
    }
}
