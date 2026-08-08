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

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ borderTop: '1px solid #ddd', paddingTop: 12, marginTop: 8 }}>
      <h3 style={{ margin: '0 0 8px' }}>{title}</h3>
      {children}
    </div>
  );
}

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

  const num = (key: keyof BotSettings, step?: string) => (
    <input
      type="number"
      step={step}
      value={String(form[key])}
      disabled={!canEdit}
      onChange={e => update(key, Number(e.target.value))}
      style={{ width: 140 }}
    />
  );

  const bool = (
    key: 'tradingEnabled' | 'riskEnabled' | 'trailingStopEnabled' | 'leverageEnabled' | 'forceCloseEnabled' | 'shadowModeEnabled' | 'volatilityIndexEnabled'
  ) => (
    <input type="checkbox" checked={form[key]} disabled={!canEdit} onChange={e => update(key, e.target.checked)} />
  );

  return (
    <div>
      <h2>Runtime Settings</h2>
      {!canEdit && (
        <div style={{ color: '#c62828', marginBottom: 12 }}>
          У вас роль ANALYTICS — только просмотр. Изменение настроек доступно администратору.
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 560 }}>
        <label style={row}>
          Trading Enabled
          {bool('tradingEnabled')}
        </label>
        <label style={row}>
          Risk Enabled
          {bool('riskEnabled')}
        </label>
        <label style={row}>
          Trading Mode
          <select value={form.tradingMode} disabled={!canEdit} onChange={e => update('tradingMode', e.target.value)}>
            <option value="SIMULATION">SIMULATION</option>
            <option value="LIVE">LIVE</option>
          </select>
        </label>

        <Section title="Риск-менеджмент (применяется сразу)">
          <label style={row}>
            Max Position (RUB)
            {num('maxPositionRub')}
          </label>
          <label style={row}>
            Max Daily Loss (RUB)
            {num('maxDailyLossRub')}
          </label>
          <label style={row}>
            Max Open Positions
            {num('maxOpenPositions')}
          </label>
          <label style={row}>
            Futures Max Open Positions
            {num('futuresMaxOpenPositions')}
          </label>
          <label style={row}>
            Max Sector Exposure
            {num('maxSectorExposure')}
          </label>
          <label style={row}>
            Max Volatility (%)
            {num('maxVolatilityPercent', '0.5')}
          </label>
          <label style={row}>
            Default Stop Loss (%)
            {num('defaultStopLossPercent', '0.1')}
          </label>
          <label style={row}>
            Default Take Profit (%)
            {num('defaultTakeProfitPercent', '0.1')}
          </label>
          <label style={row}>
            Trailing Stop Enabled
            {bool('trailingStopEnabled')}
          </label>
          <label style={row}>
            Trailing Stop (%)
            {num('trailingStopPercent', '0.1')}
          </label>
          <label style={row}>
            Risk per Trade (%)
            {num('riskPerTradePercent', '0.1')}
          </label>
          <label style={row}>
            Kelly Fraction
            {num('kellyFraction', '0.05')}
          </label>
          <label style={row}>
            Trading Hours Start (МСК)
            <input type="time" value={form.tradingHoursStart} disabled={!canEdit} onChange={e => update('tradingHoursStart', e.target.value)} />
          </label>
          <label style={row}>
            Trading Hours End (МСК)
            <input type="time" value={form.tradingHoursEnd} disabled={!canEdit} onChange={e => update('tradingHoursEnd', e.target.value)} />
          </label>
        </Section>

        <Section title="Multi-Tier Drawdown Protection (% от AUM)">
          <label style={row}>
            Max Daily Loss (% AUM)
            {num('maxDailyLossPercent', '0.1')}
          </label>
          <label style={row}>
            Max Rolling Loss 7d (% AUM)
            {num('maxRollingLossPercent7d', '0.1')}
          </label>
          <label style={row}>
            Max Rolling Loss 30d (% AUM)
            {num('maxRollingLossPercent30d', '0.1')}
          </label>
          <label style={row}>
            Max Consecutive Losses
            {num('maxConsecutiveLosses')}
          </label>
          <label style={row}>
            Shadow Mode Enabled
            {bool('shadowModeEnabled')}
          </label>
          <label style={row}>
            Shadow Mode Cooldown (h)
            {num('shadowModeCooldownHours')}
          </label>
        </Section>

        <Section title="Volatility Index Filter (MOEX RVI)">
          <label style={row}>
            Enabled
            {bool('volatilityIndexEnabled')}
          </label>
          <label style={row}>
            Max Volatility Index (%)
            {num('maxVolatilityIndexPercent', '1')}
          </label>
        </Section>

        <Section title="Плечо (фьючерсы, применяется сразу)">
          <label style={row}>
            Leverage Enabled
            {bool('leverageEnabled')}
          </label>
          <label style={row}>
            User Leverage
            {num('userLeverage', '0.1')}
          </label>
          <label style={row}>
            Min Leverage
            {num('minLeverage', '0.1')}
          </label>
          <label style={row}>
            Max Leverage
            {num('maxLeverage', '0.1')}
          </label>
        </Section>

        <Section title="Интервалы циклов (применяются после перезапуска)">
          <label style={row}>
            Bot Interval (ms)
            {num('botIntervalMs')}
          </label>
          <label style={row}>
            Strategy Interval (ms)
            {num('strategyIntervalMs')}
          </label>
          <div style={{ color: '#666', fontSize: 13 }}>
            Планировщик @Scheduled фиксирует интервалы при старте — изменения вступят в силу после перезапуска.
          </div>
        </Section>

        <Section title="LLM Provider">
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
                style={{ width: 300 }}
              />
            </label>
          ))}
          <div style={{ color: '#666', fontSize: 13 }}>
            По умолчанию: {llmProviders?.active || 'ROUTER_AI'} ({llmProviders?.default || 'ROUTER_AI'}). Пустое поле «Модель» = модель агрегатора.
          </div>
        </Section>

        <Section title="Timeframes">
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
        </Section>

        <Section title="Force Close">
          <label style={row}>
            Enabled
            {bool('forceCloseEnabled')}
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
        </Section>

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
            {!tradingStatus.tradingEnabled && (tradingStatus.reason || (tradingStatus.blocks ?? []).length > 0) && (
              <div style={{ marginTop: 6, lineHeight: 1.5 }}>
                {(tradingStatus.blocks ?? []).length > 0 ? (
                  tradingStatus.blocks!.map((b, i) => (
                    <div key={i} style={{ color: b.ticker ? '#9e6d00' : '#c62828' }}>
                      {b.ticker ? `[${b.ticker}] ` : ''}<b>{b.reason}</b> ({b.source}){b.detail ? ` — ${b.detail}` : ''}
                    </div>
                  ))
                ) : (
                  <div style={{ color: '#c62828' }}>
                    <b>{tradingStatus.reason}</b> ({tradingStatus.source}){tradingStatus.detail ? ` — ${tradingStatus.detail}` : ''}
                  </div>
                )}
                {tradingStatus.blockedAt && <div style={{ color: '#888', fontSize: 12 }}>с {new Date(tradingStatus.blockedAt).toLocaleString()}</div>}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function toggle(add: boolean, list: string[], value: string): string[] {
  return add ? (list.includes(value) ? list : [...list, value]) : list.filter(v => v !== value);
}
