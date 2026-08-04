--liquibase formatted sql
--changeset dmitry:005

-- Append-only лог торговых решений (Event Sourcing light).
-- Никаких UPDATE/DELETE: каждая запись — неизменяемое событие для audit trail.
CREATE TABLE IF NOT EXISTS trade_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT NOW(),
    sequence_number BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trade_events_aggregate ON trade_events(aggregate_id, sequence_number);
CREATE INDEX IF NOT EXISTS idx_trade_events_occurred_at ON trade_events(occurred_at);
