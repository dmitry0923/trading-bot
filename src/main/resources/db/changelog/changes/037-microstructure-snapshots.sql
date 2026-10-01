--changeset dmitry:037 splitStatements:false

-- Микроструктурные снапшоты стакана (L1) для forward-исследований.
--
-- Источник: live WebSocket Alor (`Quotations`), который уже отдаёт
-- bid/ask/askSize/bidSize бесплатно. Сырые тики НЕ сохраняются: частота
-- котировок на порядки выше торговой, а для research нужны агрегаты
-- внутри фиксированного бакета (по умолчанию 1 секунда).
--
-- Строка = один бакет (ticker, time) со средними OBI/microprice и последними
-- наблюдаемыми bid/ask/размерами. Средние, а не последние значения нужны,
-- чтобы усреднить микроскопический шум стакана внутри бакета.
--
-- OBI и microprice считаются существующими доменными калькуляторами
-- (ObiCalculator / MicropriceCalculator) в MicrostructureRecorder.
--
-- Как и в 012: при отсутствии timescaledb конвертация пропускается с
-- warning, приложение стартует на обычных таблицах.
--
-- compression не используется: TimescaleDB-компрессия несовместима с
-- UNIQUE-индексами, а UNIQUE (ticker, time) нужен для идемпотентной
-- записи бакета (ON CONFLICT DO NOTHING).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        EXECUTE 'CREATE EXTENSION timescaledb';
    END IF;

    EXECUTE 'DROP TABLE IF EXISTS microstructure_snapshots';

    EXECUTE '
        CREATE TABLE microstructure_snapshots (
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
        )';

    EXECUTE 'SELECT create_hypertable(''microstructure_snapshots'', ''time'', chunk_time_interval => INTERVAL ''1 day'')';

    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_microstructure_ticker_time
        ON microstructure_snapshots(ticker, time DESC)';

    EXECUTE 'SELECT add_retention_policy(''microstructure_snapshots'', INTERVAL ''90 days'')';
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'TimescaleDB conversion of microstructure_snapshots skipped: %', SQLERRM;
END $$;
