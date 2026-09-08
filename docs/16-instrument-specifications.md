# 16. Спецификации инструментов, издержки и funding

> **Статус**: реализовано (InstrumentsConfig + FundingCosts + PnlCalculator.futures +
> BacktestEngine) и протестировано. Значения комиссий/GO/funding — **provisional**, сверяются
> по фактическим выпискам и клиринговым отчётам MOEX ДО LIVE (см. «Подлежит сверке»).

## 16.1. Модель издержек (P1-аудит)

Единый вход издержек для сайзинга, realised P&L (live) и бэктеста — `InstrumentSpec`:

| Поле | Смысл | Единица |
|---|---|---|
| `brokerCommissionRub` | брокерская комиссия | ₽ за 1 лот/контракт за сторону |
| `exchangeFeeRub` | биржевой (клиринговый) сбор | ₽ за 1 лот/контракт за сторону |
| `commissionRub` (легаси) | суммарная комиссия (fallback, если сплит не задан) | ₽ за 1 лот/контракт за сторону |
| `slippageBps` | проскальзывание исполнения | базисных пунктов (1 bp = 1/10000 цены) на сторону |
| `fundingRubPerContractPerDay` | фьючерсный funding | ₽ за 1 контракт за клиринг (18:45 МСК) |

**`totalCommissionPerLotSide()`** = `brokerCommissionRub + exchangeFeeRub`, иначе `commissionRub`, иначе 0.
Round-trip = `totalCommissionPerLotSide × qty × 2`.

### Где участвуют

- `FuturesPositionSizer` / `StockEntryProfile` / BacktestRiskSimulator — вычет round-trip из бюджета риска при сайзинге;
- `PnlCalculator.lotBased` / `PnlCalculator.futures` — realised P&L (live) с вычетом комиссии;
- `BacktestEngine.computeCommission` — комиссия сделок бэктеста;
- `BacktestEngine.executionFill` — проскальзывание: `slippageBps × price` (HALF_UP, 8 знаков), но не
  меньше 1 тика (`priceStep`);

### SL/TP «в пунктах» для фьючерсов

Для фьючерсов SL/TP задаются в недежных ×: `slPoints × priceStep`. Комиссия/сбор к пунктам НЕ
относятся — это отдельные рублёвые величины.

## 16.2. CNYRUBF (валютный фьючерс CNY/RUB, MOEX FORTS)

| Параметр | Значение | Источник |
|---|---|---|
| `lotSize` | 1000 CNY | спецификация контракта |
| `priceStep` | 0.001 ₽ | спецификация контракта |
| `priceStepCost` | 1.0 ₽ | спецификация контракта |
| `pointValue` (priceStepCost / priceStep) | 1000 ₽ | производная |
| notional 1 контракта (цене 12.8) | ~12 800 ₽ | производная = цена × lotSize |
| `go` (конфиг) | 850 ₽ | **provisional**, для SIM/бэктеста; в LIVE берётся `GET /risk` |
| `leverage` | ${leverage.user-leverage} = 2.0 | информационное поле фьючерсной позиции |
| `max-margin-usage-percent` | 60 (demo/live), 90 (backtest) | потолок маржи на аккаунт |
| `max-contracts-per-position` | **1** (live) | жёсткий потолок контрактов на вход |

### Издержки CNYRUBF

| Статья | Значение | Статус |
|---|---|---|
| `brokerCommissionRub` | 1.0 ₽/контракт/сторона | **provisional** — сверяется по тарифу Alor |
| `exchangeFeeRub` | 0.5 ₽/контракт/сторона | **provisional** — сверяется по выпискам MOEX |
| `totalCommissionPerLotSide` | 1.5 ₽/контракт/сторона | производная |
| `slippageBps` | 1.0 bp (0.01%) на сторону | **provisional** |
| `fundingRubPerContractPerDay` | 0.5 ₽/контракт/клиринг | **provisional** — сверяется по MOEX (лот 1000 CNY) |

### Funding (CNYRUBF)

- Носитель: 1 контракт за каждый пережитый клиринг (18:45 МСК, торговый день; внутридневная позиция — 0).
- Моменты: `FundingCosts.clearingsCrossed(openedAt, closedAt)` — дни, где `openedAt < clearing < closedAt`,
  с учётом выходных (без клирингов) и московского времени.
- Встраивание: `PnlCalculator.futures` (live) и `BacktestEngine.closePosition` (бэктест) вычитают
  `fundingPerClearing × qty × clearings`. Значение отключено (`null`), если funding не задан.

### Отличие от Si/акций

- `Si` / `RI` остаются в конфиге как тестовая фикстура (commissionRub легаси; funding — нет).
- Акции — `STOCK`: издержки = `totalCommissionPerLotSide` (сплит не задан → fallback `commissionRub`);
  funding не применяется; проскальзывание — спред из свечи (`bt.realistic-execution`) или 0.1%.
- `CNYRUB_TOM` (кассовый юань) — легаси фикстура тикера акций/валюты, в бэктест-калибровке не участвует.

## 16.3. Источники истины

- Код: `src/main/kotlin/com/trading/bot/config/InstrumentsConfig.kt`
  (defaults для тестов + `validateSpecs()`), `application.yml` → `instruments.instruments` (live-значения).
- P&L/funding: `PnlCalculator.futures` (OrderExecutionEngine), `FundingCosts`, `BacktestEngine.closePosition`.
- Маржинальный гейт: `RiskManagementService.freshMarginOfPositions` — фактическое ГО открытых позиций
  (marginUsed → живой GET_GO с TTL 30с; недоступность → fail-closed `PORTFOLIO_MARGIN_DATA_UNAVAILABLE`).

## 16.4. Подлежит сверке перед LIVE

1. Фактическая комиссия Alor по счёту (брокерская) и клиринговая по выпискам MOEX (биржа) для CNYRUBF;
2. Величина funding (RUB/контракт/день) по данным MOEX и фактическим выплатам;
3. Фактическое начальное и уровни риска ГО (влияют на `freshMarginOfPositions` и сайзинг);
4. Реалистичное проскальзывание CNYRUBF на минутных барах (для калибровки `slippageBps`).