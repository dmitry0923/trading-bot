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
| `go` (конфиг) | 850 ₽ | **SIM/fallback только**; в LIVE ГО — side-specific `Initial Margin Long/Short` с `GET /risk` (см. 16.3) |
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
| `fundingRubPerContractPerDay` | 0.5 ₽/контракт/клиринг | **provisional fallback (SIM/бэктест)**; в LIVE — динамическая величина MOEX через `FundingSnapshotService` (`funding.moex-url`; см. 16.3) |

### Funding (CNYRUBF)

- Носитель: 1 контракт за каждый пережитый клиринг (18:45 МСК, торговый день; внутридневная позиция — 0).
- Моменты: `FundingCosts.clearingsCrossed(openedAt, closedAt)` — дни, где `openedAt < clearing < closedAt`,
  с учётом выходных (без клирингов) и московского времени.
- Встраивание: `PnlCalculator.futures` (live) и `BacktestEngine.closePosition` (бэктест) вычитают
  `fundingPerClearing × qty × clearings`. Значение отключено (`null`), если funding не задан.
- **LIVE-источник (P0)**: перед входом `FuturesEntryProfile` вызывает `FundingSnapshotService.refresh(ticker)`
  — MOEX-снапшот (`funding.moex-url`, столбец `funding.moex-column`, конвертация `raw × funding.moex-lot-multiplier`
  = лот 1000 CNY → RUB/контракт/клиринг) с TTL `funding.moex-ttl-ms`. Поле funding — **`SWAPRATE`**
  (MOEX ISS, «Фандинг, руб.», RUB за 1 единицу базового актива; подтверждено по реальным данным
  CNYRUBF 2026-09-08..10: 0.00278 / 0.00256 — `LATESTFUNDING` в MOEX ISS НЕ существует, верифицировано 2026-09-10).
  Недоступность MOEX → **явный** provisional CONFIG-fallback с метрикой `funding.live.provider_unavailable`;
  устаревший MOEX-снапшот (> TTL) → fallback с метрикой `funding.live.snapshot_stale_config_fallback`.
  Множитель: лот 1000 CNY.

### Отличие от Si/акций

- `Si` / `RI` остаются в конфиге как тестовая фикстура (commissionRub легаси; funding — нет).
- Акции — `STOCK`: издержки = `totalCommissionPerLotSide` (сплит не задан → fallback `commissionRub`);
  funding не применяется; проскальзывание — спред из свечи (`bt.realistic-execution`) или 0.1%.
- `CNYRUB_TOM` (кассовый юань) — легаси фикстура тикера акций/валюты, в бэктест-калибровке не участвует.

## 16.3. Источники истины

- Код: `src/main/kotlin/com/trading/bot/config/InstrumentsConfig.kt`
  (defaults для тестов + `validateSpecs()`), `application.yml` → `instruments.instruments` (live-значения).
- P&L/funding: `PnlCalculator.futures` (OrderExecutionEngine), `FundingCosts`, `BacktestEngine.closePosition`;
  LIVE funding — `FundingSnapshotService` (`application/funding/`: `FundingConfig`, `ConfiguredFundingProvider`,
  `MoexFundingProvider`, `FundingSnapshotService`).
- Маржинальный гейт: `RiskManagementService.freshMarginOfPositions` — ГО открытых позиций из ЕДИНОГО
  риск-снапшота входа (`FuturesRiskSnapshot`, P1): side-specific живой `GET_GO` (long/short `initialMargin`,
  TTL 30с); `marginUsed` и статический spec.go в LIVE НЕ используются (устаревшие). Недоступность/позиция вне
  снапшота → fail-closed `PORTFOLIO_MARGIN_DATA_UNAVAILABLE`.

## 16.4. Подлежит сверке перед LIVE

1. Фактическая комиссия Alor по счёту (брокерская) и клиринговая по выпискам MOEX (биржа) для CNYRUBF;
2. Величина funding (RUB/контракт/день) по данным MOEX и фактическим выплатам; **настроить и проверить
   `funding.moex-url`/столбец/множитель** (LIVE-источник, иначе работает CONFIG-fallback);
3. Фактическое начальное и уровни риска ГО (влияют на `freshMarginOfPositions` и сайзинг) — **long и short**;
4. Реалистичное проскальзывание CNYRUBF на минутных барах (для калибровки `slippageBps`; slippage теперь
   входит в live-риск-бюджет сайзинга `FuturesPositionSizer`, см. P1-5).