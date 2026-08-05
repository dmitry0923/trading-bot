--liquibase formatted sql
--changeset dmitry:007

-- Мульти-таймфрейм: каждая стратегия теперь привязана к таймфрейму свечей.
ALTER TABLE strategies ADD COLUMN IF NOT EXISTS timeframe VARCHAR(20) NOT NULL DEFAULT 'MINUTE_10';

CREATE INDEX IF NOT EXISTS idx_strategies_timeframe ON strategies(timeframe);
