import React from "react";
import {
  AUTH_REQUIRED_EVENT,
  clearCredentials,
  get,
  hasCredentials,
  setCredentials,
} from "./api";
import DashboardPage from "./pages/DashboardPage";
import PositionsPage from "./pages/PositionsPage";
import StrategiesPage from "./pages/StrategiesPage";
import LogsPage from "./pages/LogsPage";
import AnalyticsPage from "./pages/AnalyticsPage";
import SettingsPage from "./pages/SettingsPage";
import BacktestPage from "./pages/BacktestPage";

const TABS = [
  { key: "dashboard", label: "Dashboard" },
  { key: "positions", label: "Positions" },
  { key: "strategies", label: "Strategies" },
  { key: "logs", label: "Agents Log" },
  { key: "analytics", label: "Analytics" },
  { key: "settings", label: "Settings" },
  { key: "backtest", label: "Backtest" },
] as const;

type TabKey = (typeof TABS)[number]["key"];

function Login({ onSuccess }: { onSuccess: () => void }) {
  const [username, setUsername] = React.useState("admin");
  const [password, setPassword] = React.useState("");
  const [error, setError] = React.useState("");
  const [loading, setLoading] = React.useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setLoading(true);
    setError("");
    setCredentials(username.trim(), password);
    try {
      await get("/api/v1/settings");
      onSuccess();
    } catch (reason) {
      clearCredentials();
      setError(
        reason instanceof Error ? reason.message : "Authentication failed",
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <main
      style={{
        fontFamily: "Segoe UI, Arial, sans-serif",
        maxWidth: 380,
        margin: "12vh auto",
        padding: 24,
      }}
    >
      <h1>Trading Bot</h1>
      <p style={{ color: "#666" }}>Введите Basic Auth учётные данные API.</p>
      <form
        onSubmit={submit}
        style={{ display: "flex", flexDirection: "column", gap: 12 }}
      >
        <label>
          Логин
          <input
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            required
            style={{
              boxSizing: "border-box",
              display: "block",
              width: "100%",
              padding: 8,
              marginTop: 4,
            }}
          />
        </label>
        <label>
          Пароль
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
            autoFocus
            style={{
              boxSizing: "border-box",
              display: "block",
              width: "100%",
              padding: 8,
              marginTop: 4,
            }}
          />
        </label>
        <button
          type="submit"
          disabled={loading}
          style={{ padding: 10, cursor: "pointer" }}
        >
          {loading ? "Проверка…" : "Войти"}
        </button>
        {error && (
          <div role="alert" style={{ color: "#c62828" }}>
            {error}
          </div>
        )}
      </form>
    </main>
  );
}

function App() {
  const [tab, setTab] = React.useState<TabKey>("dashboard");
  const [authenticated, setAuthenticated] = React.useState(hasCredentials);

  React.useEffect(() => {
    const requireAuth = () => setAuthenticated(false);
    window.addEventListener(AUTH_REQUIRED_EVENT, requireAuth);
    return () => window.removeEventListener(AUTH_REQUIRED_EVENT, requireAuth);
  }, []);

  if (!authenticated) {
    return <Login onSuccess={() => setAuthenticated(true)} />;
  }

  const logout = () => {
    clearCredentials();
    setAuthenticated(false);
  };

  return (
    <div
      style={{
        fontFamily: "Segoe UI, Arial, sans-serif",
        padding: 20,
        maxWidth: 1200,
        margin: "0 auto",
      }}
    >
      <header
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          gap: 16,
        }}
      >
        <h1 style={{ color: "#1a1a2e" }}>Trading Bot Dashboard v2</h1>
        <button
          onClick={logout}
          style={{ padding: "7px 12px", cursor: "pointer" }}
        >
          Выйти
        </button>
      </header>
      <nav
        style={{ display: "flex", gap: 8, marginBottom: 20, flexWrap: "wrap" }}
      >
        {TABS.map((item) => (
          <button
            key={item.key}
            onClick={() => setTab(item.key)}
            style={{
              padding: "8px 16px",
              cursor: "pointer",
              border: "1px solid #ccc",
              borderRadius: 4,
              background: tab === item.key ? "#1a1a2e" : "#fff",
              color: tab === item.key ? "#fff" : "#1a1a2e",
              fontWeight: tab === item.key ? 600 : 400,
            }}
          >
            {item.label}
          </button>
        ))}
      </nav>
      {tab === "dashboard" && <DashboardPage />}
      {tab === "positions" && <PositionsPage />}
      {tab === "strategies" && <StrategiesPage />}
      {tab === "logs" && <LogsPage />}
      {tab === "analytics" && <AnalyticsPage />}
      {tab === "settings" && <SettingsPage />}
      {tab === "backtest" && <BacktestPage />}
    </div>
  );
}

export default App;
