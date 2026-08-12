import React from 'react';
import { get, post } from '../api';
import type { BacktestResult, PanelBacktestResponse } from '../types';

const fmtPct = (v: number) => `${(v * 100).toFixed(2)}%`;

function MetricRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <b>{label}:</b> {value}
    </div>
  );
}

export default function BacktestPage() {
  const [ticker, setTicker] = React.useState('SBER');
  const [days, setDays] = React.useState(730);
  const [loadHistory, setLoadHistory] = React.useState(true);
  const [result, setResult] = React.useState<BacktestResult | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState('');

  const [panelTickers, setPanelTickers] = React.useState('SBER, GAZP, LKOH');
  const [panelDays, setPanelDays] = React.useState(365);
  const [panel, setPanel] = React.useState<PanelBacktestResponse | null>(null);
  const [panelLoading, setPanelLoading] = React.useState(false);
  const [panelError, setPanelError] = React.useState('');

  const run = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await get<BacktestResult>(`/api/v1/backtest/${ticker}?days=${days}&loadHistory=${loadHistory}`);
      setResult(res);
    } catch (e) {
      setError((e as Error).message);
      setResult(null);
    } finally {
      setLoading(false);
    }
  };

  const runPanel = async () => {
    setPanelLoading(true);
    setPanelError('');
    setPanel(null);
    const tickers = panelTickers
      .split(',')
      .map(t => t.trim())
      .filter(Boolean);
    try {
      const res = await post<PanelBacktestResponse>('/api/v1/backtest/panel', {
        tickers,
        days: panelDays,
        loadHistory: true,
      });
      setPanel(res);
    } catch (e) {
      setPanelError((e as Error).message);
    } finally {
      setPanelLoading(false);
    }
  };

  return (
    <div>
      <h2>Backtest</h2>

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16 }}>
        <input value={ticker} onChange={e => setTicker(e.target.value)} style={{ padding: 6 }} />
        <input
          type="number" min={30} max={1095} value={days}
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
            <MetricRow label="Total Return" value={fmtPct(result.totalReturn)} />
            <MetricRow label="Sharpe" value={result.sharpeRatio.toFixed(2)} />
            <MetricRow label="Max DD" value={fmtPct(result.maxDrawdown)} />
            <MetricRow label="Win Rate" value={fmtPct(result.winRate)} />
            <MetricRow label="Profit Factor" value={result.profitFactor.toFixed(2)} />
            <MetricRow label="Trades" value={String(result.totalTrades)} />
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

      <h3 style={{ marginTop: 28 }}>Panel backtest (multi-ticker)</h3>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16 }}>
        <input
          value={panelTickers}
          onChange={e => setPanelTickers(e.target.value)}
          placeholder="tickers, comma separated"
          style={{ padding: 6, width: 220 }}
        />
        <input
          type="number" min={30} max={1095} value={panelDays}
          onChange={e => setPanelDays(Number(e.target.value))}
          style={{ padding: 6, width: 90 }}
        />
        <button onClick={runPanel} disabled={panelLoading} style={{ padding: '8px 16px', cursor: 'pointer' }}>
          {panelLoading ? 'Running...' : 'Run Panel'}
        </button>
      </div>
      {panelError && <div style={{ color: '#c62828' }}>Error: {panelError}</div>}

      {panel && (
        <div>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16, fontSize: 13 }}>
            <MetricRow label="Pass" value={`${panel.summary.passCount}/${panel.summary.tickerCount} (${fmtPct(panel.summary.passShare)})`} />
            <MetricRow label="Avg Return" value={fmtPct(panel.summary.avgTotalReturn)} />
            <MetricRow label="Median Return" value={fmtPct(panel.summary.medianTotalReturn)} />
            <MetricRow label="Min / Max" value={`${fmtPct(panel.summary.minTotalReturn)} / ${fmtPct(panel.summary.maxTotalReturn)}`} />
            <MetricRow label="Total Trades" value={String(panel.summary.totalTrades)} />
            <MetricRow label="Params" value={`${panel.days}d ${panel.timeframe} ${panel.initialCapital} init`} />
          </div>
          <table style={{ borderCollapse: 'collapse', fontSize: 13, width: '100%', maxWidth: 760 }}>
            <thead>
              <tr style={{ textAlign: 'left' }}>
                <th style={{ padding: '6px 8px', borderBottom: '1px solid #ccc' }}>Ticker</th>
                <th style={{ padding: '6px 8px', borderBottom: '1px solid #ccc' }}>Return</th>
                <th style={{ padding: '6px 8px', borderBottom: '1px solid #ccc' }}>Sharpe</th>
                <th style={{ padding: '6px 8px', borderBottom: '1px solid #ccc' }}>Max DD</th>
                <th style={{ padding: '6px 8px', borderBottom: '1px solid #ccc' }}>Win Rate</th>
                <th style={{ padding: '6px 8px', borderBottom: '1px solid #ccc' }}>PF</th>
                <th style={{ padding: '6px 8px', borderBottom: '1px solid #ccc' }}>Trades</th>
                <th style={{ padding: '6px 8px', borderBottom: '1px solid #ccc' }}>Status</th>
              </tr>
            </thead>
            <tbody>
              {panel.results.map(r => (
                <tr key={r.ticker}>
                  <td style={{ padding: '6px 8px', borderBottom: '1px solid #eee', fontWeight: 600 }}>{r.ticker}</td>
                  <td style={{ padding: '6px 8px', borderBottom: '1px solid #eee' }}>{fmtPct(r.totalReturn)}</td>
                  <td style={{ padding: '6px 8px', borderBottom: '1px solid #eee' }}>{r.sharpeRatio.toFixed(2)}</td>
                  <td style={{ padding: '6px 8px', borderBottom: '1px solid #eee' }}>{fmtPct(r.maxDrawdown)}</td>
                  <td style={{ padding: '6px 8px', borderBottom: '1px solid #eee' }}>{fmtPct(r.winRate)}</td>
                  <td style={{ padding: '6px 8px', borderBottom: '1px solid #eee' }}>{r.profitFactor.toFixed(2)}</td>
                  <td style={{ padding: '6px 8px', borderBottom: '1px solid #eee' }}>{r.totalTrades}</td>
                  <td style={{ padding: '6px 8px', borderBottom: '1px solid #eee' }}>
                    <span style={{ color: r.passable ? '#2e7d32' : '#c62828', fontWeight: 700 }}>
                      {r.passable ? 'PASS' : 'REJECT'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!panel && !panelLoading && !panelError && <div style={{ color: '#888' }}>Run a panel backtest to compare tickers.</div>}
    </div>
  );
}
