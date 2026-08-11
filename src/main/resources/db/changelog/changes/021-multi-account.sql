--liquibase formatted sql
--changeset dmitry:021

-- Multi-account (roadmap v2.2): несколько Alor-портфелей через общий конвейер.
--
-- trading_accounts — реестр аккаунтов (портфелей) с персональными лимитами:
--   - alor_portfolio — имя портфеля Alor (как AlorConfig.portfolio);
--   - aum_rub — переопределение депозита (NULL = реальный баланс Alor);
--   - max_open_positions — лимит открытых позиций (NULL = RiskConfig.maxOpenPositions);
--   - max_daily_loss_rub — персональный дневной лимит убытка (NULL = % AUM);
--   - weight — относительный вес при распределении сигналов (round-robin с весом).
--
-- Пустая таблица = legacy single-account режим: используется AlorConfig.portfolio,
-- позиции без account_id.
--
-- account_id добавляется на:
--   - positions — принадлежность позиции аккаунту (маршрутизация ордеров, отчёты);
--   - order_outbox — маршрутизация outbox-доставки в нужный портфель;
--   - daily_risk_snapshot — персональный дневной лимит/снапшот на аккаунт
--     (UNIQUE пересоздаётся как (trade_date, account_id): несколько NULL-строк
--     для legacy global-режима допустимы, т.к. NULL в уникальном ключе не равен NULL).
--
-- FK на positions (PARTITION BY RANGE (opened_at)) задаётся на родительской
-- таблице и автоматически распространяется на партиции.

CREATE TABLE IF NOT EXISTS trading_accounts (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    alor_portfolio VARCHAR(64) NOT NULL,
    exchange VARCHAR(20) NOT NULL DEFAULT 'MOEX',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    aum_rub NUMERIC(19,6),
    max_open_positions INT,
    max_daily_loss_rub NUMERIC(19,6),
    weight INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trading_accounts_enabled ON trading_accounts(enabled);

ALTER TABLE positions ADD COLUMN IF NOT EXISTS account_id BIGINT;
ALTER TABLE order_outbox ADD COLUMN IF NOT EXISTS account_id BIGINT;
ALTER TABLE daily_risk_snapshot ADD COLUMN IF NOT EXISTS account_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_positions_account_id ON positions(account_id) WHERE account_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_account_id ON order_outbox(account_id) WHERE account_id IS NOT NULL;

ALTER TABLE positions ADD CONSTRAINT fk_positions_account FOREIGN KEY (account_id) REFERENCES trading_accounts(id);
ALTER TABLE order_outbox ADD CONSTRAINT fk_outbox_account FOREIGN KEY (account_id) REFERENCES trading_accounts(id);

ALTER TABLE daily_risk_snapshot DROP CONSTRAINT IF EXISTS uq_daily_risk_snapshot_date;
-- Уникальность дневных снапшотов:
--   - (trade_date, account_id) — multi-account строки;
--   - отдельный частичный индекс (trade_date) WHERE account_id IS NULL — legacy global-строки.
-- В PostgreSQL NULL != NULL, поэтому составной UNIQUE-констрейнт не конфликтует для
-- NULL-аккаунтов — нужен частичный индекс.
CREATE UNIQUE INDEX IF NOT EXISTS uq_daily_risk_snapshot_date_account ON daily_risk_snapshot(trade_date, account_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_daily_risk_snapshot_date_global ON daily_risk_snapshot(trade_date) WHERE account_id IS NULL;
