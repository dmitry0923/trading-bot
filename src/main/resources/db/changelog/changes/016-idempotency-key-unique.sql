--liquibase formatted sql
--changeset dmitry:016

-- Защита от дублирующих outbox-рядов на один логический ордер (multi-instance/гонки):
-- idempotency_key уникален в рамках БД — повторный save с тем же ключом возвращает
-- существующую строку (см. OrderOutboxRepository.save), двойной ордер не создаётся.
CREATE UNIQUE INDEX IF NOT EXISTS uq_order_outbox_idempotency_key
    ON order_outbox (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
