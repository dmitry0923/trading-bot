--changeset dmitry:038 splitStatements:false endDelimiter:;

-- Исторические тики и реконструированный BBO из платных архивов MOEX
-- («Реестры заявок и сделок», Тип В / Тип А). Закупка и формат — docs/22.
--
-- Отличие от 037 (microstructure_snapshots): та таблица — forward L1 из live
-- WebSocket Alor с бакетами по microstructure.bucket-ms. Здесь — историческая
-- реконструкция из файлов MOEX, и она намеренно кладётся в отдельные таблицы:
--
--   1) источник другой (файлы, не WebSocket) и своя точка отсчёта (MOMENT
--      в MOEX отсчитывает время сделки, а не приём котировки);
--   2) Tick — отдельная сущность (в 037 тики не сохранялись вовсе, чтобы
--      не жечь Storage: их частота на порядки выше торговой);
--   3) поле времени неделимое (TIMESTAMP) — обе таблицы пишутся секундными
--      бакетами, как и forward-слой, поэтому millisecond-колонка не нужна
--      и UNIQUE (ticker, ts) остаётся уникальным ключом бакета.
--
-- Семантика окна чтения — строго-before как у свечей: бакет с началом в `to`
-- ещё не закрыт и не должен попадать в point-in-time признаки (lookahead).

-- Тики (сделки). Одна строка = одна сделка.
-- Источник: YYYYMMDD_fut_deal.csv, колонки
-- #SYMBOL,SYSTEM,MOMENT,ID_DEAL,PRICE_DEAL,VOLUME,OPEN_POS,DIRECTION
-- MOMENT — 17 цифр yyyyMMddHHmmssSSS (миллисекунды, время сделки).
-- DIRECTION: 'B' — инициатор покупатель, 'S' — инициатор продавец.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        EXECUTE 'CREATE EXTENSION timescaledb';
    END IF;

    EXECUTE 'DROP TABLE IF EXISTS ticks';

    EXECUTE '
        CREATE TABLE ticks (
            ticker VARCHAR(20) NOT NULL,
            ts TIMESTAMP NOT NULL,
            deal_id BIGINT NOT NULL,
            price NUMERIC(19,8) NOT NULL,
            volume BIGINT NOT NULL,
            direction VARCHAR(1) NOT NULL,
            open_interest BIGINT,
            -- TimescaleDB требует, чтобы уникальный индекс гипертаблицы включал
            -- колонку партиционирования: UNIQUE (ticker, deal_id) без ts даёт
            -- ERROR "cannot create a unique index without the column ts" и весь
            -- блок падает. Поэтому ts входит в ключ уникальности.
            UNIQUE (ticker, ts, deal_id)
        )';

    EXECUTE 'SELECT create_hypertable(''ticks'', ''ts'', chunk_time_interval => INTERVAL ''1 day'')';

    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ticks_ticker_ts
        ON ticks(ticker, ts DESC)';

    -- Только реконструированный BBO секундными бакетами (те же поля и та же
    -- семантика, что у microstructure_snapshots, но исторические).
    EXECUTE 'DROP TABLE IF EXISTS orderbook_bbo';

    EXECUTE '
        CREATE TABLE orderbook_bbo (
            ticker VARCHAR(20) NOT NULL,
            ts TIMESTAMP NOT NULL,
            quote_count BIGINT NOT NULL,
            price NUMERIC(19,8),
            bid NUMERIC(19,8),
            ask NUMERIC(19,8),
            bid_size BIGINT,
            ask_size BIGINT,
            spread_bps NUMERIC(19,8),
            obi NUMERIC(9,6),
            microprice NUMERIC(19,8),
            microprice_deviation_bps NUMERIC(19,8),
            UNIQUE (ticker, ts)
        )';

    EXECUTE 'SELECT create_hypertable(''orderbook_bbo'', ''ts'', chunk_time_interval => INTERVAL ''1 day'')';

    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_orderbook_bbo_ticker_ts
        ON orderbook_bbo(ticker, ts DESC)';
-- Fail-closed. Шаблон 012/037 здесь НЕ копируется: `EXCEPTION WHEN OTHERS +
-- RAISE WARNING` проглатывает ошибку, и Liquibase отмечает changeset
-- применённым, хотя таблиц нет. Именно так был потерян 038: UNIQUE (ticker,
-- deal_id) без колонки партиционирования `ts` роняет create_hypertable, и
-- весь блок молча откатывается. Ошибка наружу = миграция падает громко.
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'migration 038 (ticks/orderbook_bbo) failed: %', SQLERRM;
END $$;
