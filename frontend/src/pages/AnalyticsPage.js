import React, { useState, useEffect } from 'react';

function AnalyticsPage() {
  const [health, setHealth] = useState(null);
  const [positions, setPositions] = useState([]);

  useEffect(() => {
    fetch('/api/v1/analytics/health')
      .then(r => r.json())
      .then(setHealth);
    fetch('/api/v1/positions')
      .then(r => r.json())
      .then(setPositions);
  }, []);

  return (
    <div style={{ padding: 20 }}>
      <h1>Trading Bot Dashboard</h1>
      {health && (
        <div>
          <h2>Health</h2>
          <pre>{JSON.stringify(health, null, 2)}</pre>
        </div>
      )}
      <h2>Open Positions ({positions.length})</h2>
      <table border="1" cellPadding="5">
        <thead>
          <tr><th>Ticker</th><th>Direction</th><th>Qty</th><th>Entry</th><th>PnL</th></tr>
        </thead>
        <tbody>
          {positions.map(p => (
            <tr key={p.id}>
              <td>{p.ticker}</td>
              <td>{p.direction}</td>
              <td>{p.quantity}</td>
              <td>{p.entryPrice}</td>
              <td>{p.pnl}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default AnalyticsPage;
