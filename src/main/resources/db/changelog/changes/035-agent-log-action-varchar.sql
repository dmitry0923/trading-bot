-- Widen agent_logs columns for LLM backtest runs.
-- 1) action: LLM agents can return action strings longer than 50 chars.
--    (e.g. "LONG_BREAKOUT_PULLBACK", "HOLD_INSUFFICIENT_DATA").
-- 2) cycle_id: BacktestEngine.kt builds "backtest-$ticker-${UUID}" which is
--    53 chars ("backtest-CNYRUBF-xxxxxxxx-xxxx-..." = 9+1+6+1+36 = 53) > 50.
ALTER TABLE agent_logs ALTER COLUMN action TYPE VARCHAR(200);
ALTER TABLE agent_logs ALTER COLUMN cycle_id TYPE VARCHAR(100);