import React, { useState, useEffect } from 'react';
import AnalyticsPage from './pages/AnalyticsPage';

const API = '/api/v1';

export default function App() {
  const [tab, setTab] = useState('dashboard');
  const [dashboard, setDashboard] = useState({});

  useEffect(() => {
    if (tab === 'dashboard') refreshDashboard();
  }, [tab]);

  const refreshDashboard = () => {
    Promise.all([
      fetch(`${API}/risk/daily-pnl`).then(r => r.json()),
      fetch(`${API}/positions`).then(r => r.json()),
      fetch(`${API}/strategies`).then(r => r.json()),
    ]).then(([pnl, positions, strategies]) => {
      setDashboard({ pnl, positions, strategies });
    });
  };

  return (
    <div style={{ fontFamily: 'Segoe UI, sans-serif', maxWidth: 1200, margin: '0 auto' }}>
      <header style={{ background: '#1a1a2e', color: 'white', padding: '16px 24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>🤖 MMVB AI Trading Bot v2</h2>
        <nav>
          {['dashboard', 'analytics', 'settings', 'logs'].map(t => (
            <button
              key={t}
              onClick={() => setTab(t)}
              style={{
                background: tab === t ? '#e94560' : 'transparent',
                color: 'white',
                border: 'none',
                padding: '8px 16px',
                marginLeft: 8,
                borderRadius: 4,
                cursor: 'pointer',
                textTransform: 'capitalize'
              }}
            >
              {t === 'dashboard' ? 'Дашборд' : t === 'analytics' ? 'Аналитика' : t === 'settings' ? 'Настройки' : 'Логи'}
            </button>
          ))}
        </nav>
      </header>

      <main style={{ padding: 24 }}>
        {tab === 'dashboard' && <Dashboard data={dashboard} onRefresh={refreshDashboard} />}
        {tab === 'analytics' && <AnalyticsPage />}
        {tab === 'settings' && <SettingsPage />}
        {tab === 'logs' && <LogsPage />}
      </main>
    </div>
  );
}

function Dashboard({ data, onRefresh }) {
  const { pnl, positions, strategies } = data;
  return (
    <div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16, marginBottom: 24 }}>
        <Card title="P&L (сегодня)" value={`${pnl?.dailyPnl ?? 0} ₽`} color="#27ae60" />
        <Card title="Открытых позиций" value={positions?.length ?? 0} color="#2980b9" />
        <Card title="Активных стратегий" value={strategies?.length ?? 0} color="#8e44ad" />
        <Card title="Режим" value={process.env.REACT_APP_MODE || 'SIMULATION'} color="#e67e22" />
      </div>
      <div style={{ marginBottom: 16 }}>
        <button onClick={onRefresh} style={{ marginRight: 8, padding: '8px 16px' }}>🔄 Обновить</button>
        <button onClick={() => fetch(`${API}/strategy/trigger`, { method: 'POST' })} style={{ marginRight: 8, padding: '8px 16px' }}>⚡ Пересчитать стратегии</button>
        <button onClick={() => fetch(`${API}/bot/trigger`, { method: 'POST' })} style={{ padding: '8px 16px' }}>▶️ Запустить бота</button>
      </div>
      <h3>Открытые позиции</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead><tr style={{ background: '#2c3e50', color: 'white' }}>
          <th>Тикер</th><th>Направление</th><th>Кол-во</th><th>Вход</th><th>Текущая</th><th>Стоп</th><th>Тейк</th><th>P&L</th>
        </tr></thead>
        <tbody>
          {(positions || []).map(p => (
            <tr key={p.id} style={{ borderBottom: '1px solid #ddd' }}>
              <td>{p.ticker}</td>
              <td>{p.direction}</td>
              <td>{p.quantity}</td>
              <td>{p.entryPrice}</td>
              <td>{p.currentPrice}</td>
              <td>{p.stopLoss}</td>
              <td>{p.takeProfit}</td>
              <td style={{ color: (p.pnl || 0) >= 0 ? '#27ae60' : '#e74c3c' }}>{p.pnl}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SettingsPage() {
  const [settings, setSettings] = useState({});
  useEffect(() => {
    fetch(`${API}/settings`).then(r => r.json()).then(setSettings);
  }, []);
  return (
    <div>
      <h2>Настройки</h2>
      <pre style={{ background: '#f4f4f4', padding: 16, borderRadius: 8 }}>{JSON.stringify(settings, null, 2)}</pre>
    </div>
  );
}

function LogsPage() {
  const [logs, setLogs] = useState([]);
  useEffect(() => {
    fetch(`${API}/logs`).then(r => r.json()).then(setLogs);
  }, []);
  return (
    <div>
      <h2>Логи агентов</h2>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
        <thead><tr style={{ background: '#2c3e50', color: 'white' }}>
          <th>Время</th><th>Агент</th><th>Тикер</th><th>Действие</th><th>Confidence</th><th>Reasoning</th>
        </tr></thead>
        <tbody>
          {logs.map(l => (
            <tr key={l.id} style={{ borderBottom: '1px solid #eee' }}>
              <td>{new Date(l.createdAt).toLocaleString()}</td>
              <td>{l.agentName}</td>
              <td>{l.ticker}</td>
              <td>{l.action}</td>
              <td>{l.confidence?.toFixed(2) ?? '-'}</td>
              <td>{l.reasoning?.substring(0, 80)}...</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Card({ title, value, color }) {
  return (
    <div style={{ background: color, color: 'white', padding: 20, borderRadius: 8, textAlign: 'center' }}>
      <div style={{ fontSize: 12, opacity: 0.85 }}>{title}</div>
      <div style={{ fontSize: 28, fontWeight: 'bold', marginTop: 6 }}>{value}</div>
    </div>
  );
}
