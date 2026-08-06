import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';

const json = (body: unknown, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body
});

const SETTINGS = {
  tradingEnabled: true,
  riskEnabled: true,
  tradingMode: 'SIMULATION',
  maxPositionRub: 100000,
  maxDailyLossRub: 20000,
  maxOpenPositions: 5,
  futuresMaxOpenPositions: 3,
  maxSectorExposure: 30,
  maxVolatilityPercent: 25,
  defaultStopLossPercent: 2,
  defaultTakeProfitPercent: 4,
  trailingStopEnabled: true,
  trailingStopPercent: 1,
  riskPerTradePercent: 1,
  kellyFraction: 0.25,
  tradingHoursStart: '10:00',
  tradingHoursEnd: '18:00',
  leverageEnabled: true,
  userLeverage: 3,
  minLeverage: 1,
  maxLeverage: 10,
  botIntervalMs: 5000,
  strategyIntervalMs: 5000,
  llmProvider: 'ROUTER_AI',
  llmModel: '',
  llmBaseUrl: '',
  llmApiKey: '',
  timeframes: ['MINUTE_10', 'HOUR_1'],
  forceCloseEnabled: true,
  forceCloseTime: '23:50',
  maxDailyLossPercent: 10,
  maxRollingLossPercent7d: 15,
  maxRollingLossPercent30d: 25,
  maxConsecutiveLosses: 3,
  shadowModeEnabled: true,
  shadowModeCooldownHours: 24,
  volatilityIndexEnabled: true,
  maxVolatilityIndexPercent: 50
};

const PROVIDERS = { providers: ['ROUTER_AI'], active: 'ROUTER_AI', model: '', default: 'ROUTER_AI' };
const STATUS = { tradingEnabled: true, tradingMode: 'SIMULATION', forceCloseEnabled: true, forceCloseTime: '23:50', openPositions: 2 };
const DRAWDOWN = {
  aum: 50000,
  dailyPnlRub: -1200,
  dailyLimitRub: 5000,
  dailyLimitBreached: false,
  rolling7dPnlRub: -3000,
  rolling7dLimitRub: 7500,
  rolling7dBreached: false,
  rolling30dPnlRub: -4000,
  rolling30dLimitRub: 12500,
  rolling30dBreached: false,
  consecutiveLosses: 1,
  maxConsecutiveLosses: 3,
  shadowModeActive: false,
  shadowModeUntil: null,
  reasons: [],
  timestamp: '2026-01-01T00:00:00Z'
};

let currentUser = { username: 'tester', roles: ['ROLE_ADMIN'] };
let userAuthed = true;

const mockFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input);
  if (url.includes('/api/v1/me')) return userAuthed ? json(currentUser) : json({}, 401);
  if (url.includes('/api/v1/auth/login')) {
    const body = JSON.parse(String(init?.body));
    if (body.username === 'admin' && body.password === 'secret') {
      return json({ accessToken: 'jwt-access', refreshToken: 'refresh', expiresIn: 900, username: 'admin', roles: ['ROLE_ADMIN'] });
    }
    return json({}, 401);
  }
  if (url.includes('/api/v1/auth/refresh')) return json({}, 401);
  if (url.includes('/api/v1/positions')) return json([]);
  if (url.includes('/api/v1/settings')) return json(SETTINGS);
  if (url.includes('/api/v1/llm/providers')) return json(PROVIDERS);
  if (url.includes('/api/v1/trading/status')) return json(STATUS);
  if (url.includes('/api/v1/risk/drawdown')) return json(DRAWDOWN);
  return json({});
});

beforeEach(() => {
  currentUser = { username: 'tester', roles: ['ROLE_ADMIN'] };
  userAuthed = true;
  vi.stubGlobal('fetch', mockFetch);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('App', () => {
  it('renders title, user info and all tab buttons', async () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: /Trading Bot Dashboard v2/ })).toBeInTheDocument();
    expect(await screen.findByText('tester · ADMIN')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Positions' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Settings' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Backtest' })).toBeInTheDocument();
  });

  it('navigates to the Positions tab and renders empty table', async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole('button', { name: 'Positions' }));
    expect(await screen.findByText('No positions')).toBeInTheDocument();
  });

  it('renders Settings editable for ADMIN role', async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole('button', { name: 'Settings' }));
    expect(await screen.findByRole('button', { name: 'Save Settings' })).toBeEnabled();
    expect(screen.queryByText(/У вас роль ANALYTICS/)).not.toBeInTheDocument();
  });

  it('renders Settings read-only for ANALYTICS role', async () => {
    currentUser = { username: 'analyst', roles: ['ROLE_ANALYTICS'] };
    const user = userEvent.setup();
    render(<App />);
    expect(await screen.findByText('analyst · ANALYTICS')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Settings' }));
    expect(await screen.findByText(/У вас роль ANALYTICS/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save Settings' })).toBeDisabled();
  });

  it('shows login page when unauthenticated and logs in with valid credentials', async () => {
    userAuthed = false;
    const user = userEvent.setup();
    render(<App />);
    expect(await screen.findByRole('button', { name: /Sign In/ })).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Username/), 'admin');
    await user.type(screen.getByLabelText(/Password/), 'secret');
    await user.click(screen.getByRole('button', { name: /Sign In/ }));
    expect(await screen.findByText('admin · ADMIN')).toBeInTheDocument();
  });

  it('shows error on failed login', async () => {
    userAuthed = false;
    const user = userEvent.setup();
    render(<App />);
    await user.type(screen.getByLabelText(/Username/), 'admin');
    await user.type(screen.getByLabelText(/Password/), 'wrong');
    await user.click(screen.getByRole('button', { name: /Sign In/ }));
    expect(await screen.findByText('Неверные учётные данные')).toBeInTheDocument();
  });
});
