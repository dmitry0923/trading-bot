import React from 'react';
import DashboardPage from './pages/DashboardPage';
import PositionsPage from './pages/PositionsPage';
import StrategiesPage from './pages/StrategiesPage';
import LogsPage from './pages/LogsPage';
import AnalyticsPage from './pages/AnalyticsPage';
import SettingsPage from './pages/SettingsPage';
import BacktestPage from './pages/BacktestPage';
import InvestorsPage from './pages/InvestorsPage';
import ForecastPage from './pages/ForecastPage';
import LoginPage from './LoginPage';
import { useAuth } from './auth';

const TABS = [
  { key: 'dashboard', label: 'Dashboard' },
  { key: 'positions', label: 'Positions' },
  { key: 'strategies', label: 'Strategies' },
  { key: 'logs', label: 'Agents Log' },
  { key: 'analytics', label: 'Analytics' },
  { key: 'investors', label: 'Investors' },
  { key: 'forecast', label: 'Forecast' },
  { key: 'settings', label: 'Settings' },
  { key: 'backtest', label: 'Backtest' }
] as const;

type TabKey = typeof TABS[number]['key'];

function App() {
  const { status, user, signIn, signOut } = useAuth();
  const [tab, setTab] = React.useState<TabKey>('dashboard');
  const isAdmin = user ? user.roles.includes('ROLE_ADMIN') : false;

  if (status === 'loading') {
    return <div style={{ fontFamily: 'Segoe UI, Arial, sans-serif', padding: 40 }}>Загрузка…</div>;
  }

  if (status === 'unauthenticated') {
    return <LoginPage onSignIn={signIn} />;
  }

  return (
    <div style={{ fontFamily: 'Segoe UI, Arial, sans-serif', padding: 20, maxWidth: 1200, margin: '0 auto' }}>
      <h1 style={{ color: '#1a1a2e' }}>
        Trading Bot Dashboard v2
        {user && (
          <span style={{ fontSize: 13, color: '#666', fontWeight: 400, marginLeft: 12 }}>
            {user.username} · {user.roles.map(r => r.replace('ROLE_', '')).join(', ')}
          </span>
        )}
        <button
          onClick={() => signOut()}
          style={{
            marginLeft: 16,
            padding: '4px 12px',
            cursor: 'pointer',
            border: '1px solid #ccc',
            borderRadius: 4,
            background: '#fff',
            color: '#1a1a2e',
            fontSize: 13
          }}
        >
          Logout
        </button>
      </h1>
      <nav style={{ display: 'flex', gap: 8, marginBottom: 20, flexWrap: 'wrap' }}>
        {TABS.map(t => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            style={{
              padding: '8px 16px',
              cursor: 'pointer',
              border: '1px solid #ccc',
              borderRadius: 4,
              background: tab === t.key ? '#1a1a2e' : '#fff',
              color: tab === t.key ? '#fff' : '#1a1a2e',
              fontWeight: tab === t.key ? 600 : 400
            }}
          >
            {t.label}
          </button>
        ))}
      </nav>
      {tab === 'dashboard' && <DashboardPage />}
      {tab === 'positions' && <PositionsPage />}
      {tab === 'strategies' && <StrategiesPage />}
      {tab === 'logs' && <LogsPage />}
      {tab === 'analytics' && <AnalyticsPage />}
      {tab === 'investors' && <InvestorsPage />}
      {tab === 'forecast' && <ForecastPage />}
      {tab === 'settings' && <SettingsPage canEdit={isAdmin} />}
      {tab === 'backtest' && <BacktestPage />}
    </div>
  );
}

export default App;
