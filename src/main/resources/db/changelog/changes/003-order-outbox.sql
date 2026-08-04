--liquibase formatted sql
--changeset dmitry:003

CREATE TABLE IF NOT EXISTS order_outbox (
    id UUID PRIMARY KEY,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    alor_order_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON order_outbox(status, created_at);
