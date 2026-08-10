package com.trading.bot.application

import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Component

/**
 * Итог запуска стратегий: победитель (максимальная взвешенная уверенность) и все
 * решения для журналирования. Ти-брейк — порядок регистрации (детерминирован).
 */
data class StrategyResult(
    val winnerId: String,
    val decision: StrategyDecision,
    val all: Map<String, StrategyDecision>,
)

/**
 * Запускает стратегии параллельно и выбирает победителя по максимальной
 * взвешенной уверенности. Не знает ни об одной конкретной стратегии: реализации
 * приходят как [List] интерфейса [Strategy] (Spring-инжекция).
 *
 * Учёт рыночного режима ([StrategyContext.regime]) — через [StrategySelector]:
 *   1. Жёсткий фильтр: при [com.trading.bot.domain.risk.PerTickerRegime.blocksEntry]
 *      или отсутствии совместимых стратегий — HOLD, цикл стратегий не запускается;
 *      иначе запускаются ТОЛЬКО допустимые для режима стратегии.
 *   2. Мягкое взвешивание: confidence каждого решения умножается на
 *      [StrategySelector.fitScore] (0..1) — режим влияет на выбор победителя.
 *
 * Если режим в контексте отсутствует — поведение прежнее (все стратегии, без
 * взвешивания). Стратегия, упавшая с исключением, участвует как HOLD с нулевой
 * уверенностью — цикл не прерывается.
 */
@Component
class StrategyRunner(
    private val strategies: List<Strategy>,
    private val strategySelector: StrategySelector,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun runAll(context: StrategyContext): StrategyResult {
        if (strategies.isEmpty()) {
            return StrategyResult(
                winnerId = "NONE",
                decision = StrategyDecision.hold(context.snapshot.currentPrice, "No strategies registered"),
                all = emptyMap(),
            )
        }

        val regime = context.regime
        if (regime != null && regime.blocksEntry) {
            meterRegistry
                .counter(
                    "strategy.runner.blocked",
                    Tags.of("ticker", context.ticker, "reason", regime.describe()),
                ).increment()
            logger.info { "Regime blocks entries for ${context.ticker}: ${regime.describe()}" }
            return StrategyResult(
                winnerId = "NONE",
                decision =
                    StrategyDecision.hold(
                        context.snapshot.currentPrice,
                        "Blocked by regime: ${regime.describe()}",
                    ),
                all = emptyMap(),
            )
        }

        val eligibleIds = regime?.let { strategySelector.eligibleStrategyIds(it) }
        val toRun = strategies.filter { eligibleIds == null || it.id in eligibleIds }
        if (toRun.isEmpty()) {
            return StrategyResult(
                winnerId = "NONE",
                decision =
                    StrategyDecision.hold(
                        context.snapshot.currentPrice,
                        "No strategies compatible with regime: ${regime?.describe() ?: "unknown"}",
                    ),
                all = emptyMap(),
            )
        }

        val evaluated =
            coroutineScope {
                toRun
                    .map { strategy ->
                        async {
                            val decision =
                                try {
                                    strategy.evaluate(context)
                                } catch (e: Exception) {
                                    logger.error(e) { "Strategy ${strategy.id} failed for ${context.ticker}" }
                                    StrategyDecision.hold(context.snapshot.currentPrice, "Strategy error: ${e.message}")
                                }
                            strategy.id to decision
                        }
                    }.awaitAll()
            }

        // Мягкое взвешивание уверенности по режиму (0 при отсутствии режима — нейтрально).
        val weighted =
            evaluated.map { (id, decision) ->
                val fit = if (regime != null) strategySelector.fitScore(id, regime) else 1.0
                val weightedDecision =
                    if (fit < 1.0) {
                        decision.copy(confidence = (decision.confidence * fit).coerceIn(0.0, 1.0))
                    } else {
                        decision
                    }
                id to weightedDecision
            }

        val winner =
            weighted.maxByOrNull { it.second.confidence }
                ?: return StrategyResult(
                    winnerId = "NONE",
                    decision = StrategyDecision.hold(context.snapshot.currentPrice, "No strategies evaluated"),
                    all = emptyMap(),
                )
        meterRegistry.counter("strategy.runner.winner", Tags.of("strategy", winner.first)).increment()
        if (regime != null && evaluated.size < strategies.size) {
            meterRegistry
                .counter("strategy.runner.filtered", Tags.of("ticker", context.ticker))
                .increment()
        }
        return StrategyResult(winner.first, winner.second, weighted.toMap())
    }
}
