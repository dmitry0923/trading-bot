--liquibase formatted sql
--changeset trading-bot:031-close-cancel-pending
ALTER TABLE positions ADD COLUMN IF NOT EXISTS close_cancel_pending BOOLEAN NOT NULL DEFAULT false;
