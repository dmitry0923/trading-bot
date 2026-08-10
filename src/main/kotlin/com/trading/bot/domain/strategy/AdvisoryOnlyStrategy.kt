package com.trading.bot.domain.strategy

/**
 * Маркер стратегии, которая НЕ является источником торгового сигнала (C-001).
 *
 * Агентные/LLM-реализации [Strategy] помечаются этим интерфейсом: они могут
 * использоваться для A/B-эксперимента и аналитики
 * ([com.trading.bot.application.strategy.DiscretionaryStrategy]),
 * но не участвуют в конкуренции за сигнал в [com.trading.bot.application.StrategyRunner].
 * Единственный источник сигнала — детерминированные стратегии; LLM работает как
 * советник (advisory layer) вне критического исполнительного пути.
 */
interface AdvisoryOnlyStrategy : Strategy
