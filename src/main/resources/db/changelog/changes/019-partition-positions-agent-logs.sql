--liquibase formatted sql
--changeset dmitry:019 splitStatements:false

-- PostgreSQL native partitioning для positions и agent_logs (roadmap v2.2, раздел 6.4).
--
-- agent_logs (PARTITION BY RANGE (created_at)) — самая быстрорастущая таблица
--   (LLM-логи по каждому циклу): запись идёт только в горячую месячную партицию,
--   выборки по created_at/cycle_id получают pruning по партициям.
-- positions (PARTITION BY RANGE (opened_at)) — история сделок: горячее множество
--   OPEN-позиций живёт в свежих партициях.
--
-- Конвертация выполняется в одном атомарном DO-блоке (весь changeset — одна
-- транзакция): при любой ошибке все DDL/DML откатываются, таблицы остаются
-- в исходном состоянии. В отличие от candles (012), здесь нет fallback-режима —
-- партиционирование обязательное, поэтому исключение НЕ перехватывается.
--
-- Шаги:
--   1. старые таблицы, их индексы и sequence переименовываются в *_old;
--   2. создаются новые партиционированные таблицы. PK включает partition key
--      ((id, opened_at) / (id, created_at)) — требование PostgreSQL для
--      уникальности на партиционированном родителе;
--   3. создаются месячные партиции (2024-01 .. 2027-12) и DEFAULT-партиции
--      (страховка для строк вне диапазона);
--   4. данные копируются, последовательности id подравниваются под MAX(id);
--   5. индексы пересоздаются (имена освобождены шагом 1), старые таблицы удаляются.
--
-- Будущие партиции поддерживает PartitionMaintenanceService (@Scheduled:
-- создаёт месячные партиции на 3 месяца вперёд + гарантирует текущий месяц
-- при старте приложения, раздел 6.4).

DO $$
DECLARE
    m DATE;
BEGIN
    -- ============================ agent_logs ============================
    EXECUTE 'ALTER TABLE agent_logs RENAME TO agent_logs_old';
    EXECUTE 'ALTER INDEX idx_agent_logs_cycle RENAME TO idx_agent_logs_cycle_old';
    EXECUTE 'ALTER INDEX idx_agent_logs_created_at RENAME TO idx_agent_logs_created_at_old';
    EXECUTE 'ALTER SEQUENCE agent_logs_id_seq RENAME TO agent_logs_old_id_seq';

    EXECUTE '
        CREATE TABLE agent_logs (
            id BIGSERIAL,
            cycle_id VARCHAR(50) NOT NULL,
            agent_name VARCHAR(100) NOT NULL,
            ticker VARCHAR(20),
            action VARCHAR(50) NOT NULL,
            confidence NUMERIC(5,4),
            reasoning TEXT,
            raw_output TEXT,
            latency_ms BIGINT,
            tokens_used INT,
            is_cached BOOLEAN NOT NULL DEFAULT FALSE,
            override_reason VARCHAR(200),
            storage_key VARCHAR(255),
            created_at TIMESTAMP NOT NULL DEFAULT NOW(),
            PRIMARY KEY (id, created_at)
        ) PARTITION BY RANGE (created_at)';

    FOR m IN SELECT generate_series(DATE '2024-01-01', DATE '2027-12-01', INTERVAL '1 month') LOOP
        EXECUTE format(
            'CREATE TABLE agent_logs_%s PARTITION OF agent_logs FOR VALUES FROM (%L) TO (%L)',
            to_char(m, 'YYYYMM'),
            m::timestamp,
            (m + INTERVAL '1 month')::timestamp
        );
    END LOOP;
    EXECUTE 'CREATE TABLE agent_logs_default PARTITION OF agent_logs DEFAULT';

    EXECUTE '
        INSERT INTO agent_logs (id, cycle_id, agent_name, ticker, action, confidence, reasoning, raw_output,
                                latency_ms, tokens_used, is_cached, override_reason, storage_key, created_at)
        SELECT id, cycle_id, agent_name, ticker, action, confidence, reasoning, raw_output,
               latency_ms, tokens_used, is_cached, override_reason, storage_key, created_at
        FROM agent_logs_old';

    EXECUTE 'SELECT setval(''agent_logs_id_seq'', GREATEST((SELECT COALESCE(MAX(id), 1) FROM agent_logs), 1))';

    EXECUTE 'CREATE INDEX idx_agent_logs_cycle ON agent_logs(cycle_id)';
    EXECUTE 'CREATE INDEX idx_agent_logs_created_at ON agent_logs(created_at)';

    EXECUTE 'DROP TABLE agent_logs_old';

    -- ============================ positions ============================
    EXECUTE 'ALTER TABLE positions RENAME TO positions_old';
    EXECUTE 'ALTER INDEX idx_positions_ticker RENAME TO idx_positions_ticker_old';
    EXECUTE 'ALTER INDEX idx_positions_status RENAME TO idx_positions_status_old';
    EXECUTE 'ALTER INDEX idx_positions_closed_at RENAME TO idx_positions_closed_at_old';
    EXECUTE 'ALTER INDEX idx_positions_instrument_type RENAME TO idx_positions_instrument_type_old';
    EXECUTE 'ALTER INDEX idx_positions_cycle_id RENAME TO idx_positions_cycle_id_old';
    EXECUTE 'ALTER INDEX idx_positions_pending_close RENAME TO idx_positions_pending_close_old';
    EXECUTE 'ALTER INDEX idx_positions_pending_entry RENAME TO idx_positions_pending_entry_old';
    EXECUTE 'ALTER SEQUENCE positions_id_seq RENAME TO positions_old_id_seq';

    EXECUTE '
        CREATE TABLE positions (
            id BIGSERIAL,
            ticker VARCHAR(20) NOT NULL,
            direction VARCHAR(10) NOT NULL,
            quantity INT NOT NULL,
            entry_price NUMERIC(19,6) NOT NULL,
            current_price NUMERIC(19,6),
            close_price NUMERIC(19,6),
            stop_loss NUMERIC(19,6),
            take_profit NUMERIC(19,6),
            trailing_stop_price NUMERIC(19,6),
            pnl NUMERIC(19,6),
            status VARCHAR(20) NOT NULL DEFAULT ''OPEN'',
            alor_order_id VARCHAR(100),
            close_reason VARCHAR(50),
            opened_at TIMESTAMP NOT NULL DEFAULT NOW(),
            closed_at TIMESTAMP,
            instrument_type VARCHAR(10) NOT NULL DEFAULT ''STOCK'',
            leverage NUMERIC(10,4),
            go_per_contract NUMERIC(19,6),
            margin_used NUMERIC(19,6),
            liquidation_price NUMERIC(19,6),
            variation_margin NUMERIC(19,6) NOT NULL DEFAULT 0,
            stop_loss_points INT,
            cycle_id VARCHAR(64),
            close_order_id VARCHAR(100),
            pending_close BOOLEAN NOT NULL DEFAULT FALSE,
            pending_entry BOOLEAN NOT NULL DEFAULT FALSE,
            realized_pnl NUMERIC(19,6) NOT NULL DEFAULT 0,
            PRIMARY KEY (id, opened_at)
        ) PARTITION BY RANGE (opened_at)';

    FOR m IN SELECT generate_series(DATE '2024-01-01', DATE '2027-12-01', INTERVAL '1 month') LOOP
        EXECUTE format(
            'CREATE TABLE positions_%s PARTITION OF positions FOR VALUES FROM (%L) TO (%L)',
            to_char(m, 'YYYYMM'),
            m::timestamp,
            (m + INTERVAL '1 month')::timestamp
        );
    END LOOP;
    EXECUTE 'CREATE TABLE positions_default PARTITION OF positions DEFAULT';

    EXECUTE '
        INSERT INTO positions (id, ticker, direction, quantity, entry_price, current_price, close_price,
            stop_loss, take_profit, trailing_stop_price, pnl, status, alor_order_id, close_reason,
            opened_at, closed_at, instrument_type, leverage, go_per_contract, margin_used,
            liquidation_price, variation_margin, stop_loss_points, cycle_id, close_order_id,
            pending_close, pending_entry, realized_pnl)
        SELECT id, ticker, direction, quantity, entry_price, current_price, close_price,
            stop_loss, take_profit, trailing_stop_price, pnl, status, alor_order_id, close_reason,
            opened_at, closed_at, instrument_type, leverage, go_per_contract, margin_used,
            liquidation_price, variation_margin, stop_loss_points, cycle_id, close_order_id,
            pending_close, pending_entry, realized_pnl
        FROM positions_old';

    EXECUTE 'SELECT setval(''positions_id_seq'', GREATEST((SELECT COALESCE(MAX(id), 1) FROM positions), 1))';

    EXECUTE 'CREATE INDEX idx_positions_ticker ON positions(ticker)';
    EXECUTE 'CREATE INDEX idx_positions_status ON positions(status)';
    EXECUTE 'CREATE INDEX idx_positions_closed_at ON positions(closed_at)';
    EXECUTE 'CREATE INDEX idx_positions_instrument_type ON positions(instrument_type)';
    EXECUTE 'CREATE INDEX idx_positions_cycle_id ON positions(cycle_id)';
    EXECUTE 'CREATE INDEX idx_positions_pending_close ON positions(pending_close) WHERE pending_close = TRUE';
    EXECUTE 'CREATE INDEX idx_positions_pending_entry ON positions(pending_entry) WHERE pending_entry = TRUE';

    EXECUTE 'DROP TABLE positions_old';
END $$;
