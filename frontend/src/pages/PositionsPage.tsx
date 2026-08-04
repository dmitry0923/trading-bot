import React from 'react';
import { useFetch } from '../api';
import type { Position } from '../types';

export default function PositionsPage() {
  const [status, setStatus] = React.useState<'open' | 'all'>('open');
  const { data: open, error: openError } = useFetch<Position[]>('/api/v1/positions', 10000);
  const { data: all, error: allError } = useFetch<Position[]>('/api/v1/positions/all', 10000);

  if (openError || allError) return <div>Error: {openError || allError}</div>;

  const rows: Position[] = status === 'open' ? open || [] : all || [];

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <label>Status: </label>
        <select value={status} onChange={e => setStatus(e.target.value as 'open' | 'all')}>
          <option value="open">Open</option>
          <option value="all">All (open + closed)</option>
        </select>
      </div>
      <table border={1} cellPadding={6} style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead style={{ background: '#f0f0f0' }}>
          <tr>
            <th>ID</th><th>Ticker</th><th>Direction</th><th>Qty</th><th>Entry</th>
            <th>Current</th><th>Close</th><th>SL</th><th>TP</th><th>P&L</th>
            <th>Status</th><th>Reason</th><th>Opened</th><th>Closed</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(p => (
            <tr key={p.id}>
              <td>{p.id}</td>
              <td>{p.ticker}</td>
              <td>{p.direction}</td>
              <td>{p.quantity}</td>
              <td>{p.entryPrice}</td>
              <td>{p.currentPrice}</td>
              <td>{p.closePrice}</td>
              <td>{p.stopLoss}</td>
              <td>{p.takeProfit}</td>
              <td>{p.pnl}</td>
              <td>{p.status}</td>
              <td>{p.closeReason || '-'}</td>
              <td>{p.openedAt}</td>
              <td>{p.closedAt || '-'}</td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td colSpan={14} style={{ textAlign: 'center', color: '#888' }}>No positions</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
