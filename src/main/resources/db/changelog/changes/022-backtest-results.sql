--liquibase formatted sql
--changeset dmitry:022

-- Backtest: сохранение результатов (roadmap v2.2, раздел 13.7.3).
--
-- backtest_results — история прогонов бэктеста для сравнения итераций стратегии:
--   - params — параметры прогона (days, timeframe, initialCapital, SL/TP и т.п.);
--   - metrics — метрики результата (Sharpe, Sortino, MDD, PF, win rate, passable);
--   - oos — walk-forward OOS-сводка (consistency, robust, aggregateOutOfSample) —
--     заполняется эндпоинтом /api/v1/backtest/{ticker}/validate (C-002);
--   - created_at — время прогона (индекс для выборки истории по тикеру).

CREATE TABLE IF NOT EXISTS backtest_results (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(32) NOT NULL,
    params JSONB NOT NULL,
    metrics JSONB NOT NULL,
    oos JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_backtest_results_ticker_created ON backtest_results(ticker, created_at DESC);
