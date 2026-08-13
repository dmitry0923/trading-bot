package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.DegenerateCaseDetector
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Пре-входной guard вырожденных рыночных случаев (roadmap 13.3.5).
 *
 * Проверяет перед входом в позицию:
 *   1. [WIDE_SPREAD] — спред котировок выше [RiskConfig.maxSpreadPercent] (fail-open:
 *      нет снэпшота — пропускаем, как в [AlorClient.placeMarketOrder]);
 *   2. [PRICE_GAP] — открывающий гэп на последней свече выше [RiskConfig.maxGapPercent];
 *   3. [DEPOSITARY_PAUSE] — [RiskConfig.consecutiveZeroVolumeBars] подряд нулевых
 *      по объёму свечей (депозитарная/торговая пауза).
 *
 * Проверки по свечам fail-open при недостатке данных (пустой кэш на старте).
 * Мастер-выключатель: [RiskConfig.degenerateCaseGuardEnabled] (false — pass-through);
 * отдельные проверки отключаются порогом <= 0.
 */
@Component
class DegenerateCaseGuard(
    private val config: RiskConfig,
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

        val snapshot = alorClient.getMarketSnapshot(ticker)
        if (
            snapshot != null &&
            DegenerateCaseDetector.isWideSpread(
                snapshot.bid,
                snapshot.ask,
                snapshot.currentPrice,
                config.maxSpreadPercent,
            )
        ) {
            logger.warn { "Wide spread for $ticker (> ${config.maxSpreadPercent}%) — entry blocked" }
            return "WIDE_SPREAD"
        }

        val candles = candleCache.getRecentCandles(ticker, timeframe, lookbackBars)
        if (DegenerateCaseDetector.isGap(candles, config.maxGapPercent)) {
            logger.warn { "Price gap for $ticker (> ${config.maxGapPercent}%) — entry blocked" }
            return "PRICE_GAP"
        }
        if (DegenerateCaseDetector.isDepositaryPause(candles, config.consecutiveZeroVolumeBars)) {
            logger.warn { "Depositary pause for $ticker (${config.consecutiveZeroVolumeBars} zero-volume bars) — entry blocked" }
            return "DEPOSITARY_PAUSE"
        }
        return null
    }
}
