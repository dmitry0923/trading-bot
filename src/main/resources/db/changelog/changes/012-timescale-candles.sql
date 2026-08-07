--liquibase formatted sql
--changeset dmitry:012 splitStatements:false

-- Перевод candles на TimescaleDB (гипертаблица по time) с чистого листа:
-- старые строки не переносятся, история перезагружается с MOEX ISS
-- (HistoricalDataLoader / BacktestEngine).
--
-- Вся конвертация выполняется в одном атомарном DO-блоке:
--   * при любой ошибке все DDL откатываются, таблица candles остаётся
--     в исходном состоянии и продолжает работать;
--   * если timescaledb недоступен (обычный PostgreSQL или Yandex Managed PG
--     без включённого расширения) — логируется предупреждение, приложение
--     стартует без изменений схемы.
--
-- Включение timescaledb на Yandex Managed PG выполняется НЕ через SQL, а через
-- консоль/CLI/Terraform: добавить расширение timescaledb и shared_preload_libraries
-- timescaledb (перезапуск мастера). После этого достаточно перезапустить приложение
-- (или выполнить конвертацию вручную), чтобы candles стала гипертаблицей.
--
-- Эффект: партиционирование по чанкам (1 неделя) + add_retention_policy (90 дней)
-- автоматически удаляет старые чанки — таблица не растёт неограниченно,
-- VACUUM-нагрузка на историю отсутствует.
--
-- compression не используется: TimescaleDB-компрессия несовместима с UNIQUE-индексами,
-- а UNIQUE (ticker, timeframe, time) сохраняется для идемпотентной записи
-- (ON CONFLICT DO NOTHING).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        EXECUTE 'CREATE EXTENSION timescaledb';
    END IF;

    EXECUTE 'DROP TABLE IF EXISTS candles';

    EXECUTE '
        CREATE TABLE candles (
            ticker VARCHAR(20) NOT NULL,
            timeframe VARCHAR(20) NOT NULL,
            open_price NUMERIC(19,6) NOT NULL,
            high_price NUMERIC(19,6) NOT NULL,
            low_price NUMERIC(19,6) NOT NULL,
            close_price NUMERIC(19,6) NOT NULL,
            volume BIGINT NOT NULL,
            time TIMESTAMP NOT NULL,
            UNIQUE (ticker, timeframe, time)
        )';

    EXECUTE 'SELECT create_hypertable(''candles'', ''time'', chunk_time_interval => INTERVAL ''1 week'')';

    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_candles_ticker_time ON candles(ticker, timeframe, time DESC)';

    EXECUTE 'SELECT add_retention_policy(''candles'', INTERVAL ''90 days'')';
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'TimescaleDB conversion of candles skipped: %', SQLERRM;
END $$;
