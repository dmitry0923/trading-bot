--liquibase formatted sql
--changeset dmitry:046 splitStatements:false

-- Fallback для `microstructure_snapshots` на PostgreSQL БЕЗ TimescaleDB (скан-14 P2).
--
-- Миграция 037 обещала «при отсутствии timescaledb конвертация пропускается, приложение
-- стартует на обычных таблицах», но фактически всё было внутри одного DO-блока:
-- исключение (нет расширения / нет прав) откатывало ВСЮ подтранзакцию, включая
-- CREATE TABLE, а сам changeset помечался применённым. Итог: таблицы не существовало,
-- и при `microstructure.enabled=true` каждый flush падал `relation does not exist`.
--
-- Править 037 нельзя (контрольная сумма Liquibase заблокировала бы старт на уже
-- мигрированных БД), поэтому таблица создаётся здесь заново — идемпотентно:
-- на существующей Timescale-БД это no-op, на vanilla-Postgres появляется рабочая
-- обычная таблица. Структура повторяет 037 один-в-один.
CREATE TABLE IF NOT EXISTS microstructure_snapshots (
    ticker VARCHAR(20) NOT NULL,
    time TIMESTAMP NOT NULL,
    update_count BIGINT NOT NULL,
    price NUMERIC(19,8),
    bid NUMERIC(19,8),
    ask NUMERIC(19,8),
    bid_size BIGINT,
    ask_size BIGINT,
    spread_bps NUMERIC(19,8),
    obi NUMERIC(9,6),
    microprice NUMERIC(19,8),
    microprice_deviation_bps NUMERIC(19,8),
    UNIQUE (ticker, time)
);

-- Индекс нужен в обоих вариантах (в 037 он создавался только внутри DO-блока).
CREATE INDEX IF NOT EXISTS idx_microstructure_ticker_time
    ON microstructure_snapshots(ticker, time DESC);

DO $$
BEGIN
    RAISE NOTICE 'microstructure_snapshots ensured (hypertable conversion is optional)';
END $$;
