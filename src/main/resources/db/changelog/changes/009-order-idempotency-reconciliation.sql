--liquibase formatted sql
--changeset dmitry:009

-- Идемпотентность ордеров (Alor): уникальный ключ на логический ордер +
-- счётчик повторных попыток доставки (bounded retry с State Reconciliation).
ALTER TABLE order_outbox ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);
ALTER TABLE order_outbox ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;

-- Бэкфилл ключа из payload (старые строки уже содержали idempotencyKey в JSON).
UPDATE order_outbox
SET idempotency_key = payload->>'idempotencyKey'
WHERE idempotency_key IS NULL AND payload->>'idempotencyKey' IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_idempotency_key ON order_outbox(idempotency_key);

-- Позиции: стейт-машина подтверждения входов/закрытий (partial fills, double-execution guard).
ALTER TABLE positions ADD COLUMN IF NOT EXISTS close_order_id VARCHAR(100);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS pending_close BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE positions ADD COLUMN IF NOT EXISTS pending_entry BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE positions ADD COLUMN IF NOT EXISTS realized_pnl NUMERIC(19,6) NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_positions_pending_close ON positions(pending_close) WHERE pending_close = TRUE;
CREATE INDEX IF NOT EXISTS idx_positions_pending_entry ON positions(pending_entry) WHERE pending_entry = TRUE;
