import React from 'react';

interface Props {
  onSignIn: (username: string, password: string) => Promise<void>;
}

export default function LoginPage({ onSignIn }: Props) {
  const [username, setUsername] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onSignIn(username, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка входа');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      style={{
        maxWidth: 380,
        margin: '100px auto',
        padding: 24,
        fontFamily: 'Segoe UI, Arial, sans-serif',
        border: '1px solid #ddd',
        borderRadius: 8,
        boxShadow: '0 2px 8px rgba(0,0,0,0.08)'
      }}
    >
      <h1 style={{ color: '#1a1a2e', marginTop: 0 }}>Trading Bot Dashboard v2</h1>
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <label>
          <div style={{ fontSize: 13, color: '#555', marginBottom: 4 }}>Username</div>
          <input
            value={username}
            onChange={e => setUsername(e.target.value)}
            placeholder="admin"
            autoComplete="username"
            autoFocus
            style={{ padding: '8px 10px', width: '100%', boxSizing: 'border-box' }}
          />
        </label>
        <label>
          <div style={{ fontSize: 13, color: '#555', marginBottom: 4 }}>Password</div>
          <input
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            placeholder="••••••••"
            autoComplete="current-password"
            style={{ padding: '8px 10px', width: '100%', boxSizing: 'border-box' }}
          />
        </label>
        <button
          type="submit"
          disabled={busy}
          style={{
            padding: '10px 16px',
            cursor: 'pointer',
            border: 'none',
            borderRadius: 4,
            background: '#1a1a2e',
            color: '#fff',
            fontWeight: 600
          }}
        >
          {busy ? 'Signing in…' : 'Sign In'}
        </button>
        {error && <div style={{ color: '#c00', fontSize: 13 }}>{error}</div>}
      </form>
    </div>
  );
}
