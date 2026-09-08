package com.trading.bot.application.funding

import com.trading.bot.config.FundingConfig
import com.trading.bot.config.TradingConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Единая точка доступа к funding для P&L (P0-аудит).
 *
 * - LIVE: отдаёт MOEX-снапшот ([MoexFundingProvider]) с TTL [FundingConfig.moexTtlMs];
 *   при недоступности MOEX — provisional CONFIG fallback с метрикой
 *   `funding.live.provider_unavailable` (никогда «тихо»).
 * - SIM/backtest: CONFIG напрямую (фиксированное значение корректно для симуляции).
 *
 * Снапшоты обновляются при входе (см.
 * [com.trading.bot.application.decision.FuturesEntryProfile.buildEntryRequest]);
 * синхронный [value] используется из горячего пути P&L без сетевых вызовов.
 */
@Component
class FundingSnapshotService(
    private val fundingConfig: FundingConfig,
    private val tradingConfig: TradingConfig,
    private val configuredFunding: ConfiguredFundingProvider,
    private val moexFunding: MoexFundingProvider,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val cache = ConcurrentHashMap<String, FundingSnapshot>()

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"

    /**
     * Обновляет funding-снапшот тикера (вызывается на входе фьючерсной позиции,
     * suspend-контекст). LIVE: MOEX при наличии, иначе CONFIG fallback + метрика.
     */
    suspend fun refresh(ticker: String) {
        if (isLive) {
            val live = moexFunding.currentSnapshot(ticker)
            if (live != null) {
                cache[ticker] = live
                return
            }
            meterRegistry.counter("funding.live.provider_unavailable", Tags.of("ticker", ticker)).increment()
            logger.warn { "Funding (MOEX) unavailable for $ticker in LIVE — provisional config fallback" }
        }
        cache[ticker] = configuredFunding.currentSnapshot(ticker)
    }

    /**
     * Funding за 1 контракт за 1 клиринг (RUB) для P&L (синхронно, без сетевых вызовов).
     * Устаревший MOEX-снапшот (старше [FundingConfig.moexTtlMs]) авторитетом не считается —
     * provisional CONFIG fallback + метрика `funding.live.snapshot_stale_config_fallback`.
     */
    fun value(ticker: String): BigDecimal {
        val snapshot = cache[ticker]
        val ageMs =
            snapshot
                ?.timestamp
                ?.let { Duration.between(it, LocalDateTime.now()).toMillis() }
                ?: Long.MAX_VALUE
        if (snapshot != null && snapshot.source == FundingSource.MOEX && ageMs > fundingConfig.moexTtlMs) {
            meterRegistry.counter("funding.live.snapshot_stale_config_fallback", Tags.of("ticker", ticker)).increment()
            return configuredFunding.value(ticker)
        }
        return snapshot?.valueRubPerContractPerClearing ?: configuredFunding.value(ticker)
    }
}
