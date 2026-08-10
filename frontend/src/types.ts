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
  description?: string;
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

export interface DrawdownStatus {
  aum: number | string;
  dailyPnlRub: number | string;
  dailyLimitRub: number | string;
  dailyLimitBreached: boolean;
  rolling7dPnlRub: number | string;
  rolling7dLimitRub: number | string;
  rolling7dBreached: boolean;
  rolling30dPnlRub: number | string;
  rolling30dLimitRub: number | string;
  rolling30dBreached: boolean;
  consecutiveLosses: number;
  maxConsecutiveLosses: number;
  shadowModeActive: boolean;
  shadowModeUntil?: string | null;
  reasons: string[];
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
  tradingMode: string;
  maxOpenPositions: number;
  futuresMaxOpenPositions: number;
  maxSectorExposure: number;
  maxVolatilityPercent: number;
  defaultStopLossPercent: number;
  defaultTakeProfitPercent: number;
  trailingStopEnabled: boolean;
  trailingStopPercent: number;
  riskPerTradePercent: number;
  tradingHoursStart: string;
  tradingHoursEnd: string;
  botIntervalMs: number;
  strategyIntervalMs: number;
  kellyFraction: number;
  timeframes: string[];
  llmProvider: string;
  llmModel: string;
  llmBaseUrl: string;
  llmApiKey: string;
  forceCloseEnabled: boolean;
  forceCloseTime: string;
  investorManagementEnabled: boolean;
  leverageEnabled: boolean;
  userLeverage: number;
  minLeverage: number;
  maxLeverage: number;
  maxDailyLossPercent: number;
  maxRollingLossPercent7d: number;
  maxRollingLossPercent30d: number;
  maxConsecutiveLosses: number;
  shadowModeEnabled: boolean;
  shadowModeCooldownHours: number;
  volatilityIndexEnabled: boolean;
  maxVolatilityIndexPercent: number;
}

export interface Investor {
  id: string;
  name: string;
  email?: string | null;
  status: string;
  createdAt?: string;
}

export interface InvestorAccount {
  id: string;
  investorId: string;
  currency: string;
  balance: number | string;
  totalDeposited: number | string;
  totalWithdrawn: number | string;
  createdAt?: string;
  updatedAt?: string;
}

export interface InvestorView {
  investor: Investor;
  account: InvestorAccount;
  realizedPnL: number | string;
  totalReturnPercent: number;
}

export interface InvestorTransaction {
  id: string;
  investorId: string;
  accountId: string;
  type: string;
  amount: number | string;
  currency: string;
  description?: string | null;
  createdAt?: string;
}

export interface InvestorAllocation {
  id: string;
  investorId: string;
  accountId: string;
  amount: number | string;
  allocatedAt?: string;
}

export interface ProfitForecast {
  asOf?: string;
  horizonDays: number;
  expectedReturnPercent: number;
  expectedReturnAnnualPercent: number;
  confidenceLowPercent: number;
  confidenceHighPercent: number;
  dailyMeanReturnPercent: number;
  dailyVolatilityPercent: number;
  tradesAnalyzed: number;
  note?: string;
}

export interface ClearingQuote {
  investorId: string;
  investorName: string;
  requestedDate?: string;
  sharesAtTime: number | string;
  poolEquity: number | string;
  poolContributed: number | string;
  poolRealizedPnL: number | string;
  attributedPnL: number | string;
  forecastComponent: number | string;
  estimatedWithdrawalAmount: number | string;
  breakdown?: Record<string, string>;
}

export interface TradingBlock {
  reason: string;
  source: string;
  detail: string;
  timestamp: string;
  ticker?: string | null;
}

export interface TradingStatus {
  tradingEnabled: boolean;
  reason?: string | null;
  source?: string | null;
  detail?: string | null;
  blockedAt?: string | null;
  blocks?: TradingBlock[];
  tradingMode: string;
  forceCloseEnabled: boolean;
  forceCloseTime: string;
  openPositions: number;
}

export interface PoolStats {
  poolContributed: number | string;
  poolRealizedPnL: number | string;
  poolEquity: number | string;
  openPositions: number;
}

export interface LlmProviders {
  providers: string[];
  active: string;
  model: string;
  default: string;
}

export interface CurrentUser {
  username: string;
  roles: string[];
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

export interface PositionExposure {
  ticker: string;
  direction: string;
  sector: string;
  notionalRub: number | string;
  exposurePercentAum: number | string;
}

export interface SectorExposure {
  sector: string;
  positionCount: number;
  grossPercentAum: number | string;
  netPercentAum: number | string;
}

export type CorrelationMatrix = Record<string, Record<string, number | null>>;

export interface RiskExposureReport {
  aum: number | string;
  exposureScore: number;
  grossExposureRub: number | string;
  grossExposurePercent: number | string;
  grossLimitPercent: number | string;
  netExposureRub: number | string;
  netExposurePercent: number | string;
  netLimitPercent: number | string;
  perPositionExposure: PositionExposure[];
  perSectorExposure: SectorExposure[];
  correlationMatrix: CorrelationMatrix;
  maxPairCorrelation: number;
  effectivePositions: number | string;
  var95Rub: number | string;
  var95Percent: number | string;
  timestamp?: string;
}
