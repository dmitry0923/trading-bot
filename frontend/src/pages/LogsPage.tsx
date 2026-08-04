import React from "react";
import { useFetch } from "../api";
import type { AgentLog } from "../types";

const AGENT_OPTIONS = [
  "",
  "Agent-1-Technical",
  "Agent-2-Fundamental",
  "Agent-3-Strategist",
  "Agent-4-Contrarian",
  "Agent-5-Arbitrator",
  "Agent-6-Performance",
];

export default function LogsPage() {
  const [ticker, setTicker] = React.useState("");
  const [agent, setAgent] = React.useState("");
  const [limit, setLimit] = React.useState(100);

  const params = new URLSearchParams();
  if (ticker) params.set("ticker", ticker);
  if (agent) params.set("agent", agent);
  params.set("limit", String(limit));

  const { data: logs, error } = useFetch<AgentLog[]>(
    `/api/v1/logs?${params.toString()}`,
    5000,
  );

  return (
    <div>
      <div
        style={{
          marginBottom: 12,
          display: "flex",
          gap: 8,
          alignItems: "center",
          flexWrap: "wrap",
        }}
      >
        <input
          placeholder="Ticker"
          value={ticker}
          onChange={(e) => setTicker(e.target.value)}
          style={{ padding: 6 }}
        />
        <select
          value={agent}
          onChange={(e) => setAgent(e.target.value)}
          style={{ padding: 6 }}
        >
          {AGENT_OPTIONS.map((a) => (
            <option key={a} value={a}>
              {a || "All agents"}
            </option>
          ))}
        </select>
        <input
          type="number"
          min={1}
          max={500}
          value={limit}
          onChange={(e) => setLimit(Number(e.target.value))}
          style={{ padding: 6, width: 80 }}
        />
      </div>
      {error && <div style={{ color: "#c62828" }}>Error: {error}</div>}
      <table
        border={1}
        cellPadding={6}
        style={{ borderCollapse: "collapse", width: "100%" }}
      >
        <thead style={{ background: "#f0f0f0" }}>
          <tr>
            <th>Time</th>
            <th>Agent</th>
            <th>Ticker</th>
            <th>Action</th>
            <th>Conf</th>
            <th>Latency</th>
            <th>Tokens</th>
            <th>Cached</th>
            <th>Reasoning</th>
          </tr>
        </thead>
        <tbody>
          {(logs || []).map((l) => (
            <tr key={l.id}>
              <td style={{ fontSize: 12 }}>{l.createdAt}</td>
              <td>{l.agentName}</td>
              <td>{l.ticker}</td>
              <td>{l.action}</td>
              <td>{l.confidence != null ? l.confidence.toFixed(2) : "-"}</td>
              <td>{l.latencyMs != null ? `${l.latencyMs}ms` : "-"}</td>
              <td>{l.tokensUsed || "-"}</td>
              <td>{l.isCached ? "yes" : "no"}</td>
              <td style={{ maxWidth: 400, fontSize: 12 }}>{l.reasoning}</td>
            </tr>
          ))}
          {(!logs || logs.length === 0) && (
            <tr>
              <td colSpan={9} style={{ textAlign: "center", color: "#888" }}>
                No logs
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
