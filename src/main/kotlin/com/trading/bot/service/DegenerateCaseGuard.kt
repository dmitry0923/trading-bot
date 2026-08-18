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
 *   1. WIDE_SPREAD — спред котировок выше порога (per-instrument или global);
 *   2. PRICE_GAP — открывающий гэп на последней свече выше порога;
 *   3. DEPOSITARY_PAUSE — consecutiveZeroVolumeBars подряд нулевых
 *      по объёму свечей (депозитарная/торговая пауза).
 *
 * Проверки по свечам fail-closed при недостатке данных (пустой кэш на старте).
 * Мастер-выключатель: [RiskConfig.degenerateCaseGuardEnabled] (false — pass-through);
 * отдельные проверки отключаются порогом <= 0.
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
     * @return причина блокировки входа ("WIDE_SPREAD" / "PRICE_GAP" / "DEPOSITARY_PAUSE")
     *   или null, если вход допустим.
     */
    suspend fun blockReason(
        ticker: String,
        timeframe: String,
    ): String? {
        if (!config.degenerateCaseGuardEnabled) return null

        val spec = instrumentsConfig.find(ticker)
        val spreadThreshold = spec?.effectiveMaxSpreadPercent(config.maxSpreadPercent) ?: config.maxSpreadPercent
        val gapThreshold = spec?.effectiveMaxGapPercent(config.maxGapPercent) ?: config.maxGapPercent

        val snapshot = alorClient.getMarketSnapshot(ticker)
        if (snapshot == null) {
            logger.warn { "Market snapshot unavailable for $ticker — entry blocked (fail-closed)" }
            return "NO_MARKET_DATA"
        }
        if (
            DegenerateCaseDetector.isWideSpread(
                snapshot.bid,
                snapshot.ask,
                snapshot.currentPrice,
                spreadThreshold,
            )
        ) {
            logger.warn { "Wide spread for $ticker (> ${spreadThreshold}%) — entry blocked" }
            return "WIDE_SPREAD"
        }

        val candles = candleCache.getRecentCandles(ticker, timeframe, lookbackBars)
        if (candles.isEmpty()) {
            logger.warn { "No candle data for $ticker — entry blocked (fail-closed)" }
            return "NO_CANDLE_DATA"
        }
        if (DegenerateCaseDetector.isGap(candles, gapThreshold)) {
            logger.warn { "Price gap for $ticker (> ${gapThreshold}%) — entry blocked" }
            return "PRICE_GAP"
        }
        if (DegenerateCaseDetector.isDepositaryPause(candles, config.consecutiveZeroVolumeBars)) {
            logger.warn { "Depositary pause for $ticker (${config.consecutiveZeroVolumeBars} zero-volume bars) — entry blocked" }
            return "DEPOSITARY_PAUSE"
        }
        return null
    }
}
