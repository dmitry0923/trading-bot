--changeset dmitry:036 splitStatements:false

-- Фактический funding (SWAPRATE MOEX) по датам клирингов для P&L бэктеста
-- и funding-veto. Хранит руб/контракт/клиринг, что напрямую используется
-- в BacktestEngine.closePosition и FundingVetoGate.
CREATE TABLE IF NOT EXISTS funding_history (
    id              BIGSERIAL    PRIMARY KEY,
    ticker          VARCHAR(32)  NOT NULL,
    clearing_date   DATE         NOT NULL,
    raw_value       NUMERIC(19,8) NOT NULL,
    value_rub_per_contract NUMERIC(19,6) NOT NULL,
    source          VARCHAR(16)  NOT NULL DEFAULT 'MOEX',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_funding_history_ticker_date UNIQUE (ticker, clearing_date)
);

CREATE INDEX IF NOT EXISTS idx_funding_history_ticker_date
    ON funding_history(ticker, clearing_date DESC);
