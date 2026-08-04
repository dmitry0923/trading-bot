import React from 'react';
import { useFetch } from '../api';

function Card({ title, value, color }) {
  return (
    <div style={{ flex: 1, minWidth: 160, padding: 16, border: '1px solid #ddd', borderRadius: 8, background: '#fafafa' }}>
      <div style={{ fontSize: 13, color: '#666' }}>{title}</div>
      <div style={{ fontSize: 24, fontWeight: 700, color: color || '#1a1a2e' }}>{value}</div>
    </div>
  );
}

export default function DashboardPage() {
  const { data, error } = useFetch('/api/v1/dashboard', 5000);

  if (error) return <div>Error: {error}</div>;
  if (!data) return <div>Loading dashboard...</div>;

  const pnlColor = p => (p && p > 0 ? '#2e7d32' : p && p < 0 ? '#c62828' : '#333');

  return (
    <div>
      <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
        <Card title="Mode" value={data.tradingMode} />
        <Card title="Daily P&L" value={data.dailyPnl} color={pnlColor(Number(data.dailyPnl))} />
        <Card title="Open P&L (unrealized)" value={data.openPnl} color={pnlColor(Number(data.openPnl))} />
        <Card title="Realized Today" value={data.realizedPnlToday} color={pnlColor(Number(data.realizedPnlToday))} />
        <Card title="Closed Today" value={data.closedTodayCount} />
        <Card title="Strategies Today" value={data.strategiesToday} />
        <Card title="Open Positions" value={data.openPositionsCount} />
      </div>

      {data.pausedTickers && data.pausedTickers.length > 0 && (
        <div style={{ padding: 12, border: '1px solid #ffb74d', background: '#fff8e1', borderRadius: 8, marginBottom: 16 }}>
          <b>Paused tickers:</b> {data.pausedTickers.join(', ')}
        </div>
      )}

      <h2>Open Positions</h2>
      <table border="1" cellPadding="6" style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead style={{ background: '#f0f0f0' }}>
          <tr><th>Ticker</th><th>Direction</th><th>Qty</th><th>Entry</th><th>Current</th><th>SL</th><th>TP</th><th>P&L</th></tr>
        </thead>
        <tbody>
          {(data.openPositions || []).map(p => (
            <tr key={p.id}>
              <td>{p.ticker}</td>
              <td>{p.direction}</td>
              <td>{p.quantity}</td>
              <td>{p.entryPrice}</td>
              <td>{p.currentPrice}</td>
              <td>{p.stopLoss}</td>
              <td>{p.takeProfit}</td>
              <td style={{ color: pnlColor(Number(p.pnl)) }}>{p.pnl}</td>
            </tr>
          ))}
          {(!data.openPositions || data.openPositions.length === 0) && (
            <tr><td colSpan="8" style={{ textAlign: 'center', color: '#888' }}>No open positions</td></tr>
          )}
        </tbody>
      </table>
      <div style={{ marginTop: 8, fontSize: 12, color: '#888' }}>Updated: {data.timestamp}</div>
    </div>
  );
}
