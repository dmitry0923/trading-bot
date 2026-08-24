--liquibase formatted sql
--changeset trading-bot:030-protection-cumulative-fill-qty
ALTER TABLE positions ADD COLUMN IF NOT EXISTS cumulative_sl_fill_qty INTEGER NOT NULL DEFAULT 0;
ALTER TABLE positions ADD COLUMN IF NOT EXISTS cumulative_tp_fill_qty INTEGER NOT NULL DEFAULT 0;
