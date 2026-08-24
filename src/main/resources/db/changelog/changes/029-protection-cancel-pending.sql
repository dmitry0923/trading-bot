--liquibase formatted sql

--changeset trading-bot:029-protection-cancel-pending

-- Protection cancel state machine: when a cancel is requested for SL/TP,
-- the order ID must NOT be cleared until the exchange confirms cancellation.
-- This prevents duplicate protection orders (old SL still live + new SL placed).
ALTER TABLE positions ADD COLUMN IF NOT EXISTS sl_cancel_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE positions ADD COLUMN IF NOT EXISTS tp_cancel_pending BOOLEAN NOT NULL DEFAULT FALSE;
