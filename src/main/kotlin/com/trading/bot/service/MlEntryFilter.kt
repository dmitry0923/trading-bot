package com.trading.bot.service

import com.trading.bot.config.MlConfig
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
 * Политика отказов:
 * - `ml.enabled=false` или `ml.filter.enabled=false` — фильтр выключен (pass-through);
 * - фильтр включён, модель недоступна — БЛОК (fail-closed: оператор явно включил
 *   фильтр, вход без ML-оценки недопустим);
 * - фильтр включён, данных свечей недостаточно — БЛОК (fail-closed, причина в логе);
 * - `probability < threshold` — БЛОК (result=REJECT).
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
    suspend fun shouldBlock(signal: Signal): String? {
        if (!mlConfig.enabled || !mlConfig.filter.enabled) return null
        if (signal.action != StrategyAction.BUY && signal.action != StrategyAction.SELL) return null

        val model = mlModelProvider.model
        if (!model.available) {
            return blocked(signal, "ML model unavailable (${model.unavailableReason})")
        }

        val direction = if (signal.action == StrategyAction.BUY) "LONG" else "SHORT"
        val vector =
            mlFeatureResolver.resolve(
                ticker = signal.ticker,
                at = LocalDateTime.now(),
                strategyAction = signal.action.name,
                strategyConfidence = signal.confidence,
                direction = direction,
            ) ?: return blocked(signal, "insufficient candle data for ML features")

        val probability = model.probability(vector.numericFeatures(), vector.categoricalFeatures())
        val threshold = mlConfig.filter.threshold
        if (probability < threshold) {
            meterRegistry.counter("ml.entry.filter", Tags.of("ticker", signal.ticker, "result", "REJECT")).increment()
            val reason = "win probability ${fmt(probability)} below threshold $threshold"
            logger.warn { "ML filter rejected ${signal.ticker} ${signal.action}: $reason" }
            return "ML filter: $reason"
        }
        meterRegistry.counter("ml.entry.filter", Tags.of("ticker", signal.ticker, "result", "PASS")).increment()
        return null
    }

    private fun blocked(
        signal: Signal,
        reason: String,
    ): String {
        meterRegistry.counter("ml.entry.filter", Tags.of("ticker", signal.ticker, "result", "FAIL_CLOSED")).increment()
        logger.warn { "ML filter blocked ${signal.ticker} ${signal.action}: $reason" }
        return "ML filter: $reason"
    }

    private fun fmt(value: Double): String = BigDecimal(value).setScale(4, RoundingMode.HALF_UP).toPlainString()
}
