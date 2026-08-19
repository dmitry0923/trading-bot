--liquibase formatted sql
--changeset dmitry:026

-- Дельта-модель close fills: накопительный счётчик исполненных лотов close-ордера.
-- Позволяет обрабатывать дублирующие WS events без повторного закрытия позиции.
-- Значение сбрасывается при начале нового закрытия (pendingClose=true).

ALTER TABLE positions ADD COLUMN cumulative_close_fill_qty INTEGER NOT NULL DEFAULT 0;
