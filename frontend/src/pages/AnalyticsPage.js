import React, { useEffect, useState } from 'react';

const API = '/api/v1';

export default function AnalyticsPage() {
  const [stats, setStats] = useState({});
  const [blindSpots, setBlindSpots] = useState([]);
  const [adjustments, setAdjustments] = useState([]);
  const [health, setHealth] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      fetch(`${API}/analytics/trade-stats?days=14`).then(r => r.json()),
      fetch(`${API}/analytics/blind-spots`).then(r => r.json()),
      fetch(`${API}/analytics/adjustments`).then(r => r.json()),
      fetch(`${API}/analytics/health`).then(r => r.json()),
    ]).then(([s, bs, adj, h]) => {
      setStats(s);
      setBlindSpots(bs);
      setAdjustments(adj.slice(0, 20));
      setHealth(h);
      setLoading(false);
    });
  }, []);

  if (loading) return <div style={{ padding: 20 }}>Загрузка аналитики...</div>;

  const tickers = Object.keys(stats);

  return (
    <div style={{ padding: 20, fontFamily: 'Segoe UI, sans-serif' }}>
      <h1>🧠 Self-Learning Analytics</h1>

      {health && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 24 }}>
          <Card title="Тикеров" value={health.totalTickersAnalyzed} />
          <Card title="Сделок (7д)" value={health.totalTradesLast7Days} />
          <Card title="Ср. Win Rate" value={health.averageWinRate} />
          <Card title="Пауза" value={health.pausedTickers.length > 0 ? health.pausedTickers.join(', ') : 'Нет'} color="#e74c3c" />
        </div>
      )}

      <h2>📊 Статистика по тикерам (14 дней)</h2>
      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 24 }}>
        <thead>
          <tr style={{ background: '#2c3e50', color: 'white' }}>
            <th>Тикер</th><th>Сделок</th><th>Win Rate</th><th>PF</th>
            <th>SL%</th><th>TP%</th><th>Серия убытков</th><th>Пауза</th>
          </tr>
        </thead>
        <tbody>
          {tickers.map(t => {
            const s = stats[t];
            const paused = s.maxConsecutiveLosses >= 4;
            return (
              <tr key={t} style={{ borderBottom: '1px solid #ddd', background: paused ? '#ffebee' : 'white' }}>
                <td><b>{t}</b></td>
                <td>{s.totalTrades}</td>
                <td style={{ color: s.winRate > 0.5 ? '#27ae60' : '#e74c3c' }}>
                  {(s.winRate * 100).toFixed(1)}%
                </td>
                <td>{s.profitFactor.toFixed(2)}</td>
                <td>{(s.slHitRate * 100).toFixed(0)}%</td>
                <td>{(s.tpHitRate * 100).toFixed(0)}%</td>
                <td>{s.maxConsecutiveLosses}</td>
                <td>{paused ? '⏸️ ДА' : 'Нет'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>

      <h2>⚠️ Слепые зоны (Blind Spots)</h2>
      {blindSpots.length === 0 ? (
        <p style={{ color: '#27ae60' }}>Слепых зон не обнаружено 👍</p>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 24 }}>
          <thead>
            <tr style={{ background: '#c0392b', color: 'white' }}>
              <th>Тикер</th><th>Паттерн</th><th>Убытков</th><th>Рекомендация</th>
            </tr>
          </thead>
          <tbody>
            {blindSpots.map(bs => (
              <tr key={bs.id} style={{ borderBottom: '1px solid #ddd' }}>
                <td>{bs.ticker}</td>
                <td>{bs.conditionPattern}</td>
                <td>{(bs.lossRate * 100).toFixed(0)}% ({bs.occurrenceCount})</td>
                <td>{bs.recommendation}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>🔧 История корректировок</h2>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr style={{ background: '#2980b9', color: 'white' }}>
            <th>Время</th><th>Тикер</th><th>Тип</th><th>Старое</th><th>Новое</th><th>Причина</th>
          </tr>
        </thead>
        <tbody>
          {adjustments.map(a => (
            <tr key={a.id} style={{ borderBottom: '1px solid #ddd' }}>
              <td>{new Date(a.createdAt).toLocaleString()}</td>
              <td>{a.ticker}</td>
              <td>{a.adjustmentType}</td>
              <td>{a.oldValue ?? '-'}</td>
              <td><b>{a.newValue ?? '-'}</b></td>
              <td>{a.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Card({ title, value, color = '#2c3e50' }) {
  return (
    <div style={{ background: color, color: 'white', padding: 16, borderRadius: 8, textAlign: 'center' }}>
      <div style={{ fontSize: 12, opacity: 0.8 }}>{title}</div>
      <div style={{ fontSize: 24, fontWeight: 'bold', marginTop: 4 }}>{value}</div>
    </div>
  );
}
