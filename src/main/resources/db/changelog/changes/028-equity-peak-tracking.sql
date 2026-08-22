-- Equity-based drawdown breaker: track peak equity (balance + unrealized P&L)
-- for intraday HWM tracking. Reset on new trading day.
ALTER TABLE daily_risk_snapshot ADD COLUMN IF NOT EXISTS peak_equity NUMERIC(20, 2);
