import React from 'react';
import { get } from '../api';

export default function BacktestPage() {
  const [ticker, setTicker] = React.useState('SBER');
  const [days, setDays] = React.useState(730);
  const [loadHistory, setLoadHistory] = React.useState(true);
  const [result, setResult] = React.useState(null);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState('');

  const run = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await get(`/api/v1/backtest/${ticker}?days=${days}&loadHistory=${loadHistory}`);
      setResult(res);
    } catch (e) {
      setError(e.message);
      setResult(null);
    } finally {
      setLoading(false);
    }
  };

  const fmtPct = v => `${(v * 100).toFixed(2)}%`;

  return (
    <div>
      <h2>Backtest</h2>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16 }}>
        <input value={ticker} onChange={e => setTicker(e.target.value)} style={{ padding: 6 }} />
        <input
          type="number" min="30" max="1095" value={days}
          onChange={e => setDays(Number(e.target.value))}
          style={{ padding: 6, width: 90 }}
        />
        <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 13 }}>
          <input type="checkbox" checked={loadHistory} onChange={e => setLoadHistory(e.target.checked)} />
          load from MOEX
        </label>
        <button onClick={run} disabled={loading} style={{ padding: '8px 16px', cursor: 'pointer' }}>
          {loading ? 'Running...' : 'Run Backtest'}
        </button>
      </div>
      {error && <div style={{ color: '#c62828' }}>Error: {error}</div>}

      {result && (
        <div>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
            <div><b>Total Return:</b> {fmtPct(result.totalReturn)}</div>
            <div><b>Sharpe:</b> {result.sharpeRatio.toFixed(2)}</div>
            <div><b>Max DD:</b> {fmtPct(result.maxDrawdown)}</div>
            <div><b>Win Rate:</b> {fmtPct(result.winRate)}</div>
            <div><b>Profit Factor:</b> {result.profitFactor.toFixed(2)}</div>
            <div><b>Trades:</b> {result.totalTrades}</div>
            <div>
              <b>Passable:</b>
              <span style={{ color: result.passable ? '#2e7d32' : '#c62828', fontWeight: 700 }}>
                {' '}{result.passable ? 'PASS' : 'REJECT'}
              </span>
            </div>
          </div>
          <details>
            <summary style={{ cursor: 'pointer' }}>Equity curve (raw)</summary>
            <pre style={{ maxHeight: 300, overflow: 'auto', background: '#f5f5f5', padding: 12, fontSize: 11 }}>
              {JSON.stringify(result.equityCurve, null, 0)}
            </pre>
          </details>
        </div>
      )}
      {!result && !loading && <div style={{ color: '#888' }}>Run a backtest to see results.</div>}
    </div>
  );
}
