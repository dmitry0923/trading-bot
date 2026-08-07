--liquibase formatted sql
--changeset dmitry:013

-- Трейсинг цикла: cycleId (= trace_id из TraceContext) проставляется на позицию
-- при входе, чтобы проследить цепочку WS-тик -> промпт -> LLM -> ордер -> позиция
-- (JSON-логи, agent_logs, событие PositionOpened).
ALTER TABLE positions ADD COLUMN IF NOT EXISTS cycle_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_positions_cycle_id ON positions(cycle_id);
