package com.trading.bot.backtest

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Spring-wiring для [LiveStrategyBacktestSignalGenerator].
 *
 * Сам генератор — НЕ Spring-бин (детерминированный класс без зависимостей),
 * поэтому бин создаётся здесь только при `bt.agent.live-strategies=true`
 * (env `BT_AGENT_LIVE_STRATEGIES`). @Primary разрешает конфликт с
 * [DeterministicBacktestSignalGenerator] / [AgentBacktestSignalGenerator]:
 * live-стратегии имеют приоритет, если флаги пересекаются.
 */
@Configuration
class BacktestSignalGeneratorConfig {
    @Bean
    @Primary
    @ConditionalOnProperty(name = ["bt.agent.live-strategies"], havingValue = "true")
    fun liveStrategyBacktestSignalGenerator(): BacktestSignalGenerator = LiveStrategyBacktestSignalGenerator()
}
