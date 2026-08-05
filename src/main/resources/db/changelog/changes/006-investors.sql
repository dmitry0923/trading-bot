--liquibase formatted sql
--changeset dmitry:006

-- Инвесторы и клиринг: счета, транзакции (ввод/вывод), аллокации капитала.
CREATE TABLE IF NOT EXISTS investors (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(200),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS investor_accounts (
    id UUID PRIMARY KEY,
    investor_id UUID NOT NULL REFERENCES investors(id),
    currency VARCHAR(10) NOT NULL DEFAULT 'RUB',
    balance NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_deposited NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_withdrawn NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_investor_accounts_investor ON investor_accounts(investor_id);

CREATE TABLE IF NOT EXISTS investor_transactions (
    id UUID PRIMARY KEY,
    investor_id UUID NOT NULL REFERENCES investors(id),
    account_id UUID NOT NULL REFERENCES investor_accounts(id),
    type VARCHAR(20) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'RUB',
    shares_at_time NUMERIC(12,6),
    equity_at_time NUMERIC(19,2),
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_investor_transactions_investor ON investor_transactions(investor_id);
CREATE INDEX IF NOT EXISTS idx_investor_transactions_account ON investor_transactions(account_id);

CREATE TABLE IF NOT EXISTS investor_allocations (
    id UUID PRIMARY KEY,
    investor_id UUID NOT NULL REFERENCES investors(id),
    account_id UUID NOT NULL REFERENCES investor_accounts(id),
    amount NUMERIC(19,2) NOT NULL,
    allocated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_investor_allocations_investor ON investor_allocations(investor_id);
