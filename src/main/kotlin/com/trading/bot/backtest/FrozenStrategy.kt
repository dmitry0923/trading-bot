package com.trading.bot.backtest

/**
 * Единый ЗАМОРОЖЕННЫЙ набор параметров стратегии для per-ticker LIVE-исполнения.
 *
 * Является единственным источником правды для всего, что касается исполнения
 * одобренной стратегии: fingerprint, approve, live-execution (SL/TP/размер/риск/
 * confidence/леверидж). Создаётся ОДИН раз (из [StrategyParameters], полученных по
 * итогам WFA->holdout->MC) и далее НЕ пересобирается из текущего конфига — любые
 * изменения в live-конфигурации меняют fingerprint и блокируют исполнение, пока не
 * проведён новый deployment-цикл.
 *
 * @property strategyVersion версия логики стратегии (должна увеличиваться при
 *   изменении сигнала/исполнения; включается в fingerprint)
 * @property gitCommitSha identity сборки (P2): SHA-256/полный коммит; null, если
 *   билд-идентичность недоступна — тогда fingerprint опирается на версию и параметры
 */
data class FrozenStrategy(
    val ticker: String,
    val strategyVersion: String,
    val gitCommitSha: String?,
    val slPercent: Double?,
    val tpPercent: Double?,
    val slPoints: Int?,
    val tpPoints: Int?,
    val confidenceThreshold: Double?,
    val leverage: Double,
    val riskPerTradePercent: Double?,
    val futuresMaxContractsPerPosition: Int?,
) {
    companion object {
        /** Строит [FrozenStrategy] из замороженных [StrategyParameters] + identity. */
        fun from(
            parameters: StrategyParameters,
            ticker: String,
            strategyVersion: String,
            gitCommitSha: String?,
        ): FrozenStrategy =
            FrozenStrategy(
                ticker = ticker,
                strategyVersion = strategyVersion,
                gitCommitSha = gitCommitSha,
                slPercent = parameters.slPercent.takeIf { it > 0.0 },
                tpPercent = parameters.tpPercent.takeIf { it > 0.0 },
                slPoints = parameters.slPoints,
                tpPoints = parameters.tpPoints,
                confidenceThreshold = parameters.confidenceThreshold,
                leverage = parameters.leverage,
                riskPerTradePercent = parameters.riskPerTradePercent,
                futuresMaxContractsPerPosition = parameters.futuresMaxContractsPerPosition,
            )
    }
}
