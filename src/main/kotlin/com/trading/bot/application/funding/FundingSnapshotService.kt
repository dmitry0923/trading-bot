package com.trading.bot.application.funding

import com.trading.bot.config.FundingConfig
import com.trading.bot.config.TradingConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Единая точка доступа к funding для P&L (P0-аудит) — per-clearing модель.
 *
 * Снапшоты хранятся СЕРИЕЙ по дате клиринга ([FundingSnapshot.clearingDate]):
 * для каждого пережитого клиринга P&L позиции использует ЗНАЧЕНИЕ ИМЕННО ЭТОГО
 * клиринга (сумму по [FundingCosts.clearingDates]), а не «текущее значение ×
 * число клирингов». Серия пополняется при входе ([refresh]) и каждый день, пока
 * позиция открыта (повторный [refresh] в стратегическом цикле).
 *
 * Политика источника:
 * - LIVE: авторитет только MOEX. Если MOEX недоступен (или на дату клиринга нет
 *   авторитетного снапшота) — [fundingForClearings] возвращает null
 *   (FUNDING_UNKNOWN) с ERROR-логом и метрикой `funding.live.clearing_unknown`;
 *   CONFIG-значение в LIVE НЕ подставляется (никакого «тихого 0.5»). P&L такой
 *   сделки вычислен без авторитетного funding (0 за неизвестные клиринги) —
 *   сделка помечается как funding-uncertain на стороне вызывающего контура.
 * - SIM/backtest: CONFIG напрямую (фиксированное значение за каждый клиринг
 *   корректно для симуляции).
 *
 * [refresh] не делает сетевой запрос, если на сегодняшнюю дату клиринга уже есть
 * свежий MOEX-снапшот (TTL [FundingConfig.moexTtlMs]) — серия накапливает ровно
 * одно значение на дату, без холостых вызовов MOEX на каждый бот-цикл.
 */
@Component
class FundingSnapshotService(
    private val fundingConfig: FundingConfig,
    private val tradingConfig: TradingConfig,
    private val configuredFunding: ConfiguredFundingProvider,
    private val moexFunding: MoexFundingProvider,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
) {
    private val logger = KotlinLogging.logger {}
    private val series = ConcurrentHashMap<String, ConcurrentSkipListMap<LocalDate, FundingSnapshot>>()

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"

    /**
     * Обновляет funding-серию тикера (вызывается на входе фьючерсной позиции и в
     * стратегическом цикле, suspend-контекст). В LIVE MOEX-снапшот записывается в
     * серию под сегодняшней датой клиринга; недоступность MOEX — ERROR + метрика,
     * серия не пополняется (CONFIG не используется).
     *
     * Сетевой вызов пропускается, если последний ПОЛУЧЕННЫЙ снапшот тикера —
     * свежий MOEX (возраст <= [FundingConfig.moexTtlMs]): на один день накапливается
     * одно значение, без холостых вызовов MOEX на каждый бот-цикл. На новый день
     * возраст предыдущего снапшота > TTL → повторный запрос, значение попадает под
     * новую дату клиринга.
     */
    suspend fun refresh(ticker: String) {
        val latest = series[ticker]?.lastEntry()?.value
        val freshMoex =
            latest != null &&
                latest.source == FundingSource.MOEX &&
                Duration.between(latest.timestamp, LocalDateTime.now(clock)).toMillis() <= fundingConfig.moexTtlMs
        if (freshMoex) return

        if (isLive) {
            val live = moexFunding.currentSnapshot(ticker)
            if (live != null) {
                series.computeIfAbsent(ticker) { ConcurrentSkipListMap() }[live.clearingDate] = live
                return
            }
            meterRegistry.counter("funding.live.provider_unavailable", Tags.of("ticker", ticker)).increment()
            logger.error {
                "Funding (MOEX) unavailable for $ticker in LIVE — per-clearing funding for today UNKNOWN, " +
                    "P&L of crossing trades will be marked uncertain"
            }
            return
        }
        val config = configuredFunding.currentSnapshot(ticker)
        series.computeIfAbsent(ticker) { ConcurrentSkipListMap() }[config.clearingDate] = config
    }

    /**
     * TOTAL funding за 1 контракт по всем пережитым [clearings] (RUB) для P&L
     * (синхронно, без сетевых вызовов). Сумма значений ЗА КАЖДЫЙ клиринг.
     *
     * @return сумма per-clearing значений; null = FUNDING_UNKNOWN (LIVE: хотя бы
     *   на один клиринг нет авторитетного MOEX-снапшота — CONFIG не подставляется,
     *   ERROR/метрика). В SIM/backtest никогда null (фиксированная ставка).
     */
    fun fundingForClearings(
        ticker: String,
        clearings: List<LocalDate>,
    ): BigDecimal? {
        if (clearings.isEmpty()) return BigDecimal.ZERO
        val configured = configuredFunding.value(ticker)
        if (configured <= BigDecimal.ZERO) return BigDecimal.ZERO
        if (!isLive) return configured.multiply(BigDecimal(clearings.size))
        val tickerSeries = series[ticker].orEmpty()
        val missing = mutableListOf<LocalDate>()
        var total = BigDecimal.ZERO
        for (clearing in clearings) {
            val snapshot = tickerSeries[clearing]
            if (snapshot == null || snapshot.source != FundingSource.MOEX) {
                missing.add(clearing)
            } else {
                total = total.add(snapshot.valueRubPerContractPerClearing)
            }
        }
        if (missing.isNotEmpty()) {
            meterRegistry.counter("funding.live.clearing_unknown", Tags.of("ticker", ticker)).increment()
            logger.error {
                "FUNDING_UNKNOWN $ticker clearings=$missing — no authoritative MOEX snapshot; " +
                    "P&L расчёт без funding deduction (marked uncertain)"
            }
            return null
        }
        return total
    }
}
