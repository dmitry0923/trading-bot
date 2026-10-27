--liquibase formatted sql
--changeset dmitry:045 splitStatements:false

-- Индекс под листинги позиций (скан-14 P2).
--
-- `positions` партиционирована по `opened_at`, а PK — `(id, opened_at)`: он не
-- используется для `ORDER BY opened_at DESC`, который выполняют findAll/findClosed/
-- findByStatus/findOpenByAccount. Без индекса каждый такой запрос — Append всех
-- партиций + Sort всей истории; при накоплении сделок деградирует линейно.
CREATE INDEX IF NOT EXISTS idx_positions_opened_at ON positions (opened_at DESC);

-- Поиск дублей портфеля Alor (валидация create/update в TradingAccountService).
-- Не UNIQUE: на существующей БД дубли уже могли появиться, и жёсткий индекс заблокировал
-- бы старт приложения; запрет на НОВЫЕ дубли даёт сервисная проверка.
CREATE INDEX IF NOT EXISTS idx_trading_accounts_portfolio ON trading_accounts (lower(alor_portfolio));

DO $$
BEGIN
    RAISE NOTICE 'positions(opened_at) и trading_accounts(alor_portfolio) indexes ensured';
END $$;
