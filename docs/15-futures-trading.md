# 15. Фьючерсный контур (Si)

> **Статус**: реализовано и протестировано (unit + integration + e2e smoke в SIMULATION).
> Покрытие: `FuturesPositionSizerTest`, `FuturesRiskEngineTest`, `AlorFuturesClientTest`,
> `DailyLossCircuitBreakerTest`, `FuturesTradingBotServiceIntegrationTest` (Testcontainers + Postgres).

Фьючерсный контур — это параллельный «риск-first» исполнительный слой поверх legacy stock-бота.
В текущей версии торгуется один инструмент — фьючерс **Si** (доллар/рубль, MOEX FORTS).
Весь стек является событийным: фьючерсы обрабатываются только `FuturesTradingBotService`,
а legacy `TradingBotService` их игнорирует (`if (strat.ticker == "Si") return`).

## 15.1. Спецификация инструмента

Задаётся в `instruments` (`InstrumentsConfig`, `com.trading.bot.config`):

| Параметр | Значение | Пояснение |
|---|---|---|
| `ticker` | `Si` | валютный фьючерс USD/RUB |
| `type` | `FUTURES` | только фьючерсы проходят через futures-контур |
| `lotSize` | `1` | 1 контракт |
| `priceStep` | `0.01` | минимальный шаг цены (копейка) |
| `priceStepCost` | `10.0` ₽ | стоимость одного пункта |
| `go` | `15 000` ₽ | гарантийное обеспечение (начальная маржа) |
| `leverage` | `${leverage.user-leverage}` = `2.0` | плечо из `LeverageConfig`, clamp 1.0–3.0 |
| `baseAsset` | `USD` | размер контракта 1000 USD |

**Производные величины** (вычисляются, не задаются вручную):

- `pointValue = priceStepCost / priceStep = 10 / 0.01 = 1000 ₽` — стоимость 1.0 цены.
  Это и есть размер контракта: 1000 USD × курс.
- `marginPerContract = go / leverage = 15000 / 2 = 7500 ₽` — маржа на один контракт.
- P&L фьючерса: `(close - entry) * qty * pointValue` (LONG), знак инвертируется для SHORT.

## 15.2. Исполнительный сервис — `FuturesTradingBotService`

`src/main/kotlin/com/trading/bot/application/FuturesTradingBotService.kt`, `@Service`.

Публикуемые и потребляемые события:

| Событие | Роль |
|---|---|
| `StrategyGeneratedEvent` (BUY/SELL по Si) | вход: `onStrategyGenerated` → `openFuturesPosition` |
| `PriceChangedEvent` (Si) | мониторинг: `onPriceChanged` → `monitorOpenPositions` |
| `PositionClosedEvent` | после закрытия → `DailyLossCircuitBreaker` обновляет дневной P&L |
| `TradingHaltedEvent` | глобальная остановка входа, мониторинг продолжается |

**Поток входа** (`openFuturesPosition`):

1. `futuresRiskEngine.isDailyLossLimitReached()` → блок `DAILY_LIMIT`.
2. `tradingHoursGuard.isTradingAllowed()` → блок `OUTSIDE_HOURS`.
3. `entryPrice = alorClient.getLastPrice(ticker) ?: targetPrice`.
4. `currentGo = alorFuturesClient.getFuturesGO(ticker)`; `portfolioMoney = alorFuturesClient.getPortfolioMoney()`.
5. `futuresRiskEngine.validateEntry(...)` — risk-first, все guardrails (раздел 15.3).
6. `orderOutboxService.placeOrder(ticker, side, qty, entryPrice, "limit")` — через Outbox.
7. `alorClient.verifyOrder(placed.alorOrderId)` — фактическая цена (в SIMULATION `null` → entryPrice).
8. Сохранение `Position` с futures-полями: `instrumentType=FUTURES`, `leverage`, `goPerContract`,
   `marginUsed`, `liquidationPrice`, `variationMargin`, `stopLossPoints`, `alorOrderId`.
9. `eventPublisher.publishPositionOpened(pos)`, метрика `futures.position.opened`.

**Поток мониторинга** (`monitorOpenPositions`, каждый `PriceChangedEvent` по Si):

1. `futuresRiskEngine.checkLiquidationDistance(pos, price)`:
   - `CRITICAL` (< 10% остаточного буфера) → немедленный market close (`LIQUIDATION_CRITICAL`).
   - `WARNING` (< 25%) → лог + метрика `futures.liquidation.warning`.
2. SL/TP/trailing через legacy `RiskManagementService.shouldCloseBySL/TP/Trailing`.
3. `futuresRiskEngine.updateTrailingStop(pos, price)` — подтягивание в прибыль.

**Закрытие** (`closeFuturesPosition`): market-ордер через Outbox, P&L по формуле фьючерса,
`PositionClosedEvent` → `DailyLossCircuitBreaker`.

## 15.3. Риск-движок — `FuturesRiskEngine` + `FuturesPositionSizer`

`src/main/kotlin/com/trading/bot/domain/risk/`. Пакет `domain.risk` — чистый доменный риск.

### 15.3.1. Сайзинг (`FuturesPositionSizer.calculateSiContracts`)

| # | Шаг | Формула | Si (50k, GO 15k, стоп 50) |
|---|---|---|---|
| 1 | маржа на контракт | `marginPerContract = go / leverage` | 7500 ₽ |
| 2 | риск на сделку | `riskAmount = portfolio × riskPerTradePercent / 100` | 500 ₽ (1%) |
| 3 | убыток на стопе | `lossPerContract = stopLossPoints × priceStepCost` | 500 ₽ (50 × 10) |
| 4 | лимит по риску | `maxByRisk = floor(riskAmount / lossPerContract)` | 1 |
| 5 | маржинальный бюджет | `marginBudget = portfolio × maxMarginUsagePercent / 100` | 15 000 ₽ (30%) |
| 6 | лимит по марже | `maxByMargin = floor(marginBudget / marginPerContract)` | 2 |
| 7 | итог | `qty = min(maxByRisk, maxByMargin, maxContractsPerPosition)` | **1** |

При `qty < 1` вход запрещён с причиной `ZERO_RISK_SIZE` / `INSUFFICIENT_MARGIN`.

**Ликвидационная цена**:
`pointValue = priceStepCost / priceStep = 1000`; `bufferPrice = marginPerContract × leverage / pointValue = (7500 × 2) / 1000 = 15 ₽` (движение, при котором теряется вся маржа контракта).

- LONG: `liq = entry - 15` (при entry 100 → 85)
- SHORT: `liq = entry + 15`

### 15.3.2. Guardrails входа (`FuturesRiskEngine.validateEntry`)

Порядок проверок (первая неудача → отказ):

| # | Guardrail | Условие | reason |
|---|---|---|---|
| 1 | Мастер-выключатель | `risk.enabled == false` | `RISK_DISABLED` |
| 2 | Плечо | `leverage.enabled == false` | `LEVERAGE_DISABLED` |
| 3 | Торговые часы | вне 10:00–18:30 МСК | `OUTSIDE_HOURS` |
| 4 | Дневной лимит | `dailyPnL <= -5000` | `DAILY_LIMIT` |
| 5 | Лимит позиций | открытых ≥ 1 | `MAX_POSITIONS` |
| 6 | Инструмент | не найден или не FUTURES | `UNSUPPORTED_INSTRUMENT` |
| 7 | Входные данные | price/money/GO ≤ 0 | `INVALID_INPUT` |
| 8 | Сайзинг | `quantity == 0` | `ZERO_RISK_SIZE` и др. |
| 9 | Маржа | `marginRequired > portfolio × 30%` | `INSUFFICIENT_MARGIN` |

Каждый отказ инкрементирует `risk.entry.rejected{reason}`.

**SL/TP в ценах** (важно: пункты × `priceStep`, НЕ × `priceStepCost`):

- SL: LONG `entry - 50 × 0.01` = entry − 0.50; SHORT `entry + 0.50`.
- TP (R:R = 1:2): LONG `entry + 100 × 0.01` = entry + 1.00; SHORT `entry − 1.00`.
- Пример entry 100: SL 99.50, TP 101.00.

### 15.3.3. Дистанция до ликвидации (`checkLiquidationDistance`)

```
totalBuffer     = |entry - liq|              (15 ₽ для Si)
remainingBuffer = |currentPrice - liq|
distancePercent = remainingBuffer / totalBuffer × 100
```

На входе distance = 100%, по мере убытка буфер тает:

| Статус | Условие | Действие |
|---|---|---|
| `SAFE` | distance ≥ 25% | — |
| `WARNING` | 10% ≤ distance < 25% | лог WARN + метрика `futures.liquidation.warning` |
| `CRITICAL` | distance < 10% | немедленный market close |

Пример: entry 92000, liq 91985 (buffer 15). На цене 91986 остаточный буфер 1/15 = 6.7% → `CRITICAL`.

### 15.3.4. Trailing stop (`updateTrailingStop`)

- Считает вариационную маржу: LONG `(price - entry) × qty × pointValue`, SHORT — инвертированно.
- Двигает trailing **только в прибыль** (`variationMargin > 0`) и **только в улучшающую сторону**.
- Никогда не ослабляет ниже жёсткого `stopLoss`.

## 15.4. Дневной лимит убытка — `DailyLossCircuitBreaker`

`src/main/kotlin/com/trading/bot/application/DailyLossCircuitBreaker.kt`.

- Подписан на `PositionClosedEvent`.
- Вызывает `futuresRiskEngine.updateDailyPnL(pnl)` — дневной P&L аккумулируется и персистится.
- Если `dailyPnL <= -risk.max-daily-loss-rub (-5000)`:
  - публикуется `TradingHaltedEvent("DAILY_LOSS_LIMIT")`,
  - инкрементируется `circuit.daily_loss.triggered`,
  - новые входы блокируются (`DAILY_LIMIT`), открытые позиции продолжают мониториться.

**Персистентность**: `daily_risk_snapshot` (`004-futures-risk.sql`):

| Колонка | Тип | Назначение |
|---|---|---|
| `id` | BIGSERIAL PK | — |
| `trade_date` | DATE (UNIQUE) | торговый день (МСК) |
| `daily_pnl` | NUMERIC(19,6) | накопленный P&L дня |
| `limit_reached` | BOOLEAN | достигнут ли дневной лимит |
| `max_drawdown_today` | NUMERIC(19,6) | максимальная просадка дня |
| `updated_at` | TIMESTAMP | время обновления |

Поведение при рестарте:
- при старте `FuturesRiskEngine.init` → `restoreDailyState()` → `resetDailyState()` восстанавливает
  snapshot для текущей даты (если дата совпадает — иначе нулевое состояние);
- при смене календарного дня (МСК) состояние сбрасывается (`resetDailyStateIfNewDay()`);
- публичный `resetDailyState()` доступен для админ-сброса и тестов.

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
| `getFuturesGO(ticker)` | `GET /md/v2/Securities/{exchange}/{ticker}/risk` → `long.initialMargin` | `instruments.*.go` (15 000 ₽) |
| `getPortfolioMoney()` | `GET /md/v2/Clients/{portfolio}/summaries` → `moneyAmount` / `money` | 50 000 ₽ |

- В `TRADING_MODE=SIMULATION` все вызовы возвращают конфиг-значения без сетевых запросов.
- Любая ошибка (timeout 10 c, сеть, парсинг) → fallback + WARN-лог + gauge `futures.go` / `futures.portfolio.money`.
- LIVE-режим требует `ALOR_TOKEN`; SIMULATION — нет.

## 15.7. БД и Liquibase

Миграция `004-futures-risk.sql` добавляет к `positions`:

| Колонка | Тип | Пояснение |
|---|---|---|
| `instrument_type` | VARCHAR(10), default `STOCK` | `STOCK` / `FUTURES` |
| `leverage` | NUMERIC(10,4) | эффективное плечо (2.0) |
| `go_per_contract` | NUMERIC(19,6) | GO (15 000) |
| `margin_used` | NUMERIC(19,6) | задействованная маржа (7 500) |
| `liquidation_price` | NUMERIC(19,6) | цена ликвидации (85.0) |
| `variation_margin` | NUMERIC(19,6), default 0 | накопленная вариационная маржа |
| `stop_loss_points` | INT | стоп в пунктах (50) |

Индекс `idx_positions_instrument_type`. Таблица `daily_risk_snapshot` — раздел 15.4.
Добавлены `PositionRepository.findById` и `DailyRiskSnapshotRepository.deleteAll` (для тестов и админ-сброса).

## 15.8. Конфигурация

| Переменная / свойство | Default | Назначение |
|---|---|---|
| `risk.max-position-rub` | 50 000 | депозит |
| `risk.max-daily-loss-rub` | 5 000 | дневной лимит убытка (10%) |
| `risk.max-open-positions` | 1 | лимит позиций |
| `risk.risk-per-trade-percent` | 1.0 | риск на сделку (500 ₽) |
| `risk.default-stop-loss-points` | 50 | стоп в пунктах |
| `risk.default-take-profit-points` | 100 | тейк в пунктах (R:R 1:2) |
| `risk.min-liquidation-distance-percent` | 25.0 | порог WARNING |
| `risk.max-margin-usage-percent` | 30.0 | потолок маржи |
| `risk.max-contracts-per-position` | 1 | жёсткий лимит контрактов |
| `risk.trading-hours-start` / `-end` | 10:00 / 18:30 | торговое окно МСК |
| `leverage.default-leverage` | 2.0 | плечо по умолчанию |
| `leverage.max-leverage` | 3.0 | верхний clamp |
| `TRADING_MODE` | `SIMULATION` | SIMULATION / LIVE |
| `RISK_TRADING_HOURS_START` | — | env-переопределение окна |

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
| `risk.entry.rejected{reason}` | counter | отказы входа (DAILY_LIMIT, OUTSIDE_HOURS, MAX_POSITIONS, …) |
| `risk.daily.pnl` / `risk.daily.limit.reached` | gauge | дневной P&L и флаг лимита |
| `circuit.daily_loss.triggered` | counter | срабатывание дневного лимита |

## 15.10. Тестирование

| Тест | Что покрывает |
|---|---|
| `FuturesPositionSizerTest` | формулы сайзинга, отказы, ликвидационные цены |
| `FuturesRiskEngineTest` | guardrails входа, дистанция до ликвидации, trailing stop |
| `AlorFuturesClientTest` | SIMULATION fallback GO/деньги, pointValue |
| `DailyLossCircuitBreakerTest` | публикация `TradingHaltedEvent`, метрики |
| `FuturesTradingBotServiceIntegrationTest` | полный поток: entry (все futures-поля), CRITICAL-ликвидация → market close, дневной лимит, MAX_POSITIONS, OUTSIDE_HOURS (реальный Postgres, мок Alor/TradingHoursGuard) |
| e2e smoke (см. 15.11) | полный boot в SIMULATION с docker Postgres+Redis |

## 15.11. E2E smoke-тест (SIMULATION)

Ручной сценарий, воспроизводимый одной командой (валидирует реальный boot, Liquibase, событийную шину, Outbox):

```powershell
# 1. Постгрес и редис
docker compose up -d postgres redis

# 2. Детерминированный сигнал BUY для Si
$json = '{"ticker":"Si","action":"BUY","targetPrice":100.0,"quantity":1,"stopLoss":99.5,"takeProfit":100.5,"trailingStop":true,"confidence":0.8,"reasoning":"e2e","rawJson":"{}","cycleId":"smoke","validUntil":"2026-08-04T23:59:00","createdAt":"2026-08-03T12:00:00"}'
docker exec trading-bot-redis redis-cli SET "strategy:Si" $json EX 900

# 3. Запуск (JDK 21, SIMULATION, окно 00:00-23:59 чтобы не зависеть от времени)
java -jar build/libs/trading-bot-2.0.0.jar
#   env: TRADING_MODE=SIMULATION RISK_TRADING_HOURS_START=00:00 RISK_TRADING_HOURS_END=23:59

# 4. Триггер бот-цикла (публикует StrategyGeneratedEvent из Redis)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/bot/trigger" -Method Post

# 5. Проверка
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/positions"
```

Ожидаемый результат (подтверждён в 2026-08):

```json
{
  "ticker": "Si", "direction": "LONG", "quantity": 1,
  "entryPrice": 100.0, "stopLoss": 99.5, "takeProfit": 101.0,
  "instrumentType": "FUTURES", "leverage": 2.0, "goPerContract": 15000.0,
  "marginUsed": 7500.0, "liquidationPrice": 85.0,
  "stopLossPoints": 50, "alorOrderId": "sim-order-Si-..."
}
```

Плюс в БД: `order_outbox.status = SENT` с тем же `alor_order_id`, метрика `futures.position.opened = 1`.

## 15.12. Типовые отказы и диагностика

| Симптом | Причина | Диагностика |
|---|---|---|
| `risk.entry.rejected{reason=OUTSIDE_HOURS}` | вне 10:00–18:30 МСК | проверить `RISK_TRADING_HOURS_*` |
| `risk.entry.rejected{reason=DAILY_LIMIT}` | дневной убыток ≤ −5000 | `GET /risk/daily-pnl`, `risk.daily.pnl` |
| `risk.entry.rejected{reason=MAX_POSITIONS}` | уже открыта 1 позиция | `GET /positions` |
| `risk.entry.rejected{reason=INSUFFICIENT_MARGIN}` | маржа > 30% депозита | `futures.margin.used`, `futures.go` |
| `LIQUIDATION_CRITICAL` | остаточный буфер < 10% | `futures.liquidation.distance` gauge |
| позиция не открылась, нет событий | сигнал не BUY/SELL или HOLD из конвейера | лог `Strategy Si: <action>` |
