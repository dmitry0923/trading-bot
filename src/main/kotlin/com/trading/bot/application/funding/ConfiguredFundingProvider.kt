package com.trading.bot.application.funding

import com.trading.bot.config.InstrumentsConfig
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * CONFIG-источник funding: фиксированное
 * [InstrumentsConfig.InstrumentSpec.fundingRubPerContractPerDay].
 *
 * Единственный источник в SIM/backtest; в LIVE — только явный fallback, когда MOEX
 * недоступен (метрика `funding.live.provider_unavailable`) — никогда не «тихий»
 * авторитет. См. [FundingSnapshotService].
 */
@Component
class ConfiguredFundingProvider(
    private val instrumentsConfig: InstrumentsConfig,
) : FundingProvider {
    override suspend fun currentSnapshot(ticker: String): FundingSnapshot {
        val value = instrumentsConfig.find(ticker)?.fundingPerClearing() ?: BigDecimal.ZERO
        return FundingSnapshot(
            ticker = ticker,
            rawValue = value,
            unit = FundingUnit.RUB_PER_CONTRACT_PER_CLEARING,
            valueRubPerContractPerClearing = value,
            source = FundingSource.CONFIG,
            timestamp = LocalDateTime.now(),
        )
    }

    /** Синхронное значение для P&L (без сетевых вызовов, конфиг доступен всегда). */
    fun value(ticker: String): BigDecimal = instrumentsConfig.find(ticker)?.fundingPerClearing() ?: BigDecimal.ZERO
}
