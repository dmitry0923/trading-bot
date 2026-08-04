import React from 'react';
import { useFetch, post } from '../api';
import type { BotSettings } from '../types';

export default function SettingsPage() {
  const { data: settings, error, reload } = useFetch<BotSettings>('/api/v1/settings');
  const [form, setForm] = React.useState<BotSettings | null>(null);
  const [saved, setSaved] = React.useState('');

  React.useEffect(() => {
    if (settings && !form) setForm(settings);
  }, [settings, form]);

  if (error) return <div>Error: {error}</div>;
  if (!form) return <div>Loading settings...</div>;

  const update = <K extends keyof BotSettings>(key: K, value: BotSettings[K]) =>
    setForm({ ...form, [key]: value });

  const save = async () => {
    try {
      await post('/api/v1/settings', form);
      setSaved('Saved at ' + new Date().toLocaleTimeString());
      reload();
    } catch (e) {
      setSaved('Error: ' + (e as Error).message);
    }
  };

  return (
    <div>
      <h2>Runtime Settings</h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 420 }}>
        <label>
          Trading Enabled
          <input type="checkbox" checked={form.tradingEnabled} onChange={e => update('tradingEnabled', e.target.checked)} style={{ marginLeft: 8 }} />
        </label>
        <label>
          Risk Enabled
          <input type="checkbox" checked={form.riskEnabled} onChange={e => update('riskEnabled', e.target.checked)} style={{ marginLeft: 8 }} />
        </label>
        <label>
          Max Position (RUB)
          <input type="number" value={form.maxPositionRub} onChange={e => update('maxPositionRub', Number(e.target.value))} style={{ marginLeft: 8 }} />
        </label>
        <label>
          Max Daily Loss (RUB)
          <input type="number" value={form.maxDailyLossRub} onChange={e => update('maxDailyLossRub', Number(e.target.value))} style={{ marginLeft: 8 }} />
        </label>
        <button onClick={save} style={{ padding: 10, cursor: 'pointer' }}>Save</button>
        {saved && <div style={{ color: '#2e7d32' }}>{saved}</div>}
      </div>
      <div style={{ marginTop: 20, color: '#666', fontSize: 13 }}>
        Trading mode is controlled via env <code>TRADING_MODE</code> (SIMULATION | LIVE).
      </div>
    </div>
  );
}
