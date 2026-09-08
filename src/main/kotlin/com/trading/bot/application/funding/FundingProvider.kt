package com.trading.bot.application.funding

/**
 * Источник актуального funding. Возвращает снапшот или null, если данные недоступны.
 *
 * Реализации:
 * - [ConfiguredFundingProvider] — конфигурационное значение (SIM/backtest/fallback);
 * - [MoexFundingProvider] — актуальный MOEX (LIVE-источник).
 */
interface FundingProvider {
    suspend fun currentSnapshot(ticker: String): FundingSnapshot?
}
