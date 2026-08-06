import React from 'react';
import { useFetch } from '../api';
import type { DrawdownStatus } from '../types';

function fmt(n: number | string | undefined): string {
  return n === undefined || n === null ? '—' : Number(n).toLocaleString('ru-RU');
}

function fmtPercent(n: number | string | undefined): string {
  return n === undefined || n === null ? '—' : Number(n).toFixed(1) + '%';
}

function fmtMoney(n: number | string | undefined): string {
  return n === undefined || n === null ? '—' : Number(n).toLocaleString('ru-RU') + ' ₽';
}

function badge(ok: boolean): React.ReactNode {
  return (
    <span
      style={{
        display: 'inline-block',
        padding: '2px 8px',
        borderRadius: 4,
        fontSize: 12,
        fontWeight: 600,
        color: ok ? '#fff' : '#1a1a2e',
        background: ok ? '#c62828' : '#e0e0e0'
      }}
    >
      {ok ? 'BLOCKED' : 'OK'}
    </span>
  );
}

export default function DrawdownWidget() {
  const { data, error } = useFetch<DrawdownStatus>('/api/v1/risk/drawdown', 5000);

  if (!data || data.aum === undefined || data.aum === null) return null;
  if (error) return <div style={{ fontSize: 12, color: '#c62828', marginBottom: 12 }}>Drawdown status: {error}</div>;

  const blocking = data.dailyLimitBreached || data.rolling7dBreached || data.rolling30dBreached || data.shadowModeActive;

  const item: React.CSSProperties = { flex: 1, minWidth: 150, padding: 8, border: '1px solid #ddd', borderRadius: 6, background: '#fff' };

  return (
    <div style={{ marginBottom: 16, border: `1px solid ${blocking ? '#c62828' : '#ddd'}`, borderRadius: 8, padding: 12, background: blocking ? '#fdecea' : '#fafafa' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <b>Multi-Tier Drawdown Protection</b>
        <span style={{ fontSize: 13, color: blocking ? '#c62828' : '#2e7d32', fontWeight: 700 }}>
          {blocking ? 'ENTRIES BLOCKED' : 'Entries allowed'}
        </span>
      </div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <div style={item}>AUM: <b>{fmtMoney(data.aum)}</b></div>
        <div style={item}>
          Daily: {fmtMoney(data.dailyPnlRub)} / -{fmtMoney(data.dailyLimitRub)} {badge(data.dailyLimitBreached)}
        </div>
        <div style={item}>
          Rolling 7d: {fmtPercent(data.rolling7dPnlRub)} / -{fmt(data.rolling7dLimitRub)} ₽ {badge(data.rolling7dBreached)}
        </div>
        <div style={item}>
          Rolling 30d: {fmtPercent(data.rolling30dPnlRub)} / -{fmt(data.rolling30dLimitRub)} ₽ {badge(data.rolling30dBreached)}
        </div>
        <div style={item}>
          Losses in a row: {data.consecutiveLosses}/{data.maxConsecutiveLosses} · Shadow:{' '}
          <b style={{ color: data.shadowModeActive ? '#c62828' : '#2e7d32' }}>{data.shadowModeActive ? 'ON' : 'off'}</b>
        </div>
      </div>
      {data.reasons && data.reasons.length > 0 && (
        <div style={{ marginTop: 8, fontSize: 12, color: '#c62828' }}>
          {data.reasons.join('; ')}
        </div>
      )}
    </div>
  );
}
