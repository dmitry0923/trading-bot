package com.trading.bot.service

import com.trading.bot.client.MoexClient
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.Black76Calculator
import com.trading.bot.domain.risk.OptionQuote
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Подразумеваемая волатильность фьючерса Si по опционной таблице FORTS (Black-76).
 *
 * - Полная таблица опционов загружается из ISS (клиентская фильтрация по ASSETCODE)
 * - ATM-страйк ближайшего ликвидного месяца: месяц с максимально ранней экспирацией
 *   и OPENPOSITION >= [RiskConfig.regimeMinOpenPosition], страйк ближайший к
 *   UNDERLYINGSETTLEPRICE, из пары call/put выбирается более ликвидный
 * - IV инвертируется по премии (LAST, fallback BID) через Black-76
 * - Кэш (TTL [RiskConfig.impliedVolatilityCacheTtlMinutes]) обновляется фоном
 */
@Service
class ImpliedVolatilityService(
    private val riskConfig: RiskConfig,
    private val moexClient: MoexClient,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Текущий ATM-срез Si (IV в % + параметры выбранного опциона). */
    data class SiIvSnapshot(
        val ivPercent: Double,
        val underlyingPrice: BigDecimal,
        val atmStrike: BigDecimal,
        val expiry: LocalDate,
        val openInterest: Long,
    )

    @Volatile
    private var snapshot: SiIvSnapshot? = null

    @Volatile
    private var lastFetchedAt: Instant? = null

    private fun cacheTtl(): Duration = Duration.ofMinutes(riskConfig.impliedVolatilityCacheTtlMinutes)

    /**
     * Текущая подразумеваемая волатильность Si в % (например 25.0 = 25% годовых).
     */
    fun impliedVolatilityPercent(): Double? = snapshot?.ivPercent

    /**
     * Полный срез ATM-опциона (для диагностики и метрик).
     */
    fun snapshot(): SiIvSnapshot? = snapshot

    /**
     * Обновляет кэш IV (не чаще [cacheTtl]).
     */
    suspend fun refresh() {
        val lastFetch = lastFetchedAt
        if (lastFetch != null && Duration.between(lastFetch, Instant.now()) < cacheTtl()) return
        lastFetchedAt = Instant.now()
        if (!riskConfig.impliedVolatilityEnabled) return

        val options = moexClient.getFortsOptions()
        val target = options.filter { it.assetCode == riskConfig.impliedVolatilityTicker }
        val selected = selectAtm(target, LocalDate.now(), riskConfig.regimeMinOpenPosition)
        if (selected != null) {
            snapshot = selected
            meterRegistry.gauge("risk.implied.volatility", selected.ivPercent)
            logger.info {
                "${riskConfig.impliedVolatilityTicker} IV = ${selected.ivPercent}% " +
                    "(strike=${selected.atmStrike}, expiry=${selected.expiry}, OI=${selected.openInterest}, " +
                    "underlying=${selected.underlyingPrice})"
            }
        } else {
            logger.warn { "IV unavailable: no liquid ATM option for ${riskConfig.impliedVolatilityTicker}" }
        }
    }

    /**
     * Выбирает ATM-опцион ближайшего ликвидного месяца и считает IV по премии.
     *
     * Ликвидный месяц — самая ранняя экспирация с суммарным OPENPOSITION не ниже
     * [minOpenPosition]; при отсутствии таковых — ближайшая экспирация с ненулевым
     * открытым интересом, иначе самая ранняя экспирация вообще.
     */
    private fun selectAtm(
        quotes: List<OptionQuote>,
        today: LocalDate,
        minOpenPosition: Long,
    ): SiIvSnapshot? {
        if (quotes.isEmpty()) return null
        val future = quotes.filter { it.lastTradeDate >= today }
        val pool = future.ifEmpty { quotes }
        val byExpiry = pool.groupBy { it.lastTradeDate }
        val expiries = byExpiry.keys.sorted()
        if (expiries.isEmpty()) return null

        val openInterest = { expiry: LocalDate -> byExpiry.getValue(expiry).sumOf { it.openPosition } }
        val expiry =
            expiries.firstOrNull { openInterest(it) >= minOpenPosition }
                ?: expiries.firstOrNull { openInterest(it) > 0L }
                ?: expiries.first()

        val withPrice =
            byExpiry
                .getValue(expiry)
                .filter {
                    it.underlyingSettlePrice != null && it.underlyingSettlePrice > BigDecimal.ZERO
                }
        val underlying = withPrice.firstOrNull()?.underlyingSettlePrice ?: return null
        val atm =
            withPrice
                .sortedWith(compareBy({ (it.strike - underlying).abs() }, { -it.openPosition }))
                .first()

        val premium =
            when {
                atm.last != null && atm.last > BigDecimal.ZERO -> atm.last.toDouble()
                atm.bid != null && atm.bid > BigDecimal.ZERO -> atm.bid.toDouble()
                else -> return null
            }

        val days = Duration.between(today.atStartOfDay(), atm.lastTradeDate.atStartOfDay()).toDays()
        if (days <= 0L) return null
        val yearsToExpiry = days / 365.0
        val iv =
            Black76Calculator.impliedVolatility(
                forward = underlying.toDouble(),
                strike = atm.strike.toDouble(),
                yearsToExpiry = yearsToExpiry,
                kind = atm.kind,
                premium = premium,
            ) ?: return null

        return SiIvSnapshot(
            ivPercent = iv * 100.0,
            underlyingPrice = underlying,
            atmStrike = atm.strike,
            expiry = atm.lastTradeDate,
            openInterest = openInterest(expiry),
        )
    }

    /**
     * Фоновая подкачка опционной таблицы и IV.
     */
    @Scheduled(fixedDelay = 900_000)
    fun scheduledRefresh() {
        scope.launch {
            try {
                refresh()
            } catch (e: Exception) {
                logger.warn(e) { "Implied volatility refresh failed" }
            }
        }
    }
}
