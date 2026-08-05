import React from 'react';
import { useFetch, post } from '../api';
import type { ProfitForecast, PoolStats } from '../types';

export default function ForecastPage() {
  const [horizon, setHorizon] = React.useState(90);
  const { data: forecast, error, reload } = useFetch<ProfitForecast>('/api/v1/forecast?horizonDays=' + horizon);
  const { data: pool } = useFetch<PoolStats>('/api/v1/clearing/pool');

  if (error) return <div>Error: {error}</div>;
  if (!forecast) return <div>Loading forecast...</div>;

  const fmt = (v: unknown) => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(Number(v));

  return (
    <div>
      <h2>Profit Forecast</h2>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16 }}>
        <label>
          Horizon (days)
          <input type="number" min="1" max="365" value={horizon} onChange={e => setHorizon(Number(e.target.value))} style={{ marginLeft: 8 }} />
        </label>
        <button onClick={reload}>Recalculate</button>
      </div>

      <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', marginBottom: 20 }}>
        <div style={{ border: '1px solid #ddd', borderRadius: 8, padding: 16, minWidth: 180 }}>
          <div style={{ color: '#666', fontSize: 13 }}>Expected return ({forecast.horizonDays} d)</div>
          <div style={{ fontSize: 22, fontWeight: 600 }}>{fmt(forecast.expectedReturnPercent)}%</div>
        </div>
        <div style={{ border: '1px solid #ddd', borderRadius: 8, padding: 16, minWidth: 180 }}>
          <div style={{ color: '#666', fontSize: 13 }}>Annual (252 d)</div>
          <div style={{ fontSize: 22, fontWeight: 600 }}>{fmt(forecast.expectedReturnAnnualPercent)}%</div>
        </div>
        <div style={{ border: '1px solid #ddd', borderRadius: 8, padding: 16, minWidth: 180 }}>
          <div style={{ color: '#666', fontSize: 13 }}>95% CI</div>
          <div style={{ fontSize: 18, fontWeight: 600 }}>
            {fmt(forecast.confidenceLowPercent)}% … {fmt(forecast.confidenceHighPercent)}%
          </div>
        </div>
        <div style={{ border: '1px solid #ddd', borderRadius: 8, padding: 16, minWidth: 180 }}>
          <div style={{ color: '#666', fontSize: 13 }}>Daily mean / σ</div>
          <div style={{ fontSize: 18, fontWeight: 600 }}>
            {forecast.dailyMeanReturnPercent}% / {forecast.dailyVolatilityPercent}%
          </div>
        </div>
      </div>

      {pool && (
        <div style={{ color: '#333', marginBottom: 12 }}>
          {fmt(pool.poolContributed)} ₽ вложено · ожидаемая прибыль на горизонте ≈{' '}
          <b>{fmt(Number(pool.poolContributed) * forecast.expectedReturnPercent / 100)} ₽</b>
        </div>
      )}

      <div style={{ color: '#666', fontSize: 13 }}>
        Сделок в анализе: <b>{forecast.tradesAnalyzed}</b>. {forecast.note}
      </div>
    </div>
  );
}
