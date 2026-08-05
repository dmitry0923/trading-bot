import React from 'react';
import { useFetch, get, post } from '../api';
import type { InvestorView, InvestorTransaction, ClearingQuote, PoolStats } from '../types';

export default function InvestorsPage() {
  const { data: investors, error, reload } = useFetch<InvestorView[]>('/api/v1/investors');
  const [selected, setSelected] = React.useState<string>('');
  const [transactions, setTransactions] = React.useState<InvestorTransaction[]>([]);
  const [pool, setPool] = React.useState<PoolStats | null>(null);
  const [quote, setQuote] = React.useState<ClearingQuote | null>(null);
  const [msg, setMsg] = React.useState('');
  const [newName, setNewName] = React.useState('');
  const [newDeposit, setNewDeposit] = React.useState('');
  const [amount, setAmount] = React.useState('');
  const [exitDate, setExitDate] = React.useState('');

  React.useEffect(() => {
    get<PoolStats>('/api/v1/clearing/pool').then(setPool).catch(() => {});
  }, []);

  React.useEffect(() => {
    if (selected) {
      get<InvestorTransaction[]>('/api/v1/investors/' + selected + '/transactions').then(setTransactions).catch(() => setTransactions([]));
    }
  }, [selected]);

  if (error) return <div>Error: {error}</div>;
  if (!investors) return <div>Loading investors...</div>;

  const act = async (fn: () => Promise<unknown>, okMsg: string) => {
    try {
      await fn();
      setMsg(okMsg);
      reload();
    } catch (e) {
      setMsg('Error: ' + (e as Error).message);
    }
  };

  const fmt = (v: unknown) => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(Number(v));

  return (
    <div>
      <h2>Investors</h2>
      {pool && (
        <div style={{ display: 'flex', gap: 24, marginBottom: 16, color: '#333' }}>
          <div>Pool contributed: <b>{fmt(pool.poolContributed)} ₽</b></div>
          <div>Realized P&L: <b>{fmt(pool.poolRealizedPnL)} ₽</b></div>
          <div>Pool equity: <b>{fmt(pool.poolEquity)} ₽</b></div>
          <div>Open positions: <b>{pool.openPositions}</b></div>
        </div>
      )}

      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <input placeholder="Name" value={newName} onChange={e => setNewName(e.target.value)} />
        <input placeholder="Initial deposit" type="number" value={newDeposit} onChange={e => setNewDeposit(e.target.value)} />
        <button onClick={() => act(() => post('/api/v1/investors', { name: newName, initialDeposit: newDeposit }), 'Investor created')}>Add Investor</button>
      </div>

      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead>
          <tr style={{ textAlign: 'left' }}>
            <th>Name</th>
            <th>Balance</th>
            <th>Total deposited</th>
            <th>Total withdrawn</th>
            <th>Realized P&L</th>
            <th>Return %</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {investors.map(v => (
            <tr key={v.investor.id} style={{ borderTop: '1px solid #ddd' }}>
              <td>{v.investor.name}</td>
              <td>{fmt(v.account.balance)}</td>
              <td>{fmt(v.account.totalDeposited)}</td>
              <td>{fmt(v.account.totalWithdrawn)}</td>
              <td>{fmt(v.realizedPnL)}</td>
              <td>{v.totalReturnPercent.toFixed(2)}</td>
              <td>
                <button onClick={() => setSelected(v.investor.id)}>Details</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {selected && (
        <div style={{ marginTop: 20, border: '1px solid #ddd', padding: 16, borderRadius: 6 }}>
          <h3 style={{ marginTop: 0 }}>
            Investor {investors.find(v => v.investor.id === selected)?.investor.name || selected}
          </h3>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <input placeholder="Amount" type="number" value={amount} onChange={e => setAmount(e.target.value)} />
            <button onClick={() => act(() => post('/api/v1/investors/' + selected + '/deposit', { amount }), 'Deposit OK')}>Deposit</button>
            <button onClick={() => act(() => post('/api/v1/investors/' + selected + '/withdraw', { amount, description: 'Manual withdrawal' }), 'Withdrawal OK')}>Withdraw</button>
          </div>

          <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <input type="date" value={exitDate} onChange={e => setExitDate(e.target.value)} />
            <button
              onClick={() =>
                act(async () => {
                  const q = await get<ClearingQuote>('/api/v1/clearing/quote?investorId=' + selected + (exitDate ? '&date=' + encodeURIComponent(exitDate + 'T18:00:00') : ''));
                  setQuote(q);
                }, 'Quote loaded')
              }
            >
              Quote
            </button>
            <button
              onClick={() =>
                act(async () => {
                  const q = await post<ClearingQuote>('/api/v1/clearing/settle?investorId=' + selected + (exitDate ? '&date=' + encodeURIComponent(exitDate + 'T18:00:00') : ''));
                  setQuote(q);
                }, 'Clearing settled')
              }
            >
              Settle Clearing
            </button>
          </div>

          {quote && (
            <div style={{ marginTop: 12, background: '#f5f5f5', padding: 12, borderRadius: 6 }}>
              <div>Share: <b>{quote.sharesAtTime}</b></div>
              <div>Pool equity: <b>{fmt(quote.poolEquity)} ₽</b></div>
              <div>Attributed P&L: <b>{fmt(quote.attributedPnL)} ₽</b></div>
              <div>Forecast component: <b>{fmt(quote.forecastComponent)} ₽</b></div>
              <div style={{ marginTop: 6 }}>Estimated withdrawal: <b>{fmt(quote.estimatedWithdrawalAmount)} ₽</b></div>
            </div>
          )}

          <h4 style={{ marginBottom: 4 }}>Transactions</h4>
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            {transactions.map(t => (
              <li key={t.id}>
                {t.type} {fmt(t.amount)} ₽ {t.createdAt ? '· ' + new Date(t.createdAt).toLocaleString() : ''}{t.description ? ' · ' + t.description : ''}
              </li>
            ))}
          </ul>
        </div>
      )}

      {msg && <div style={{ marginTop: 12, color: '#2e7d32' }}>{msg}</div>}
    </div>
  );
}
