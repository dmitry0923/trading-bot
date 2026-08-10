import React from 'react';
import { useFetch } from '../api';
import type { RiskExposureReport, CorrelationMatrix } from '../types';

function fmt(n: number | string | undefined | null, digits = 2): string {
  if (n === undefined || n === null) return '—';
  const num = Number(n);
  return num.toLocaleString('ru-RU', { maximumFractionDigits: digits });
}

function fmtPercent(n: number | string | undefined | null, digits = 1): string {
  if (n === undefined || n === null) return '—';
  return Number(n).toFixed(digits) + '%';
}

function fmtMoney(n: number | string | undefined | null): string {
  if (n === undefined || n === null) return '—';
  return Number(n).toLocaleString('ru-RU', { maximumFractionDigits: 0 }) + ' ₽';
}

function scoreColor(score: number): string {
  if (score >= 70) return '#c62828';
  if (score >= 40) return '#f57c00';
  return '#2e7d32';
}

function scoreLevel(score: number): string {
  if (score >= 70) return 'HIGH';
  if (score >= 40) return 'MEDIUM';
  return 'LOW';
}

function corrColor(r: number | null | undefined): string {
  if (r === null || r === undefined) return '#f0f0f0';
  if (r >= 0) return `rgba(198, 40, 40, ${(0.06 + 0.88 * Math.min(1, r)).toFixed(3)})`;
  return `rgba(21, 101, 192, ${(0.06 + 0.88 * Math.min(1, -r)).toFixed(3)})`;
}

function corrTextColor(r: number | null | undefined): string {
  if (r === null || r === undefined) return '#888';
  return Math.abs(r) > 0.55 ? '#fff' : '#1a1a2e';
}

function ExposureBar({ label, value, limit, digits = 0 }: { label: string; value: number; limit: number; digits?: number }) {
  const pct = limit > 0 ? Math.min(100, (value / limit) * 100) : 0;
  const ok = value <= limit;
  return (
    <div style={{ flex: 1, minWidth: 220, padding: 10, border: '1px solid #ddd', borderRadius: 6, background: '#fff' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
        <b>{label}</b>
        <span style={{ color: ok ? '#2e7d32' : '#c62828', fontWeight: 600 }}>
          {value.toFixed(digits)}% / {limit.toFixed(digits)}%
        </span>
      </div>
      <div style={{ height: 10, background: '#eee', borderRadius: 5, overflow: 'hidden' }}>
        <div style={{ width: `${pct}%`, height: '100%', background: ok ? '#2e7d32' : '#c62828' }} />
      </div>
    </div>
  );
}

function CorrHeatmap({ matrix }: { matrix: CorrelationMatrix }) {
  const tickers = Object.keys(matrix || {});
  if (tickers.length === 0) {
    return <div style={{ fontSize: 12, color: '#888' }}>Нет данных для корреляционной матрицы.</div>;
  }
  return (
    <div style={{ overflowX: 'auto' }}>
      <table border={0} cellPadding={0} style={{ borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={{ padding: '4px 6px', textAlign: 'left', fontSize: 12 }} />
            {tickers.map(t => (
              <th key={t} style={{ padding: '4px 6px', fontSize: 12, fontWeight: 600 }}>{t}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {tickers.map(a => (
            <tr key={a}>
              <td style={{ padding: '4px 6px', fontSize: 12, fontWeight: 600, textAlign: 'left' }}>{a}</td>
              {tickers.map(b => {
                const r = matrix[a]?.[b] ?? null;
                const bg = corrColor(r);
                return (
                  <td
                    key={b}
                    style={{
                      padding: '6px 8px',
                      textAlign: 'center',
                      fontSize: 12,
                      background: bg,
                      color: corrTextColor(r),
                      minWidth: 52
                    }}
                  >
                    {r === null ? '—' : Number(r).toFixed(2)}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const TIMEFRAMES = ['MINUTE_10', 'HOUR_1', 'DAY_1'];

export default function CorrelationPage() {
  const [timeframe, setTimeframe] = React.useState('MINUTE_10');
  const { data: report, error: reportError } = useFetch<RiskExposureReport>('/api/v1/risk/exposure', 5000);
  const { data: matrix, error: matrixError } = useFetch<CorrelationMatrix>(
    `/api/v1/risk/correlation?timeframe=${timeframe}&period=50`,
    5000
  );

  if (reportError && !report) return <div>Error: {reportError}</div>;
  if (!report) return <div>Loading exposure...</div>;

  const score = report.exposureScore;
  const gross = Number(report.grossExposurePercent);
  const grossLimit = Number(report.grossLimitPercent);
  const net = Number(report.netExposurePercent);
  const netLimit = Number(report.netLimitPercent);

  const item: React.CSSProperties = { flex: 1, minWidth: 170, padding: 10, border: '1px solid #ddd', borderRadius: 6, background: '#fff' };

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>Correlation Engine</h2>

      <div style={{ display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <div style={{ ...item, background: scoreColor(score), color: '#fff', minWidth: 150 }}>
          <div style={{ fontSize: 13, opacity: 0.9 }}>Exposure Score</div>
          <div style={{ fontSize: 36, fontWeight: 800 }}>{score}</div>
          <div style={{ fontSize: 13, opacity: 0.9 }}>{scoreLevel(score)} · 0–100</div>
        </div>
        <div style={item}>
          AUM: <b>{fmtMoney(report.aum)}</b>
          <div style={{ fontSize: 12, color: '#666', marginTop: 4 }}>Gross {fmtMoney(report.grossExposureRub)}</div>
          <div style={{ fontSize: 12, color: '#666' }}>Net {fmtMoney(report.netExposureRub)}</div>
        </div>
        <div style={item}>
          Effective positions: <b>{fmt(report.effectivePositions, 2)}</b>
          <div style={{ fontSize: 12, color: '#666', marginTop: 4 }}>1 = одна ставка на рынок</div>
        </div>
        <div style={item}>
          VaR95: <b>{fmtPercent(report.var95Percent)}</b>
          <div style={{ fontSize: 12, color: '#666', marginTop: 4 }}>{fmtMoney(report.var95Rub)} · 1 день</div>
        </div>
        <div style={item}>
          Max pair correlation: <b>{fmt(report.maxPairCorrelation, 2)}</b>
          <div style={{ fontSize: 12, color: '#666', marginTop: 4 }}>по открытым позициям</div>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <ExposureBar label="Gross Exposure" value={gross} limit={grossLimit} />
        <ExposureBar label="Net Exposure" value={Math.abs(net)} limit={netLimit} />
      </div>

      <h3 style={{ marginBottom: 8 }}>Sector Exposure (% AUM)</h3>
      <table border={1} cellPadding={6} style={{ borderCollapse: 'collapse', width: '100%', marginBottom: 16 }}>
        <thead style={{ background: '#f0f0f0' }}>
          <tr><th>Sector</th><th>Positions</th><th>Gross % AUM</th><th>Net % AUM</th></tr>
        </thead>
        <tbody>
          {(report.perSectorExposure || []).map(s => (
            <tr key={s.sector}>
              <td>{s.sector}</td>
              <td>{s.positionCount}</td>
              <td>{fmtPercent(s.grossPercentAum)}</td>
              <td>{fmtPercent(s.netPercentAum)}</td>
            </tr>
          ))}
          {(report.perSectorExposure || []).length === 0 && (
            <tr><td colSpan={4} style={{ textAlign: 'center', color: '#888' }}>No open positions</td></tr>
          )}
        </tbody>
      </table>

      <h3 style={{ marginBottom: 8 }}>Open positions correlation</h3>
      <div style={{ marginBottom: 16 }}>
        <CorrHeatmap matrix={report.correlationMatrix || {}} />
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h3 style={{ margin: 0 }}>Watchlist correlation heatmap</h3>
        <div style={{ display: 'flex', gap: 4 }}>
          {TIMEFRAMES.map(tf => (
            <button
              key={tf}
              onClick={() => setTimeframe(tf)}
              style={{
                padding: '4px 10px',
                cursor: 'pointer',
                border: '1px solid #ccc',
                borderRadius: 4,
                background: timeframe === tf ? '#1a1a2e' : '#fff',
                color: timeframe === tf ? '#fff' : '#1a1a2e',
                fontSize: 12
              }}
            >
              {tf === 'MINUTE_10' ? '10m' : tf === 'HOUR_1' ? '1h' : '1d'}
            </button>
          ))}
        </div>
      </div>
      <div style={{ padding: 12, border: '1px solid #ddd', borderRadius: 8, background: '#fff' }}>
        {matrixError && !matrix ? <div style={{ fontSize: 12, color: '#c62828' }}>{matrixError}</div> : <CorrHeatmap matrix={matrix || {}} />}
      </div>

      <div style={{ marginTop: 8, fontSize: 12, color: '#888' }}>Updated: {report.timestamp}</div>
    </div>
  );
}
