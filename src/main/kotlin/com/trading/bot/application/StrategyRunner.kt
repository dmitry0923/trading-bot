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
 * Итог запуска всех стратегий: победитель (максимальная уверенность) и все
 * решения для журналирования. Ти-брейк — порядок регистрации (детерминирован).
 */
data class StrategyResult(
    val winnerId: String,
    val decision: StrategyDecision,
    val all: Map<String, StrategyDecision>,
)

/**
 * Запускает ВСЕ зарегистрированные стратегии параллельно и выбирает победителя
 * по максимальной уверенности. Не знает ни об одной конкретной стратегии:
 * реализации приходят как [List] интерфейса [Strategy] (Spring-инжекция).
 *
 * Стратегия, упавшая с исключением, участвует как HOLD с нулевой уверенностью —
 * цикл не прерывается.
 */
@Component
class StrategyRunner(
    private val strategies: List<Strategy>,
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

        val evaluated =
            coroutineScope {
                strategies
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

        val winner = evaluated.maxByOrNull { it.second.confidence }!!
        meterRegistry.counter("strategy.runner.winner", Tags.of("strategy", winner.first)).increment()
        return StrategyResult(winner.first, winner.second, evaluated.toMap())
    }
}
