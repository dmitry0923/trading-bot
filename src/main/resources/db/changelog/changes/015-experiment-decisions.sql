--liquibase formatted sql
--changeset dmitry:015

-- Ledger решений Shadow Mode / Decision-level A/B эксперимента.
-- Для каждого цикла пишутся две записи: CONTROL (текущий пайплайн, исполняется)
-- и VARIANT (paper, не исполняется). Исходы сравниваются при закрытии позиции:
-- result_pnl контрольной руки = фактический P&L, вариантной = гипотетический.
CREATE TABLE IF NOT EXISTS experiment_decisions (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(64) NOT NULL,
    experiment_id VARCHAR(64) NOT NULL,
    arm VARCHAR(16) NOT NULL,
    ticker VARCHAR(20) NOT NULL,
    timeframe VARCHAR(20),
    action VARCHAR(16) NOT NULL,
    target_price NUMERIC(19,6),
    quantity INT NOT NULL DEFAULT 0,
    stop_loss NUMERIC(19,6),
    take_profit NUMERIC(19,6),
    confidence DOUBLE PRECISION,
    reasoning TEXT,
    is_paper BOOLEAN NOT NULL DEFAULT FALSE,
    version VARCHAR(32),
    raw_output TEXT,
    executed BOOLEAN NOT NULL DEFAULT FALSE,
    result_pnl NUMERIC(19,6),
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    decided_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exp_decisions_cycle ON experiment_decisions(cycle_id);
CREATE INDEX IF NOT EXISTS idx_exp_decisions_ticker ON experiment_decisions(ticker, decided_at DESC);
