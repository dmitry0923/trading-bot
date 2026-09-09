package com.trading.bot.application.funding

import com.trading.bot.config.InstrumentsConfig
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * CONFIG-источник funding: фиксированное
 * [InstrumentsConfig.InstrumentSpec.fundingRubPerContractPerDay].
 *
 * Единственный источник в SIM/backtest; в LIVE НЕ используется как авторитет
 * (см. [FundingSnapshotService]: MOEX недоступен → FUNDING_UNKNOWN, метрика
 * `funding.live.provider_unavailable`, никакой «тихой» подстановки 0.5). Здесь
 * `value(ticker)` — синхронная SIM/backtest-ставка для расчёта P&L.
 */
@Component
class ConfiguredFundingProvider(
    private val instrumentsConfig: InstrumentsConfig,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
) : FundingProvider {
    override suspend fun currentSnapshot(ticker: String): FundingSnapshot {
        val value = instrumentsConfig.find(ticker)?.fundingPerClearing() ?: BigDecimal.ZERO
        return FundingSnapshot(
            ticker = ticker,
            clearingDate = LocalDate.now(clock),
            rawValue = value,
            unit = FundingUnit.RUB_PER_CONTRACT_PER_CLEARING,
            valueRubPerContractPerClearing = value,
            source = FundingSource.CONFIG,
            timestamp = LocalDateTime.now(clock),
        )
    }

    /** Синхронное значение для P&L (без сетевых вызовов, конфиг доступен всегда). */
    fun value(ticker: String): BigDecimal = instrumentsConfig.find(ticker)?.fundingPerClearing() ?: BigDecimal.ZERO
}
