package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.DegenerateCaseDetector
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Пре-входной guard вырожденных рыночных случаев (roadmap 13.3.5).
 *
 * Проверяет перед входом в позицию:
 *   1. MARKET_SNAPSHOT_UNAVAILABLE — снэпшот котировок недоступен;
 *   2. WIDE_SPREAD — спред котировок выше порога (per-instrument или global);
 *   3. INSUFFICIENT_CANDLE_DATA — недостаточно свечей для проверки гэпа/паузы;
 *   4. PRICE_GAP — открывающий гэп на последней свече выше порога;
 *   5. DEPOSITARY_PAUSE — consecutiveZeroVolumeBars подряд нулевых
 *      по объёму свечей (депозитарная/торговая пауза).
 *
 * Все проверки fail-closed: при недостатке данных вход блокируется
 * (UNKNOWN ≠ SAFE). Мастер-выключатель: [RiskConfig.degenerateCaseGuardEnabled]
 * (false — pass-through); отдельные проверки отключаются порогом <= 0.
 */
@Component
class DegenerateCaseGuard(
    private val config: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val alorClient: AlorClient,
    private val candleCache: CandleCacheService,
) {
    private val logger = KotlinLogging.logger {}

    private val lookbackBars: Int
        get() = maxOf(config.consecutiveZeroVolumeBars, 2)

    /**
     * Результат проверки guard'а.
     *
     * - [Allowed] — все проверки пройдены, вход допустим.
     * - [Blocked] — вход заблокирован по указанной причине.
     */
    sealed interface GuardResult {
        data object Allowed : GuardResult

        data class Blocked(
            val reason: String,
        ) : GuardResult
    }

    /**
     * Проверяет, не является ли случай вырожденным.
     *
     * @return [GuardResult.Allowed], если вход допустим;
     *   [GuardResult.Blocked] с причиной иначе.
     */
    suspend fun check(
        ticker: String,
        timeframe: String,
    ): GuardResult {
        if (!config.degenerateCaseGuardEnabled) return GuardResult.Allowed

        val spec = instrumentsConfig.find(ticker)
        val spreadThreshold = spec?.effectiveMaxSpreadPercent(config.maxSpreadPercent) ?: config.maxSpreadPercent
        val gapThreshold = spec?.effectiveMaxGapPercent(config.maxGapPercent) ?: config.maxGapPercent

        val snapshot = alorClient.getMarketSnapshot(ticker)
        if (snapshot == null) {
            logger.warn { "Market snapshot unavailable for $ticker — entry blocked (fail-closed)" }
            return GuardResult.Blocked("MARKET_SNAPSHOT_UNAVAILABLE")
        }
        if (
            DegenerateCaseDetector.isWideSpread(
                snapshot.bid,
                snapshot.ask,
                snapshot.currentPrice,
                spreadThreshold,
            )
        ) {
            logger.warn { "Wide spread for $ticker (> $spreadThreshold%) — entry blocked" }
            return GuardResult.Blocked("WIDE_SPREAD")
        }

        val candles = candleCache.getRecentCandles(ticker, timeframe, lookbackBars)
        if (candles.size < lookbackBars) {
            logger.warn {
                "Insufficient candle data for $ticker: ${candles.size}/$lookbackBars bars " +
                    "— entry blocked (fail-closed)"
            }
            return GuardResult.Blocked("INSUFFICIENT_CANDLE_DATA")
        }
        if (DegenerateCaseDetector.isGap(candles, gapThreshold)) {
            logger.warn { "Price gap for $ticker (> $gapThreshold%) — entry blocked" }
            return GuardResult.Blocked("PRICE_GAP")
        }
        if (DegenerateCaseDetector.isDepositaryPause(candles, config.consecutiveZeroVolumeBars)) {
            logger.warn { "Depositary pause for $ticker (${config.consecutiveZeroVolumeBars} zero-volume bars) — entry blocked" }
            return GuardResult.Blocked("DEPOSITARY_PAUSE")
        }
        return GuardResult.Allowed
    }

    /**
     * Обратно совместимая обёртка: возвращает причину блокировки или null.
     */
    suspend fun blockReason(
        ticker: String,
        timeframe: String,
    ): String? =
        when (val result = check(ticker, timeframe)) {
            is GuardResult.Allowed -> null
            is GuardResult.Blocked -> result.reason
        }
}
