import React from "react";
import { useFetch } from "../api";
import type {
  BlindSpot,
  TradeStats,
  TimePattern,
  AdaptiveParams,
  HealthData,
} from "../types";

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div
      style={{
        marginBottom: 20,
        padding: 12,
        border: "1px solid #ddd",
        borderRadius: 8,
      }}
    >
      <h3 style={{ marginTop: 0 }}>{title}</h3>
      {children}
    </div>
  );
}

export default function AnalyticsPage() {
  const [ticker, setTicker] = React.useState("SBER");
  const { data: health, error: healthError } = useFetch<HealthData>(
    "/api/v1/analytics/health",
    30000,
  );
  const { data: tradeStats } = useFetch<Record<string, TradeStats>>(
    "/api/v1/analytics/trade-stats?days=14",
    30000,
  );
  const { data: blindSpots } = useFetch<BlindSpot[]>(
    "/api/v1/analytics/blind-spots",
    30000,
  );
  const encodedTicker = encodeURIComponent(ticker.trim());
  const { data: timePattern } = useFetch<TimePattern>(
    encodedTicker
      ? `/api/v1/analytics/time-pattern/${encodedTicker}?days=30`
      : "",
    30000,
  );
  const { data: adaptive } = useFetch<AdaptiveParams>(
    encodedTicker ? `/api/v1/analytics/adaptive-params/${encodedTicker}` : "",
    30000,
  );

  return (
    <div>
      <Section title="Health">
        {healthError && (
          <div style={{ color: "#c62828" }}>Error: {healthError}</div>
        )}
        {health && (
          <pre style={{ background: "#f5f5f5", padding: 12 }}>
            {JSON.stringify(health, null, 2)}
          </pre>
        )}
      </Section>

      <Section title="Trade Stats (last 14 days)">
        <table
          border={1}
          cellPadding={6}
          style={{ borderCollapse: "collapse", width: "100%" }}
        >
          <thead style={{ background: "#f0f0f0" }}>
            <tr>
              <th>Ticker</th>
              <th>Trades</th>
              <th>WinRate</th>
              <th>PF</th>
              <th>Max Losses</th>
              <th>SL%</th>
              <th>TP%</th>
              <th>Best Hour</th>
              <th>Worst Hour</th>
            </tr>
          </thead>
          <tbody>
            {Object.entries(tradeStats || {}).map(([t, s]) => (
              <tr key={t}>
                <td>{t}</td>
                <td>{s.totalTrades}</td>
                <td>{(s.winRate * 100).toFixed(1)}%</td>
                <td>{s.profitFactor.toFixed(2)}</td>
                <td>{s.maxConsecutiveLosses}</td>
                <td>{(s.slHitRate * 100).toFixed(1)}%</td>
                <td>{(s.tpHitRate * 100).toFixed(1)}%</td>
                <td>{s.bestEntryHour ?? "-"}</td>
                <td>{s.worstEntryHour ?? "-"}</td>
              </tr>
            ))}
            {(!tradeStats || Object.keys(tradeStats).length === 0) && (
              <tr>
                <td colSpan={9} style={{ textAlign: "center", color: "#888" }}>
                  No trade stats yet
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </Section>

      <Section title="Blind Spots">
        {(blindSpots || []).map((b, i) => (
          <div
            key={b.id || i}
            style={{ padding: 6, borderBottom: "1px solid #eee" }}
          >
            <b>{b.conditionPattern}</b>{" "}
            <span style={{ color: "#666" }}>{b.recommendation}</span>
          </div>
        ))}
        {(!blindSpots || blindSpots.length === 0) && (
          <div style={{ color: "#888" }}>No active blind spots</div>
        )}
      </Section>

      <Section title={`Time Pattern (${ticker})`}>
        <div style={{ marginBottom: 8 }}>
          <input
            value={ticker}
            onChange={(e) => setTicker(e.target.value)}
            style={{ padding: 6 }}
          />
        </div>
        {timePattern && (
          <pre style={{ background: "#f5f5f5", padding: 12 }}>
            {JSON.stringify(timePattern, null, 2)}
          </pre>
        )}
      </Section>

      <Section title={`Adaptive Params (${ticker})`}>
        {adaptive && (
          <pre style={{ background: "#f5f5f5", padding: 12 }}>
            {JSON.stringify(adaptive, null, 2)}
          </pre>
        )}
      </Section>
    </div>
  );
}
