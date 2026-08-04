export interface Position {
  id?: number;
  ticker: string;
  direction: string;
  quantity: number;
  entryPrice: number | string;
  currentPrice?: number | string;
  closePrice?: number | string | null;
  stopLoss?: number | string | null;
  takeProfit?: number | string | null;
  pnl?: number | string | null;
  status: string;
  closeReason?: string | null;
  openedAt?: string;
  closedAt?: string | null;
}

export interface Strategy {
  id?: number;
  ticker: string;
  action: string;
  targetPrice: number | string;
  quantity: number;
  stopLoss?: number | string | null;
  takeProfit?: number | string | null;
  confidence: number;
  trailingStop: boolean;
  createdAt?: string;
  reasoning?: string;
}

export interface AgentLog {
  id?: number;
  createdAt?: string;
  agentName: string;
  ticker?: string;
  action?: string;
  confidence?: number | null;
  latencyMs?: number | null;
  tokensUsed?: number | null;
  isCached?: boolean;
  reasoning?: string;
}

export interface TradeStats {
  ticker: string;
  totalTrades: number;
  winningTrades: number;
  losingTrades: number;
  winRate: number;
  avgWin: number | string;
  avgLoss: number | string;
  profitFactor: number;
  maxConsecutiveLosses: number;
  avgHoldTimeMinutes: number;
  slHitRate: number;
  tpHitRate: number;
  strategyCloseRate: number;
  bestEntryHour?: number | null;
  worstEntryHour?: number | null;
  blindSpots: unknown[];
}

export interface BlindSpot {
  id?: number;
  conditionPattern: string;
  recommendation: string;
  lossRate: number;
  occurrenceCount: number;
}

export interface DashboardData {
  tradingMode: string;
  dailyPnl: number | string;
  openPnl: number | string;
  realizedPnlToday: number | string;
  closedTodayCount: number;
  strategiesToday: number;
  openPositionsCount: number;
  openPositions?: Position[];
  pausedTickers?: string[];
  timestamp?: string;
}

export interface BacktestResult {
  ticker: string;
  totalReturn: number;
  sharpeRatio: number;
  maxDrawdown: number;
  winRate: number;
  profitFactor: number;
  totalTrades: number;
  passable: boolean;
  equityCurve: number[];
  timestamp: string;
}

export interface BotSettings {
  tradingEnabled: boolean;
  riskEnabled: boolean;
  maxPositionRub: number;
  maxDailyLossRub: number;
}

export interface TimePattern {
  ticker: string;
  hourlyWinRates: Record<string, number>;
}

export interface AdaptiveParams {
  ticker: string;
  confidenceThreshold: number;
  maxPositionRub: number;
  isInRecovery: boolean;
  shouldPause: boolean;
}

export interface HealthData {
  totalTickersAnalyzed: number;
  totalTradesLast7Days: number;
  averageWinRate: string;
  pausedTickers: string[];
  timestamp: string;
}
