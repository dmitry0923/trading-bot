--liquibase formatted sql
--changeset dmitry:025

-- Атомарная резервация входа (EXEC-002, MR-B): защита от двойного входа в позицию
-- по одному (ticker, account).
--
-- Глобальный partial UNIQUE INDEX на positions невозможен: таблица партиционирована
-- по RANGE(opened_at) (019), а уникальные индексы партиционированных таблиц обязаны
-- включать ключ партиционирования. Поэтому слот «открытая позиция / вход в полёте»
-- держится в отдельной малой таблице:
--   - резервация создаётся ДО отправки entry-ордера (placeEntryOrder);
--   - освобождается при закрытии позиции (finalizeClosePosition), определённом отказе
--     биржи, abandonEntry или по таймауту stale-резерваций (cleanup);
--   - INSERT с ON CONFLICT даёт атомарный слот: из N конкурентных входов выигрывает один.
--
-- account_id NULL = legacy single-account (как в positions); COALESCE(account_id, 0)
-- делает слот уникальным и для legacy-пути (в PostgreSQL NULL != NULL).

CREATE TABLE IF NOT EXISTS entry_reservations (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    account_id BIGINT,
    direction VARCHAR(10) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_entry_reservations_ticker_account
    ON entry_reservations (ticker, COALESCE(account_id, 0));

ALTER TABLE entry_reservations ADD CONSTRAINT fk_entry_reservations_account
    FOREIGN KEY (account_id) REFERENCES trading_accounts(id);
