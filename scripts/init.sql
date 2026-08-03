CREATE TABLE IF NOT EXISTS candles (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    timeframe VARCHAR(20) NOT NULL,
    time TIMESTAMP NOT NULL,
    open NUMERIC(19,6) NOT NULL,
    high NUMERIC(19,6) NOT NULL,
    low NUMERIC(19,6) NOT NULL,
    close NUMERIC(19,6) NOT NULL,
    volume BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_candles_ticker_tf_time ON candles(ticker, timeframe, time);

CREATE TABLE IF NOT EXISTS strategies (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    action VARCHAR(20) NOT NULL,
    target_price NUMERIC(19,6) NOT NULL,
    quantity INTEGER NOT NULL,
    stop_loss NUMERIC(19,6),
    take_profit NUMERIC(19,6),
    trailing_stop BOOLEAN NOT NULL DEFAULT FALSE,
    confidence DOUBLE PRECISION NOT NULL,
    reasoning TEXT,
    cycle_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    valid_until TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_strategies_ticker_time ON strategies(ticker, created_at DESC);

CREATE TABLE IF NOT EXISTS positions (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    quantity INTEGER NOT NULL,
    entry_price NUMERIC(19,6) NOT NULL,
    current_price NUMERIC(19,6),
    stop_loss NUMERIC(19,6),
    take_profit NUMERIC(19,6),
    trailing_stop_price NUMERIC(19,6),
    opened_at TIMESTAMP DEFAULT NOW(),
    closed_at TIMESTAMP,
    close_price NUMERIC(19,6),
    pnl NUMERIC(19,6),
    status VARCHAR(20) DEFAULT 'OPEN',
    alor_order_id VARCHAR(100) NOT NULL,
    close_reason VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_positions_status ON positions(status);

CREATE TABLE IF NOT EXISTS agent_logs (
    id VARCHAR(100) PRIMARY KEY,
    cycle_id VARCHAR(100) NOT NULL,
    agent_name VARCHAR(50) NOT NULL,
    ticker VARCHAR(20) NOT NULL,
    action VARCHAR(20) NOT NULL,
    confidence DOUBLE PRECISION,
    reasoning TEXT,
    raw_output TEXT,
    latency_ms BIGINT,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_logs_cycle ON agent_logs(cycle_id);
CREATE INDEX IF NOT EXISTS idx_logs_created ON agent_logs(created_at DESC);
