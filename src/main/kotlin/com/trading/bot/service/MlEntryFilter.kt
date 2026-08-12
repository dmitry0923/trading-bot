package com.trading.bot.service

import com.trading.bot.config.MlConfig
import com.trading.bot.domain.ml.MlTrendScore
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.StrategyAction
import com.trading.bot.service.ml.MlModelProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * ML-фильтр входа в торговый цикл (roadmap v2.4, раздел 13.11.5).
 *
 * Вызывается из [DecisionEngine] после прохождения risk-этапа: если прогноз
 * модели ([MlModelProvider]) для сигнала ниже `ml.filter.threshold` — вход
 * блокируется. Признаки строятся на текущий момент ([MlFeatureResolver]) с
 * реальным решением стратега (`strategy_action = signal.action`,
 * `strategy_confidence = signal.confidence`) — в отличие от скрининга, где
 * стратег ещё не отработал.
 *
 * Ядро [shouldBlock] параметризовано временем `at` и флагом `requireEnabled`,
 * поэтому тот же фильтр используется и в бэктесте (раздел 13.11.6): признаки
 * строятся на исторический момент бара, а включение фильтра управляется
 * отдельным флагом `bt.ml-filter-enabled` без влияния на live-гейт.
 *
 * Политика отказов:
 * - фильтр выключен — pass-through (`requireEnabled=true` и `ml.enabled=false`
 *   или `ml.filter.enabled=false`; `requireEnabled=false` — принудительное
 *   включение, используется только бэктестом);
 * - фильтр включён, модель недоступна — БЛОК (fail-closed: оператор явно включил
 *   фильтр, вход без ML-оценки недопустим);
 * - фильтр включён, данных свечей недостаточно — БЛОК (fail-closed, причина в логе);
 * - `probability < threshold` — БЛОК (result=REJECT);
 * - при `ml.filter.trend-gate-enabled=true`: оценка удержания тренда
 *   ([MlTrendScore]) ниже `ml.filter.trend-min-score` — БЛОК (result=REJECT,
 *   раздел 13.11.7).
 */
@Service
class MlEntryFilter(
    private val mlConfig: MlConfig,
    private val mlModelProvider: MlModelProvider,
    private val mlFeatureResolver: MlFeatureResolver,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * @return причина блокировки входа или null, если вход разрешён
     */
    suspend fun shouldBlock(signal: Signal): String? =
        shouldBlock(
            ticker = signal.ticker,
            action = signal.action,
            confidence = signal.confidence,
            at = LocalDateTime.now(),
        )

    /**
     * Ядро фильтра. Признаки строятся на момент `at` (live — сейчас, бэктест —
     * время бара). [requireEnabled]=false форсирует включение фильтра независимо
     * от `ml.enabled`/`ml.filter.enabled` (используется бэктестом, раздел 13.11.6);
     * модель при этом всё равно должна быть доступна (fail-closed).
     */
    suspend fun shouldBlock(
        ticker: String,
        action: StrategyAction,
        confidence: Double?,
        at: LocalDateTime,
        requireEnabled: Boolean = true,
    ): String? {
        if (requireEnabled && (!mlConfig.enabled || !mlConfig.filter.enabled)) return null
        if (action != StrategyAction.BUY && action != StrategyAction.SELL) return null

        val model = mlModelProvider.model
        if (!model.available) {
            return blocked(ticker, action, "ML model unavailable (${model.unavailableReason})")
        }

        val direction = if (action == StrategyAction.BUY) "LONG" else "SHORT"
        val vector =
            mlFeatureResolver.resolve(
                ticker = ticker,
                at = at,
                strategyAction = action.name,
                strategyConfidence = confidence,
                direction = direction,
            ) ?: return blocked(ticker, action, "insufficient candle data for ML features")

        val probability = model.probability(vector.numericFeatures(), vector.categoricalFeatures())
        val threshold = mlConfig.filter.threshold
        if (probability < threshold) {
            meterRegistry.counter("ml.entry.filter", Tags.of("ticker", ticker, "result", "REJECT")).increment()
            val reason = "win probability ${fmt(probability)} below threshold $threshold"
            logger.warn { "ML filter rejected $ticker $action: $reason" }
            return "ML filter: $reason"
        }

        // Опциональный тренд-гейт (раздел 13.11.7): вход требует и оценку удержания
        // тренда (модель + детерминированная сила тренда по индикаторам).
        if (mlConfig.filter.trendGateEnabled) {
            val trendScore = MlTrendScore.score(vector, probability)
            val minScore = mlConfig.filter.trendMinScore
            if (trendScore < minScore) {
                meterRegistry.counter("ml.entry.filter", Tags.of("ticker", ticker, "result", "REJECT")).increment()
                val reason = "trend score ${fmt(trendScore)} below gate $minScore"
                logger.warn { "ML filter rejected $ticker $action: $reason" }
                return "ML filter: $reason"
            }
        }

        meterRegistry.counter("ml.entry.filter", Tags.of("ticker", ticker, "result", "PASS")).increment()
        return null
    }

    private fun blocked(
        ticker: String,
        action: StrategyAction,
        reason: String,
    ): String {
        meterRegistry.counter("ml.entry.filter", Tags.of("ticker", ticker, "result", "FAIL_CLOSED")).increment()
        logger.warn { "ML filter blocked $ticker $action: $reason" }
        return "ML filter: $reason"
    }

    private fun fmt(value: Double): String = BigDecimal(value).setScale(4, RoundingMode.HALF_UP).toPlainString()
}
