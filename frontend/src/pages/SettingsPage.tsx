import React from 'react';
import { useFetch, post } from '../api';
import type { BotSettings, LlmProviders, TradingStatus } from '../types';

const PROVIDERS: Record<string, string> = {
  ROUTER_AI: 'Router AI (агрегатор, по умолчанию)',
  KIMI: 'Kimi (Moonshot)',
  DEEPSEEK: 'DeepSeek',
  QWEN: 'Qwen (Alibaba)'
};

const LLM_FIELDS: { key: 'llmModel' | 'llmBaseUrl' | 'llmApiKey'; label: string }[] = [
  { key: 'llmModel', label: 'Модель' },
  { key: 'llmBaseUrl', label: 'Base URL' },
  { key: 'llmApiKey', label: 'API Key' }
];

export default function SettingsPage({ canEdit }: { canEdit: boolean }) {
  const { data: settings, error, reload } = useFetch<BotSettings>('/api/v1/settings');
  const { data: llmProviders } = useFetch<LlmProviders>('/api/v1/llm/providers');
  const { data: tradingStatus, reload: reloadStatus } = useFetch<TradingStatus>('/api/v1/trading/status');
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
      reloadStatus();
    } catch (e) {
      setSaved('Error: ' + (e as Error).message);
    }
  };

  const run = async (path: string, label: string) => {
    try {
      await post(path);
      setSaved(label + ' OK');
      reload();
      reloadStatus();
    } catch (e) {
      setSaved(label + ' Error: ' + (e as Error).message);
    }
  };

  const row: React.CSSProperties = { display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 };

  return (
    <div>
      <h2>Runtime Settings</h2>
      {!canEdit && (
        <div style={{ color: '#c62828', marginBottom: 12 }}>
          У вас роль ANALYTICS — только просмотр. Изменение настроек доступно администратору.
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 520 }}>
        <label style={row}>
          Trading Enabled
          <input type="checkbox" checked={form.tradingEnabled} disabled={!canEdit} onChange={e => update('tradingEnabled', e.target.checked)} />
        </label>
        <label style={row}>
          Risk Enabled
          <input type="checkbox" checked={form.riskEnabled} disabled={!canEdit} onChange={e => update('riskEnabled', e.target.checked)} />
        </label>
        <label style={row}>
          Trading Mode
          <select value={form.tradingMode} disabled={!canEdit} onChange={e => update('tradingMode', e.target.value)}>
            <option value="SIMULATION">SIMULATION</option>
            <option value="LIVE">LIVE</option>
          </select>
        </label>
        <label style={row}>
          Max Position (RUB)
          <input type="number" value={form.maxPositionRub} disabled={!canEdit} onChange={e => update('maxPositionRub', Number(e.target.value))} />
        </label>
        <label style={row}>
          Max Daily Loss (RUB)
          <input type="number" value={form.maxDailyLossRub} disabled={!canEdit} onChange={e => update('maxDailyLossRub', Number(e.target.value))} />
        </label>
        <label style={row}>
          Kelly Fraction
          <input type="number" step="0.05" min="0" max="1" value={form.kellyFraction} disabled={!canEdit} onChange={e => update('kellyFraction', Number(e.target.value))} />
        </label>
        <label style={row}>
          Max Open Positions
          <input type="number" value={form.maxOpenPositions} disabled={!canEdit} onChange={e => update('maxOpenPositions', Number(e.target.value))} />
        </label>

        <div style={{ borderTop: '1px solid #ddd', paddingTop: 12 }}>
          <h3 style={{ margin: '0 0 8px' }}>LLM Provider</h3>
          <label style={row}>
            Provider
            <select value={form.llmProvider} disabled={!canEdit} onChange={e => update('llmProvider', e.target.value)}>
              {Object.entries(PROVIDERS).map(([key, label]) => (
                <option key={key} value={key}>{label}</option>
              ))}
            </select>
          </label>
          {LLM_FIELDS.map(f => (
            <label key={f.key} style={row}>
              {f.label}
              <input
                type="text"
                value={String(form[f.key])}
                disabled={!canEdit}
                placeholder={f.key === 'llmModel' ? llmProviders?.active + ' default' : ''}
                onChange={e => update(f.key, e.target.value)}
                style={{ width: 260 }}
              />
            </label>
          ))}
          <div style={{ color: '#666', fontSize: 13 }}>
            По умолчанию: {llmProviders?.active || 'ROUTER_AI'} ({llmProviders?.default || 'ROUTER_AI'}). Пустое поле «Модель» = модель агрегатора.
          </div>
        </div>

        <div style={{ borderTop: '1px solid #ddd', paddingTop: 12 }}>
          <h3 style={{ margin: '0 0 8px' }}>Timeframes</h3>
          <div style={row}>
            <span>Активные</span>
            <span>{form.timeframes.join(', ') || '—'}</span>
          </div>
          <label style={row}>
            MINUTE_10
            <input type="checkbox" checked={form.timeframes.includes('MINUTE_10')} disabled={!canEdit} onChange={e => update('timeframes', toggle(e.target.checked, form.timeframes, 'MINUTE_10'))} />
          </label>
          <label style={row}>
            HOUR_1
            <input type="checkbox" checked={form.timeframes.includes('HOUR_1')} disabled={!canEdit} onChange={e => update('timeframes', toggle(e.target.checked, form.timeframes, 'HOUR_1'))} />
          </label>
          <label style={row}>
            DAY_1
            <input type="checkbox" checked={form.timeframes.includes('DAY_1')} disabled={!canEdit} onChange={e => update('timeframes', toggle(e.target.checked, form.timeframes, 'DAY_1'))} />
          </label>
        </div>

        <div style={{ borderTop: '1px solid #ddd', paddingTop: 12 }}>
          <h3 style={{ margin: '0 0 8px' }}>Force Close</h3>
          <label style={row}>
            Enabled
            <input type="checkbox" checked={form.forceCloseEnabled} disabled={!canEdit} onChange={e => update('forceCloseEnabled', e.target.checked)} />
          </label>
          <label style={row}>
            Time (HH:mm)
            <input type="time" value={form.forceCloseTime} disabled={!canEdit} onChange={e => update('forceCloseTime', e.target.value)} />
          </label>
          {tradingStatus?.forceCloseEnabled && tradingStatus.forceCloseTime && (
            <div style={{ color: '#666', fontSize: 13 }}>
              Активно: авто-закрытие всех позиций в {tradingStatus.forceCloseTime} (Europe/Moscow).
            </div>
          )}
        </div>

        <button onClick={save} disabled={!canEdit} style={{ padding: 10, cursor: canEdit ? 'pointer' : 'not-allowed' }}>Save Settings</button>
        {saved && <div style={{ color: '#2e7d32' }}>{saved}</div>}
      </div>

      <div style={{ marginTop: 24, borderTop: '1px solid #ddd', paddingTop: 12 }}>
        <h3 style={{ margin: '0 0 8px' }}>Trading Control</h3>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button onClick={() => run('/api/v1/trading/enable', 'Enable')} disabled={!canEdit || tradingStatus?.tradingEnabled} style={{ padding: 8, cursor: 'pointer' }}>Enable Trading</button>
          <button onClick={() => run('/api/v1/trading/disable', 'Disable')} disabled={!canEdit || !tradingStatus?.tradingEnabled} style={{ padding: 8, cursor: 'pointer' }}>Disable Trading</button>
          <button onClick={() => run('/api/v1/trading/force-close?reason=MANUAL', 'Force close')} disabled={!canEdit} style={{ padding: 8, cursor: 'pointer', background: '#c62828', color: '#fff', border: 'none' }}>Force Close Now</button>
        </div>
        {tradingStatus && (
          <div style={{ marginTop: 8, color: '#666', fontSize: 13 }}>
            Trading: <b>{tradingStatus.tradingEnabled ? 'ENABLED' : 'DISABLED'}</b> · mode {tradingStatus.tradingMode} · open positions {tradingStatus.openPositions}
          </div>
        )}
      </div>
    </div>
  );
}

function toggle(add: boolean, list: string[], value: string): string[] {
  return add ? (list.includes(value) ? list : [...list, value]) : list.filter(v => v !== value);
}
