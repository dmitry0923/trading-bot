import React from 'react';
import { useFetch } from '../api';

export default function StrategiesPage() {
  const [ticker, setTicker] = React.useState('');
  const { data: strategies, error } = useFetch('/api/v1/strategies', 15000);
  const { data: single, error: singleError } = useFetch(
    ticker ? `/api/v1/strategies/${ticker}` : '',
    ticker ? 15000 : 0
  );

  if (error) return <div>Error: {error}</div>;

  const rows = ticker ? (single ? [single] : []) : (strategies || []);

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <input
          placeholder="Filter by ticker (e.g. SBER)"
          value={ticker}
          onChange={e => setTicker(e.target.value)}
          style={{ padding: 6, marginRight: 8 }}
        />
        {singleError && <span style={{ color: '#c62828' }}>{singleError}</span>}
      </div>
      <table border="1" cellPadding="6" style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead style={{ background: '#f0f0f0' }}>
          <tr>
            <th>ID</th><th>Ticker</th><th>Action</th><th>Target</th><th>Qty</th>
            <th>SL</th><th>TP</th><th>Conf</th><th>Trailing</th><th>Created</th><th>Reasoning</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(s => (
            <tr key={s.id || s.ticker}>
              <td>{s.id || '-'}</td>
              <td>{s.ticker}</td>
              <td style={{ fontWeight: 700 }}>{s.action}</td>
              <td>{s.targetPrice}</td>
              <td>{s.quantity}</td>
              <td>{s.stopLoss}</td>
              <td>{s.takeProfit}</td>
              <td>{s.confidence}</td>
              <td>{s.trailingStop ? 'yes' : 'no'}</td>
              <td>{s.createdAt}</td>
              <td style={{ maxWidth: 400, fontSize: 12 }}>{s.reasoning}</td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td colSpan="11" style={{ textAlign: 'center', color: '#888' }}>No strategies</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
