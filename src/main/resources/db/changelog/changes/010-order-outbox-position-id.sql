--liquibase formatted sql
--changeset dmitry:010

-- Связь outbox-строки с позицией (стейт-машина входов/закрытий, сверка partial fills).
ALTER TABLE order_outbox ADD COLUMN IF NOT EXISTS position_id BIGINT;

-- Бэкфилл из payload (старые строки уже содержали positionId в JSON).
UPDATE order_outbox
SET position_id = (payload->>'positionId')::bigint
WHERE position_id IS NULL AND payload->>'positionId' IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_position_id ON order_outbox(position_id);
