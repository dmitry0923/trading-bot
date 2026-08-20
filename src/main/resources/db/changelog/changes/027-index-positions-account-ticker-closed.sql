--liquibase formatted sql
--changeset dmitry:027

-- Composite index for the multi-account query pattern used by:
--   - findClosedByTickerAndAccountSince(ticker, accountId, since)
--   - findClosedByAccountSince(accountId, since)
--
-- The existing idx_positions_account_id covers single-column lookups,
-- but timePatternAnalysis() and analyzeLastNDays() now filter by
-- account_id + ticker + closed_at simultaneously. A composite index
-- avoids expensive index-intersection plans at scale.

CREATE INDEX IF NOT EXISTS idx_positions_account_ticker_closed
ON positions (account_id, ticker, closed_at DESC)
WHERE status <> 'OPEN';

-- For findClosedByAccountSince (no ticker filter):
-- idx_positions_account_ticker_closed still works (leading column = account_id),
-- but a dedicated (account_id, closed_at) index is tighter.
-- This partially subsumes idx_positions_account_id for closed-position queries.

CREATE INDEX IF NOT EXISTS idx_positions_account_closed
ON positions (account_id, closed_at DESC)
WHERE status <> 'OPEN';
