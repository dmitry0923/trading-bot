--liquibase formatted sql
--changeset dmitry:006

ALTER TABLE order_outbox
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE order_outbox
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP NOT NULL DEFAULT NOW();

DROP INDEX IF EXISTS idx_outbox_status_created;
CREATE INDEX IF NOT EXISTS idx_outbox_status_next_attempt
    ON order_outbox(status, next_attempt_at);
