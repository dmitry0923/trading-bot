--liquibase formatted sql
--changeset dmitry:001

CREATE TABLE IF NOT EXISTS positions (
    id BIGSERIAL PRIMARY KEY,
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
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    alor_order_id VARCHAR(100),
    close_reason VARCHAR(50),
    opened_at TIMESTAMP NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_positions_ticker ON positions(ticker);
CREATE INDEX IF NOT EXISTS idx_positions_status ON positions(status);
CREATE INDEX IF NOT EXISTS idx_positions_closed_at ON positions(closed_at);

CREATE TABLE IF NOT EXISTS strategies (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    action VARCHAR(20) NOT NULL,
    target_price NUMERIC(19,6) NOT NULL,
    quantity INT NOT NULL,
    stop_loss NUMERIC(19,6),
    take_profit NUMERIC(19,6),
    trailing_stop BOOLEAN NOT NULL DEFAULT FALSE,
    confidence NUMERIC(5,4) NOT NULL,
    reasoning TEXT NOT NULL,
    raw_json TEXT,
    cycle_id VARCHAR(50) NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_strategies_ticker ON strategies(ticker);
CREATE INDEX IF NOT EXISTS idx_strategies_created_at ON strategies(created_at);

CREATE TABLE IF NOT EXISTS candles (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    timeframe VARCHAR(20) NOT NULL,
    open_price NUMERIC(19,6) NOT NULL,
    high_price NUMERIC(19,6) NOT NULL,
    low_price NUMERIC(19,6) NOT NULL,
    close_price NUMERIC(19,6) NOT NULL,
    volume BIGINT NOT NULL,
    time TIMESTAMP NOT NULL,
    UNIQUE (ticker, timeframe, time)
);

CREATE INDEX IF NOT EXISTS idx_candles_ticker_time ON candles(ticker, timeframe, time);

CREATE TABLE IF NOT EXISTS agent_logs (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(50) NOT NULL,
    agent_name VARCHAR(100) NOT NULL,
    ticker VARCHAR(20),
    action VARCHAR(50) NOT NULL,
    confidence NUMERIC(5,4),
    reasoning TEXT,
    raw_output TEXT,
    latency_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_logs_cycle ON agent_logs(cycle_id);
CREATE INDEX IF NOT EXISTS idx_agent_logs_created_at ON agent_logs(created_at);

CREATE TABLE IF NOT EXISTS blind_spots (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    condition_pattern VARCHAR(4000) NOT NULL,
    loss_rate NUMERIC(5,4) NOT NULL,
    occurrence_count INT NOT NULL,
    recommendation VARCHAR(4000) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    detected_at TIMESTAMP NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_blind_spots_ticker_active ON blind_spots(ticker, is_active);

CREATE TABLE IF NOT EXISTS strategy_adjustments (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    adjustment_type VARCHAR(50) NOT NULL,
    old_value NUMERIC(19,6),
    new_value NUMERIC(19,6),
    triggered_by VARCHAR(50) NOT NULL,
    reason VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_adjustments_ticker ON strategy_adjustments(ticker);
