# 15. Фьючерсный контур (CNYRUBF)

> **Статус**: реализовано и протестировано (unit + integration + e2e smoke в SIMULATION).
> Покрытие: `FuturesPositionSizerTest`, `FuturesRiskEngineTest`, `AlorFuturesClientTest`,
> `DailyLossCircuitBreakerTest`, `FuturesTradingBotServiceIntegrationTest` (Testcontainers + Postgres).

Фьючерсный контур — параллельный «риск-first» исполнительный слой поверх legacy stock-бота.
В актуальной версии торгуемый фьючерс — **CNYRUBF** (юань/рубль, MOEX FORTS). *(Ранее документ
описывал фьючерс Si; Si остался в `instruments` как тестовая фикстура/пример, вход — строго CNYRUBF.)*
Весь стек событийный: фьючерсы обрабатываются только `FuturesTradingBotService`, а legacy
`TradingBotService` их игнорирует.

## 15.1. Спецификация инструмента

Задаётся в `instruments` (`InstrumentsConfig`, `com.trading.bot.config`). Актуальные параметры всех
инструментов калибровки — **docs/16-instrument-specifications.md**. Ключевые для CNYRUBF:

| Параметр | Значение | Пояснение |
|---|---|---|
| `ticker` | `CNYRUBF` | валютный фьючерс CNY/RUB |
| `type` | `FUTURES` | только фьючерсы проходят через futures-контур |
| `lotSize` | `1000` | 1 контракт = 1000 CNY |
| `priceStep` | `0.001` | минимальный шаг цены |
| `priceStepCost` | `1.0` ₽ | стоимость минимального шага |
| `go` | `850` ₽ | конфиг-ГО (для SIM; в LIVE — фактическое `GET /risk`, кэш TTL 30 c) |
| `leverage` | `${leverage.user-leverage}` = `2.0` | плечо из `LeverageConfig` (информационное) |
| `baseAsset` / `quoteAsset` | `CNY` / `RUB` | пара |
| `brokerCommissionRub` / `exchangeFeeRub` | `1.0` / `0.5` ₽ | сплит комиссии за контракт/сторону |
| `slippageBps` | `1.0` | проскальзывание, базисных пунктов (бэктест) |
| `fundingRubPerContractPerDay` | `0.5` ₽ | funding за контракт за клиринг (18:45 МСК) |

**Производные величины** (вычисляются, не задаются вручную):

- `pointValue = priceStepCost / priceStep = 1 / 0.001 = 1000 ₽` — стоимость 1.0 цены.
  Размер контракта: 1000 CNY × курс (≈ 12 800 ₽ при цене 12.8).
- `marginPerContract = go / leverage = 850 / 2 = 425 ₽` в SIM; в LIVE — фактическое ГО × qty.
- P&L фьючерса: `(close − entry) × qty × pointValue − комиссия round-trip − funding`
  (`PnlCalculator.futures`; комиссия = `totalCommissionPerLotSide × qty × 2`,
  funding = `fundingPerClearing × qty × число пережитых клирингов`, см. `FundingCosts`).

## 15.2. Исполнительный сервис — `FuturesTradingBotService`

`src/main/kotlin/com/trading/bot/application/FuturesTradingBotService.kt`, `@Service`.

Публикуемые и потребляемые события:

| Событие | Роль |
|---|---|
| `StrategyGeneratedEvent` (BUY/SELL по фьючерсу) | вход: `onStrategyGenerated` → `openFuturesPosition` |
| `PriceChangedEvent` (фьючерс) | мониторинг: `onPriceChanged` → `monitorOpenPositions` |
| `PositionClosedEvent` | после закрытия → `DailyLossCircuitBreaker` обновляет дневной P&L |
| `TradingHaltedEvent` | глобальная остановка входа, мониторинг продолжается |

**Поток входа** (`openFuturesPosition`):

1. Risk-first guardrails (`FuturesRiskEngine.validateEntry` → `FuturesEntryProfile.postSizingChecks`).
2. `entryPrice = alorClient.getLastPrice(ticker) ?: targetPrice`.
3. `currentGo = alorFuturesClient.getFuturesGO(ticker)`; `portfolioMoney = alorFuturesClient.getPortfolioMoney()`
   (LIVE: TTL-кэш 30 c, fail-closed по устаревшим данным — см. 15.6).
4. Сайзинг `FuturesPositionSizer.calculateContracts(...)` (пределы маржи/риска/`max-contracts-per-position`).
5. Маржинальный гейт `PORTFOLIO_MARGIN_LIMIT` по **живому** ГО существующих позиций
   (`RiskManagementService.freshMarginOfPositions`; статический spec.go в LIVE НЕ используется,
   недоступность ГО открытых позиций → fail-closed `PORTFOLIO_MARGIN_DATA_UNAVAILABLE`).
6. `orderOutboxService.placeOrder(ticker, side, qty, entryPrice, "limit")` — через Outbox.
7. `alorClient.verifyOrder(placed.alorOrderId)` — фактическая цена (в SIMULATION `null` → entryPrice).
8. Сохранение `Position` с futures-полями: `instrumentType=FUTURES`, `leverage`, `goPerContract`,
   `marginUsed`, `liquidationPrice`, `variationMargin`, `stopLossPoints`, `alorOrderId`.
9. `eventPublisher.publishPositionOpened(pos)`, метрика `futures.position.opened`.

**Поток мониторинга** (`monitorOpenPositions`, каждый `PriceChangedEvent` по фьючерсу):

1. `futuresRiskEngine.checkLiquidationDistance(pos, price)`:
   - `CRITICAL` (< 10% остаточного буфера) → немедленный market close (`LIQUIDATION_CRITICAL`).
   - `WARNING` (< 25%) → лог + метрика `futures.liquidation.warning`.
2. SL/TP/trailing через legacy `RiskManagementService.shouldCloseBySL/TP/Trailing`.
3. `futuresRiskEngine.updateTrailingStop(pos, price)` — подтягивание в прибыль.

**Закрытие** (`closeFuturesPosition`): market-ордер через Outbox, P&L по формуле фьючерса с вычетом
комиссии и funding, `PositionClosedEvent` → `DailyLossCircuitBreaker`.

> **О ликвидации** (`bt.futures-liquidation-simulation`, LIVE-монитор то же): позиция, чей бар пробил
> `liquidationPrice` (LONG = entry − GO/pointValue), закрывается по liq-цене (worst-case, до SL/TP).
> При калибровочном SL 300 пт (CNYRUBF) стоп срабатывает раньше liq-буфера (GO 850 / pointValue 1000 =
> 0.85 ₽ ≈ 850 пт) — на доступной истории ликвидаций не наблюдалось (см. AGENTS.md).

## 15.3. Риск-движок — `FuturesRiskEngine` + `FuturesPositionSizer`

`src/main/kotlin/com/trading/bot/domain/risk/`. Пакет `domain.risk` — чистый доменный риск.

### 15.3.1. Сайзинг (`FuturesPositionSizer.calculateContracts`)

| # | Шаг | Формула | CNYRUBF (50k, GO 850, стоп 150) |
|---|---|---|---|
| 1 | маржа на контракт | `marginPerContract = go / leverage` | 425 ₽ |
| 2 | риск на сделку | `riskAmount = portfolio × riskPerTradePercent / 100` | 500 ₽ (1%) |
| 3 | убыток на стопе | `lossPerContract = stopLossPoints × priceStepCost` | 150 ₽ (150 × 1) |
| 4 | лимит по риску | `maxByRisk = floor(riskAmount / lossPerContract)` | 3 |
| 5 | маржинальный бюджет | `marginBudget = portfolio × maxMarginUsagePercent / 100` | 30 000 ₽ (60% demo/live) |
| 6 | лимит по марже | `maxByMargin = floor(marginBudget / marginPerContract)` | 70 |
| 7 | итог | `qty = min(maxByRisk, maxByMargin, maxContractsPerPosition)` | **1** (потолок 1 в live) |

При `qty < 1` вход запрещён с причиной `ZERO_RISK_SIZE` / `INSUFFICIENT_MARGIN`.

**Ликвидационная цена** (симуляция):
`pointValue = 1000`; `bufferPrice = marginPerContract × leverage / pointValue = (425 × 2) / 1000 = 0.85 ₽`
(движение, при котором теряется вся маржа контракта).

- LONG: `liq = entry − 0.85` (при entry 12.80 → 11.95)
- SHORT: `liq = entry + 0.85`

### 15.3.2. Guardrails входа (`FuturesRiskEngine.validateEntry` + `FuturesEntryProfile`)

Порядок проверок (первая неудача → отказ):

| # | Guardrail | Условие | reason |
|---|---|---|---|
| 1 | Мастер-выключатель | `risk.enabled == false` | `RISK_DISABLED` |
| 2 | Плечо | `leverage.enabled == false` | `LEVERAGE_DISABLED` |
| 3 | Торговые часы | вне 10:00–18:30 МСК | `OUTSIDE_HOURS` |
| 4 | Дневной лимит | `dailyPnL <= −effectiveLimit` (min(2% AUM, 5 000 ₽) = 1 000 ₽ на 50k) | `DAILY_LIMIT` |
| 5 | Лимит позиций | открытых ≥ 1 | `MAX_POSITIONS` |
| 6 | Инструмент | не найден или не FUTURES | `INSTRUMENT_SPEC_MISSING` / `UNSUPPORTED_INSTRUMENT` |
| 7 | Входные данные | price/money/GO ≤ 0 | `INVALID_INPUT` |
| 8 | Сайзинг | `quantity == 0` (включая лимит маржи `portfolio × maxMarginUsagePercent`) | `ZERO_RISK_SIZE` / `INSUFFICIENT_MARGIN` |
| 9 | Маржинальная загрузка | (ГО открытых + ГО кандидата×1.5) > `maxMarginUsagePercent` × депозит | `PORTFOLIO_MARGIN_LIMIT` / `PORTFOLIO_MARGIN_DATA_UNAVAILABLE` |

Каждый отказ инкрементирует `risk.entry.rejected{reason}`.

**SL/TP в ценах** (важно: пункты × `priceStep`, НЕ × `priceStepCost`):

- SL: LONG `entry − 150 × 0.001` = entry − 0.15; SHORT `entry + 0.15`.
- TP (параметры калибровки): LONG `entry + 1200 × 0.001` = entry + 1.20; SHORT `entry − 1.20`.
- Пример entry 12.80: SL 12.65, TP 14.00.

### 15.3.3. Дистанция до ликвидации (`checkLiquidationDistance`)

```
totalBuffer     = |entry - liq|              (0.85 ₽ для CNYRUBF)
remainingBuffer = |currentPrice - liq|
distancePercent = remainingBuffer / totalBuffer × 100
```

На входе distance = 100%, по мере убытка буфер тает:

| Статус | Условие | Действие |
|---|---|---|
| `SAFE` | distance ≥ 25% | — |
| `WARNING` | 10% ≤ distance < 25% | лог WARN + метрика `futures.liquidation.warning` |
| `CRITICAL` | distance < 10% | немедленный market close |

Пример: entry 12.80, liq 11.95 (buffer 0.85). На цене 12.03 остаточный буфер 0.08/0.85 ≈ 9.4% → `CRITICAL`.

### 15.3.4. Trailing stop (`updateTrailingStop`)

- Считает вариационную маржу: LONG `(price − entry) × qty × pointValue`, SHORT — инвертированно.
- Двигает trailing **только в прибыль** (`variationMargin > 0`) и **только в улучшающую сторону**.
- Никогда не ослабляет ниже жёсткого `stopLoss`.

## 15.4. Дневной лимит убытка — `DrawdownProtectionService` / `DailyRiskGuard`

См. docs/05 (`multi-tier drawdown`) и docs/15-старый `DailyLossCircuitBreaker` (легаси, фьючерсный
путь переведён на единый `DrawdownProtectionService`).

- Эффективный лимит = `min(maxDailyLossPercent% × AUM, maxDailyLossRub)` = `min(2% × 50k, 5k)` = **1 000 ₽**.
- Если `dailyPnL ≤ −effectiveLimit`:
  - публикуется `TradingHaltedEvent("DAILY_LOSS_LIMIT")`,
  - инкрементируется `circuit.daily_loss.triggered`,
  - новые входы блокируются (`DAILY_LIMIT`), открытые позиции продолжают мониториться.

**Персистентность**: `daily_risk_snapshot` (см. docs/06). Поведение при рестарте дня — восстановление
snapshot по дате (МСК), сброс при смене календарного дня.

## 15.5. Торговые часы — `TradingHoursGuard`

`src/main/kotlin/com/trading/bot/application/TradingHoursGuard.kt`.

- Окно `risk.trading-hours-start` (10:00) – `risk.trading-hours-end` (18:30) МСК, **полуоткрытый** интервал:
  в 10:00 и 18:30 ровно вход запрещён.
- Часовой пояс `Europe/Moscow` жёстко зашит.
- Вне окна: `OUTSIDE_HOURS` (метрика `risk.entry.rejected{reason=OUTSIDE_HOURS}`).

## 15.6. Alor-клиент фьючерсов — `AlorFuturesClient`

`src/main/kotlin/com/trading/bot/infrastructure/alor/AlorFuturesClient.kt`.

| Метод | Endpoint (LIVE) | Fallback (SIMULATION / сбой) |
|---|---|---|
| `getFuturesGO(ticker)` | `GET /md/v2/Securities/{exchange}/{ticker}/risk` → `long.initialMargin` | `instruments.*.go` (CNYRUBF 850 ₽) |
| `getPortfolioMoney()` | `GET /md/v2/Clients/{portfolio}/summaries` → `moneyAmount` / `money` | 50 000 ₽ |

- В `TRADING_MODE=SIMULATION` все вызовы возвращают конфиг-значения без сетевых запросов.
- **LIVE (P0-2)**: GO и свободные средства кэшируются с TTL `risk.max-go-age-ms = 30 000` (per-ticker/
  per-portfolio). При недоступности API и действующем кэше — ответ из кэша; по истечении — перезапрос;
  API down + устаревший кэш → `null` (fail-closed): сайзинг по старому ГО/балансу запрещён.
- LIVE-режим требует `ALOR_TOKEN`; SIMULATION — нет.

## 15.7. БД и Liquibase

Миграция `004-futures-risk.sql` добавляет к `positions`:

| Колонка | Тип | Пояснение (пример CNYRUBF) |
|---|---|---|
| `instrument_type` | VARCHAR(10), default `STOCK` | `STOCK` / `FUTURES` |
| `leverage` | NUMERIC(10,4) | эффективное плечо (2.0) |
| `go_per_contract` | NUMERIC(19,6) | GO (850) |
| `margin_used` | NUMERIC(19,6) | задействованная маржа (в SIM: 425) |
| `liquidation_price` | NUMERIC(19,6) | цена ликвидации (11.95) |
| `variation_margin` | NUMERIC(19,6), default 0 | накопленная вариационная маржа |
| `stop_loss_points` | INT | стоп в пунктах (150) |

Индекс `idx_positions_instrument_type`. Таблица `daily_risk_snapshot` — раздел 15.4.
Добавлены `PositionRepository.findById` и `DailyRiskSnapshotRepository.deleteAll` (для тестов и админ-сброса).

## 15.8. Конфигурация

| Переменная / свойство | Default | Назначение |
|---|---|---|
| `risk.max-position-rub` | 50 000 | депозит |
| `risk.max-daily-loss-percent` / `-rub` | 2.0 / 5 000 | дневной лимит: **эффективный min(2% AUM, 5 000 ₽)** |
| `risk.max-open-positions` | 3 (live), 1 (конструктор по умолчанию) | лимит позиций |
| `risk.futures-max-open-positions` | 1 | лимит фьючерсных позиций |
| `risk.risk-per-trade-percent` | 1.0 | риск на сделку (500 ₽) |
| `risk.default-stop-loss-points` | 50 | стоп в пунктах (легаси; калибровка — SL 300) |
| `risk.max-margin-usage-percent` | 60 (demo/live; backtest 90) | потолок маржи |
| `risk.max-contracts-per-position` | **1** (live; env `RISK_MAXCONTRACTSPERPOSITION`) | жёсткий лимит контрактов |
| `risk.max-go-age-ms` | 30 000 | TTL кэша ГО/баланса (fail-closed по устаревшим) |
| `risk.stressed-margin-multiplier` | 1.5 | стресс-запас маржинального гейта |
| `risk.trading-hours-start` / `-end` | 10:00 / 18:30 | торговое окно МСК |
| `leverage.user-leverage` | 2.0 | плечо (информационное для фьючерсов) |
| `TRADING_MODE` | `SIMULATION` | SIMULATION / LIVE |
| `RISK_TRADING_HOURS_START` | — | env-переопределение окна |

Комиссии/slippage/funding — в `instruments` (см. docs/16 и раздел 15.1).

## 15.9. Метрики (Prometheus)

| Метрика | Тип | Пояснение |
|---|---|---|
| `futures.position.opened{ticker,direction}` | counter | открытые фьючерсные позиции |
| `futures.position.closed{ticker,reason}` | counter | закрытия (STOP_LOSS/TAKE_PROFIT/LIQUIDATION_CRITICAL/…) |
| `futures.liquidation.warning{ticker}` | counter | предупреждения ликвидации |
| `futures.liquidation.distance{ticker}` | gauge | % остаточного буфера |
| `futures.go{ticker}` / `futures.portfolio.money` | gauge | GO и свободные средства |
| `futures.position.size` / `futures.margin.used` | gauge | размер позиции и маржа |
| `futures.entry.error` / `futures.monitor.error` / `futures.order.failed` | counter | ошибки |
| `futures.trading.halted{reason}` | counter | глобальная остановка |
| `risk.entry.rejected{reason}` | counter | отказы входа (DAILY_LIMIT, OUTSIDE_HOURS, MAX_POSITIONS, PORTFOLIO_MARGIN_*, …) |
| `risk.daily.pnl` / `risk.daily.limit.reached` | gauge | дневной P&L и флаг лимита |
| `circuit.daily_loss.triggered` | counter | срабатывание дневного лимита |

## 15.10. Тестирование

| Тест | Что покрывает |
|---|---|
| `FuturesPositionSizerTest` | формулы сайзинга, отказы, ликвидационные цены |
| `FuturesRiskEngineTest` | guardrails входа, дистанция до ликвидации, trailing stop |
| `AlorFuturesClientTest` / `AlorFuturesClientFreshnessTest` | SIMULATION fallback GO/деньги, TTL-кэш и fail-closed по свежести |
| `FuturesEntryProfilePostSizingTest` | маржинальный гейт со стресс-запасом 1.5×, fail-closed `INSTRUMENT_SPEC_MISSING` / `PORTFOLIO_MARGIN_DATA_UNAVAILABLE` |
| `DailyLossCircuitBreakerTest` | публикация `TradingHaltedEvent`, метрики |
| `PnlCalculatorCommissionTest` | вычет комиссии (lot/futures) и funding futures |
| `FundingCostsTest` | число пережитых клирингов (внутридень/кросс-день/выходные) |
| `FuturesTradingBotServiceIntegrationTest` | полный поток: entry (все futures-поля), CRITICAL-ликвидация → market close, дневной лимит, MAX_POSITIONS, OUTSIDE_HOURS (реальный Postgres, мок Alor/TradingHoursGuard) |
| e2e smoke (см. 15.11) | полный boot в SIMULATION с docker Postgres+Redis |

## 15.11. E2E smoke-тест (SIMULATION)

Ручной сценарий, воспроизводимый одной командой (валидирует реальный boot, Liquibase, событийную шину, Outbox):

```powershell
# 1. Постгрес и редис
docker compose up -d postgres redis

# 2. Детерминированный сигнал BUY для CNYRUBF (SL 150, TP 1200 пунктов)
$json = '{"ticker":"CNYRUBF","action":"BUY","targetPrice":12.8,"quantity":1,"stopLoss":12.65,"takeProfit":14.0,"trailingStop":true,"confidence":0.8,"reasoning":"e2e","rawJson":"{}","cycleId":"smoke","validUntil":"2026-09-20T23:59:00","createdAt":"2026-09-08T12:00:00"}'
docker exec trading-bot-redis redis-cli SET "strategy:CNYRUBF" $json EX 900

# 3. Запуск (JDK 21, SIMULATION, окно 00:00-23:59 чтобы не зависеть от времени)
java -jar build/libs/trading-bot-2.0.0.jar
#   env: TRADING_MODE=SIMULATION RISK_TRADING_HOURS_START=00:00 RISK_TRADING_HOURS_END=23:59

# 4. Триггер бот-цикла (публикует StrategyGeneratedEvent из Redis)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/bot/trigger" -Method Post

# 5. Проверка
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/positions"
```

Ожидаемый результат (цифры — SIM-конфиг CNYRUBF):

```json
{
  "ticker": "CNYRUBF", "direction": "LONG", "quantity": 1,
  "entryPrice": 12.8, "stopLoss": 12.65, "takeProfit": 14.0,
  "instrumentType": "FUTURES", "leverage": 2.0, "goPerContract": 850.0,
  "marginUsed": 425.0, "liquidationPrice": 11.95,
  "stopLossPoints": 150, "alorOrderId": "sim-order-CNYRUBF-..."
}
```

Плюс в БД: `order_outbox.status = SENT` с тем же `alor_order_id`, метрика `futures.position.opened = 1`.

## 15.12. Типовые отказы и диагностика

| Симптом | Причина | Диагностика |
|---|---|---|
| `risk.entry.rejected{reason=OUTSIDE_HOURS}` | вне 10:00–18:30 МСК | проверить `RISK_TRADING_HOURS_*` |
| `risk.entry.rejected{reason=DAILY_LIMIT}` | дневной убыток ≤ −1 000 (min(2% AUM, 5k) на 50k) | `GET /risk/daily-pnl`, `risk.daily.pnl` |
| `risk.entry.rejected{reason=MAX_POSITIONS}` | уже открыта 1 фьючерсная позиция | `GET /positions` |
| `risk.entry.rejected{reason=PORTFOLIO_MARGIN_LIMIT}` | (ГО открытых + GO кандидата×1.5) > 60% депозита | `futures.margin.used`, `futures.go` |
| `risk.entry.rejected{reason=PORTFOLIO_MARGIN_DATA_UNAVAILABLE}` | фактическое ГО открытых позиций недоступно (API down + устаревший кэш, marginUsed не записан) | логи `RiskManagementService.freshMarginOfPositions`, `risk.portfolio.*` |
| `LIQUIDATION_CRITICAL` | остаточный буфер < 10% | `futures.liquidation.distance` gauge |
| позиция не открылась, нет событий | сигнал не BUY/SELL или HOLD из конвейера | лог `Strategy CNYRUBF: <action>` |