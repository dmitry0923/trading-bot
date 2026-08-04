--liquibase formatted sql
--changeset dmitry:007

CREATE UNIQUE INDEX IF NOT EXISTS uq_trade_events_aggregate_sequence
    ON trade_events(aggregate_id, sequence_number);
