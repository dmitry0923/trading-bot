import React, { useState, useEffect } from 'react';
import axios from 'axios';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, AreaChart, Area
} from 'recharts';

const API = '/api/v1';

export default function App() {
  const [tab, setTab] = useState('dashboard');
  const [settings, setSettings] = useState(null);
  const [strategies, setStrategies] = useState([]);
  const [positions, setPositions] = useState([]);
  const [logs, setLogs] = useState([]);
  const [pnl, setPnl] = useState(0);
  const [loading, setLoading] = useState(false);

  useEffect(() => { loadAll(); const iv = setInterval(loadAll, 10000); return () => clearInterval(iv); }, []);

  async function loadAll() {
    try {
      const [s, p, l, d] = await Promise.all([
        axios.get(`${API}/strategies`),
        axios.get(`${API}/positions`),
        axios.get(`${API}/logs`),
        axios.get(`${API}/risk/daily-pnl`)
      ]);
      setStrategies(s.data);
      setPositions(p.data);
      setLogs(l.data);
      setPnl(d.data.dailyPnl);
    } catch(e) {}
  }

  async function loadSettings() {
    const r = await axios.get(`${API}/settings`);
    setSettings(r.data);
  }

  async function saveSettings() {
    await axios.post(`${API}/settings`, settings);
    alert('Настройки сохранены');
  }

  async function triggerStrategy() {
    setLoading(true);
    await axios.post(`${API}/strategy/trigger`);
    setLoading(false);
    loadAll();
  }

  async function triggerBot() {
    setLoading(true);
    await axios.post(`${API}/bot/trigger`);
    setLoading(false);
    loadAll();
  }

  const pnlColor = pnl >= 0 ? '#4ade80' : '#f87171';

  return (
    <div style={{ minHeight: '100vh', background: '#0b1120' }}>
      <header style={{ background: '#1e293b', padding: '1rem 2rem', borderBottom: '1px solid #334155', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <h1 style={{ margin: 0, fontSize: '1.25rem', color: '#38bdf8' }}>🤖 MMVB AI Trading Bot</h1>
        <nav style={{ display: 'flex', gap: '1.5rem' }}>
          {['dashboard','settings','logs'].map(t => (
            <button key={t} onClick={() => { setTab(t); if(t==='settings') loadSettings(); }}
              style={{ background: 'none', border: 'none', color: tab===t ? '#38bdf8' : '#94a3b8', cursor: 'pointer', fontSize: '0.9rem', fontWeight: tab===t?600:400 }}>
              {t==='dashboard'?'Дашборд':t==='settings'?'Настройки':'Логи'}
            </button>
          ))}
        </nav>
      </header>

      {tab === 'dashboard' && (
        <div style={{ padding: '1.5rem 2rem' }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px,1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
            <Card title="Дневной P&L" value={`${pnl.toFixed(2)} ₽`} color={pnlColor} />
            <Card title="Открытых позиций" value={positions.length} color="#38bdf8" />
            <Card title="Активных стратегий" value={strategies.filter(s=>s.action!=='HOLD').length} color="#a78bfa" />
            <Card title="Режим" value={settings?.tradingMode || 'SIMULATION'} color="#fbbf24" />
          </div>

          <div style={{ marginBottom: '1.5rem' }}>
            <button onClick={triggerStrategy} disabled={loading} style={btnStyle('#2563eb')}>🔄 Пересчитать стратегии</button>
            <button onClick={triggerBot} disabled={loading} style={{...btnStyle('#059669'), marginLeft:8}}>⚡ Запустить бота</button>
            {loading && <span style={{ marginLeft: 12, color: '#94a3b8' }}>Работаю...</span>}
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
            <Panel title="Активные стратегии (Redis)">
              <table style={tableStyle}>
                <thead><tr><th>Тикер</th><th>Действие</th><th>Цена</th><th>Стоп</th><th>Тейк</th><th>Conf</th></tr></thead>
                <tbody>
                  {strategies.slice(0,20).map(s => (
                    <tr key={s.id}>
                      <td><strong>{s.ticker}</strong></td>
                      <td><Badge text={s.action} type={s.action} /></td>
                      <td>{s.targetPrice}</td>
                      <td>{s.stopLoss || '-'}</td>
                      <td>{s.takeProfit || '-'}</td>
                      <td>{(s.confidence*100).toFixed(0)}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Panel>

            <Panel title="Открытые позиции">
              <table style={tableStyle}>
                <thead><tr><th>Тикер</th><th>Dir</th><th>Кол-во</th><th>Вход</th><th>Стоп</th><th>Тейк</th><th>P&L</th></tr></thead>
                <tbody>
                  {positions.map(p => (
                    <tr key={p.id}>
                      <td><strong>{p.ticker}</strong></td>
                      <td><span style={{color:p.direction==='LONG'?'#4ade80':'#f87171'}}>{p.direction}</span></td>
                      <td>{p.quantity}</td>
                      <td>{p.entryPrice}</td>
                      <td>{p.stopLoss || '-'}</td>
                      <td>{p.takeProfit || '-'}</td>
                      <td style={{color: (p.pnl||0)>=0 ? '#4ade80':'#f87171'}}>{p.pnl ? p.pnl.toFixed(2) : '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Panel>
          </div>
        </div>
      )}

      {tab === 'settings' && settings && (
        <div style={{ padding: '1.5rem 2rem', maxWidth: 700 }}>
          <Panel title="Торговые интервалы (мс)">
            <Field label="Интервал бота (мс) — как часто проверять позиции и открывать новые" value={settings.botIntervalMs} onChange={v=>setSettings({...settings, botIntervalMs:Number(v)})} />
            <Field label="Интервал стратегии (мс) — как часто пересчитывать анализ" value={settings.strategyIntervalMs} onChange={v=>setSettings({...settings, strategyIntervalMs:Number(v)})} />
            <Field label="Интервал мониторинга (мс) — как часто двигать стопы/тейки" value={settings.monitorIntervalMs} onChange={v=>setSettings({...settings, monitorIntervalMs:Number(v)})} />
          </Panel>
          <Panel title="Условия входа">
            <Field label="Макс. открытых позиций для НОВОГО входа (0 = входим только если 0 позиций)" value={settings.maxOpenPositionsForNewEntry} onChange={v=>setSettings({...settings, maxOpenPositionsForNewEntry:Number(v)})} />
            <Field label="Макс. позиция (₽)" value={settings.maxPositionRub} onChange={v=>setSettings({...settings, maxPositionRub:v})} />
            <Field label="Макс. дневной убыток (₽)" value={settings.maxDailyLossRub} onChange={v=>setSettings({...settings, maxDailyLossRub:v})} />
          </Panel>
          <Panel title="Стопы и тейки">
            <Field label="Стоп-лосс (%)" value={settings.stopLossPercent} onChange={v=>setSettings({...settings, stopLossPercent:Number(v)})} />
            <Field label="Тейк-профит (%)" value={settings.takeProfitPercent} onChange={v=>setSettings({...settings, takeProfitPercent:Number(v)})} />
            <Field label="Трейлинг-стоп (%)" value={settings.trailingStopPercent} onChange={v=>setSettings({...settings, trailingStopPercent:Number(v)})} />
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
              <input type="checkbox" checked={settings.trailingStopEnabled} onChange={e=>setSettings({...settings, trailingStopEnabled:e.target.checked})} />
              <span>Трейлинг-стоп включен</span>
            </label>
          </Panel>
          <button onClick={saveSettings} style={btnStyle('#2563eb')}>💾 Сохранить настройки</button>
        </div>
      )}

      {tab === 'logs' && (
        <div style={{ padding: '1.5rem 2rem' }}>
          <Panel title="Логи агентов">
            <table style={tableStyle}>
              <thead><tr><th>Время</th><th>Агент</th><th>Тикер</th><th>Действие</th><th>Conf</th><th>Обоснование</th></tr></thead>
              <tbody>
                {logs.map(l => (
                  <tr key={l.id}>
                    <td>{new Date(l.createdAt).toLocaleTimeString()}</td>
                    <td style={{color:'#38bdf8'}}>{l.agentName}</td>
                    <td>{l.ticker}</td>
                    <td><strong>{l.action}</strong></td>
                    <td>{l.confidence ? (l.confidence*100).toFixed(0)+'%' : '-'}</td>
                    <td style={{maxWidth:400, overflow:'hidden', textOverflow:'ellipsis', whiteSpace:'nowrap'}}>{l.reasoning || ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Panel>
        </div>
      )}
    </div>
  );
}

function Card({ title, value, color }) {
  return (
    <div style={{ background: '#1e293b', borderRadius: 8, padding: '1.25rem', border: '1px solid #334155' }}>
      <div style={{ fontSize: '0.75rem', color: '#94a3b8', textTransform: 'uppercase', marginBottom: 6 }}>{title}</div>
      <div style={{ fontSize: '1.5rem', fontWeight: 700, color }}>{value}</div>
    </div>
  );
}

function Panel({ title, children }) {
  return (
    <div style={{ background: '#1e293b', borderRadius: 8, padding: '1.25rem', border: '1px solid #334155', marginBottom: '1rem' }}>
      <h3 style={{ margin: '0 0 1rem 0', fontSize: '1rem', color: '#f1f5f9' }}>{title}</h3>
      {children}
    </div>
  );
}

function Field({ label, value, onChange }) {
  return (
    <div style={{ marginBottom: '0.75rem' }}>
      <label style={{ display: 'block', fontSize: '0.8rem', color: '#94a3b8', marginBottom: 4 }}>{label}</label>
      <input type="text" value={value} onChange={e=>onChange(e.target.value)}
        style={{ width: '100%', padding: '0.5rem', background: '#0f172a', border: '1px solid #334155', color: '#e2e8f0', borderRadius: 4 }} />
    </div>
  );
}

function Badge({ text, type }) {
  const colors = { BUY: '#4ade80', SELL: '#f87171', HOLD: '#fbbf24', CLOSE: '#94a3b8' };
  return <span style={{ color: colors[type] || '#e2e8f0', fontWeight: 600 }}>{text}</span>;
}

const btnStyle = (bg) => ({ background: bg, color: 'white', border: 'none', padding: '0.6rem 1.2rem', borderRadius: 6, cursor: 'pointer', fontSize: '0.9rem' });
const tableStyle = { width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' };
