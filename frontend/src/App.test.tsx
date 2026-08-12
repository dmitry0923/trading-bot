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

const EXPOSURE = {
  aum: 500000,
  exposureScore: 35,
  grossExposureRub: 150000,
  grossExposurePercent: 30,
  grossLimitPercent: 150,
  netExposureRub: 150000,
  netExposurePercent: 30,
  netLimitPercent: 100,
  perPositionExposure: [{ ticker: 'SBER', direction: 'LONG', sector: 'FINANCE', notionalRub: 150000, exposurePercentAum: 30 }],
  perSectorExposure: [{ sector: 'FINANCE', positionCount: 1, grossPercentAum: 30, netPercentAum: 30 }],
  correlationMatrix: { SBER: { SBER: 1 } },
  maxPairCorrelation: 1,
  effectivePositions: 1,
  var95Rub: 5000,
  var95Percent: 1,
  timestamp: '2026-01-01T00:00:00Z'
};

const CORR_MATRIX = { SBER: { SBER: 1, GAZP: -0.3 }, GAZP: { SBER: -0.3, GAZP: 1 } };

const BACKTEST_SINGLE = {
  ticker: 'SBER',
  totalReturn: 0.1234,
  sharpeRatio: 1.41,
  maxDrawdown: 0.081,
  winRate: 0.5421,
  profitFactor: 1.87,
  totalTrades: 152,
  passable: true,
  equityCurve: [100000, 100320.5, 101005.7],
  timestamp: '2026-01-01T00:00:00Z'
};

const PANEL = {
  tickers: ['SBER', 'GAZP', 'LKOH'],
  days: 365,
  timeframe: 'MINUTE_10',
  initialCapital: 100000,
  slPercent: 0.02,
  tpPercent: 0.04,
  minBarsForSignal: 30,
  results: [
    { ticker: 'SBER', totalReturn: 0.1234, sharpeRatio: 1.41, sortinoRatio: 1.6, maxDrawdown: 0.081, winRate: 0.5421, profitFactor: 1.87, totalTrades: 152, passable: true },
    { ticker: 'GAZP', totalReturn: 0.05, sharpeRatio: 0.9, sortinoRatio: 1.0, maxDrawdown: 0.12, winRate: 0.48, profitFactor: 1.2, totalTrades: 90, passable: false },
    { ticker: 'LKOH', totalReturn: -0.02, sharpeRatio: 0.3, sortinoRatio: 0.4, maxDrawdown: 0.09, winRate: 0.45, profitFactor: 0.9, totalTrades: 70, passable: false }
  ],
  summary: {
    tickerCount: 3,
    passCount: 1,
    passShare: 0.3333,
    avgTotalReturn: 0.0511,
    medianTotalReturn: 0.05,
    minTotalReturn: -0.02,
    maxTotalReturn: 0.1234,
    totalTrades: 312
  }
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
  if (url.includes('/api/v1/risk/exposure')) return json(EXPOSURE);
  if (url.includes('/api/v1/risk/correlation')) return json(CORR_MATRIX);
  if (url.includes('/api/v1/backtest/panel')) return json(PANEL);
  if (url.includes('/api/v1/backtest/')) return json(BACKTEST_SINGLE);
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
    expect(await screen.findByRole('heading', { name: /Trading Bot Dashboard v2/ })).toBeInTheDocument();
    expect(await screen.findByText('tester · ADMIN')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Positions' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Correlation' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Settings' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Backtest' })).toBeInTheDocument();
  });

  it('navigates to the Positions tab and renders empty table', async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'Positions' }));
    expect(await screen.findByText('No positions')).toBeInTheDocument();
  });

  it('renders Settings editable for ADMIN role', async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'Settings' }));
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
    await user.type(await screen.findByLabelText(/Username/), 'admin');
    await user.type(screen.getByLabelText(/Password/), 'secret');
    await user.click(screen.getByRole('button', { name: /Sign In/ }));
    expect(await screen.findByText('admin · ADMIN')).toBeInTheDocument();
  });

  it('shows error on failed login', async () => {
    userAuthed = false;
    const user = userEvent.setup();
    render(<App />);
    await user.type(await screen.findByLabelText(/Username/), 'admin');
    await user.type(screen.getByLabelText(/Password/), 'wrong');
    await user.click(screen.getByRole('button', { name: /Sign In/ }));
    expect(await screen.findByText('Неверные учётные данные')).toBeInTheDocument();
  });

  it('renders Correlation tab with exposure score and heatmap', async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'Correlation' }));
    expect(await screen.findByText('Exposure Score')).toBeInTheDocument();
    expect(screen.getByText('35')).toBeInTheDocument();
    expect(await screen.findByText('Watchlist correlation heatmap')).toBeInTheDocument();
    expect(screen.getAllByText('-0.30').length).toBe(2);
  });

  it('runs a single backtest and shows metrics with PASS status', async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'Backtest' }));
    await user.click(await screen.findByRole('button', { name: 'Run Backtest' }));
    expect(await screen.findByText('12.34%')).toBeInTheDocument();
    expect(screen.getByText('152')).toBeInTheDocument();
    expect(screen.getByText('PASS')).toBeInTheDocument();
  });

  it('runs a panel backtest and shows distribution table', async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'Backtest' }));
    await user.click(await screen.findByRole('button', { name: 'Run Panel' }));
    expect(await screen.findByText('1/3 (33.33%)')).toBeInTheDocument();
    expect(screen.getByText('GAZP')).toBeInTheDocument();
    expect(screen.getByText('LKOH')).toBeInTheDocument();
    expect(screen.getAllByText('REJECT').length).toBe(2);
  });
});
