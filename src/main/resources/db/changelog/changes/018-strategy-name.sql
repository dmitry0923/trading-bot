--liquibase formatted sql
--changeset dmitry:018

-- Идентификатор стратегии, сгенерировавшей решение (победитель StrategyRunner).
ALTER TABLE strategies ADD COLUMN IF NOT EXISTS strategy_name VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_strategies_strategy_name ON strategies(strategy_name);
