# 11. Backtest Framework

> **Статус**: бэктест **реализован** в кодовой базе (`com.trading.bot.backtest`). REST-endpoint `GET /api/v1/backtest/{ticker}?days=365` доступен в работающем приложении, unit-тесты в `src/test/kotlin/com/trading/bot/backtest/BacktestEngineTest.kt` проходят в сборке.

## 11.1. Назначение и цель

Бэктест отвечает на вопрос «стоит ли вообще торговать этим тикером с текущими правилами входа/выхода» до ввода реальных денег. Реализованная версия использует **детерминированные индикаторы** (RSI + MACD + Bollinger) вместо LLM-агентов — это осознанное упрощение первого этапа:

| Критерий | Целевое состояние (документировано ниже) | Текущее состояние (реализовано) |
|---|---|---|
| Генерация сигналов | Конвейер агентов (tech → fund → strategy → contrarian → arbitrator) | Детерминированные индикаторы `signalAt()`: RSI(14) + MACD(12/26/9) |
| Исполнение | `SimulatedExecution` + PortfolioSim | `SimulatedExecution` (реализован), `PortfolioSim` инлайнится в `BacktestEngine.simulate()` |
| Метрики | Sharpe, MDD, PF, win rate | `BacktestMetrics.compute()` — реализовано |
| Критерии приёма | `isPassable()` — 4 условия | Реализовано в `BacktestResult.isPassable()` |
| Запуск | отдельный Spring-профиль `backtest` | REST-вызов из основного приложения (профиль `backtest` — roadmap) |

Основные задачи текущей версии:

1. **Валидация индикаторной логики** — проверка, что RSI/MACD-сигналы статистически прибыльны на истории.
2. **Оценка исполнения** — учёт комиссии 0.05% и проскальзывания 0.1%, которые в реальном конвейере существенно режут профит.
3. **Стоп-менеджмент** — проверка работы SL/TP по внутрисвечному диапазону (intraday high/low), как в живом `TradingBotService.monitor()`.
4. **Критерии приёма** — объективное решение PASS/REJECT через `isPassable()`.

### Архитектура

```mermaid
flowchart LR
    DB[(PostgreSQL<br/>candles MINUTE_10)] --> ENG[BacktestEngine<br/>@Service]
    ENG -->|свеча за свечой<br/>sorted by time| SIG[signalAt<br/>RSI + MACD]
    SIG -->|BUY/SELL/HOLD| SIM[SimulatedExecution<br/>комиссия/слippage/лот]
    SIM -->|fills| EQ[equity curve<br/>cash + позиция]
    EQ --> MET[BacktestMetrics<br/>Sharpe / MDD / PF / win rate]
    MET --> RES[BacktestResult]
    RES --> ACC{isPassable?}
    ACC -->|да| PASS[PASS — кандидат в прод]
    ACC -->|нет| REJ[REJECT — правка параметров]
```

### Ключевое отличие от живого конвейера

| Аспект | Живой бот | Бэктест |
|---|---|---|
| Цена входа | ордер по рынку/лимиту через Alor | открытие свечи `t+1` после сигнала (консервативно) |
| SL/TP | `monitor()` каждые 10 мин по `getLastPrice` | внутрисвечной диапазон `high/low` — даже если цена коснулась уровня и вернулась |
| Комиссия | брокер Alor | `0.05%` от оборота |
| Проскальзывание | фактическое исполнение | `0.1%` market |
| Решения | LLM-агенты + guardrails | чистые индикаторы (без LLM) |
| Рынок | реальные ордера | симуляция |

## 11.2. Структура пакета

```
com.trading.bot.backtest
├── BacktestEngine.kt        # @Service: run() + simulate() + signalAt() + PositionSim
├── SimulatedExecution.kt    # object: комиссия, slippage, лотность, hitStopOrTarget
├── BacktestResult.kt        # data class + BacktestMetrics (Sharpe/MDD/PF/win rate)
└── src/test/kotlin/com/trading/bot/backtest/BacktestEngineTest.kt  # 9 тестов
```

Связь с REST: `ApiController` инжектит `BacktestEngine` и вызывает `run(ticker, days)`.

## 11.3. BacktestEngine

`@Service`, единственная зависимость — `CandleRepository` (R2DBC, таблица `candles`).

### Публичное API

```kotlin
fun run(
    ticker: String,
    days: Int = 365,
    timeframe: String = "MINUTE_10",
    initialCapital: BigDecimal = BigDecimal("100000"),
    minBarsForSignal: Int = 30
): BacktestResult

fun simulate(
    ticker: String,
    candles: List<Candle>,
    initialCapital: BigDecimal = BigDecimal("100000"),
    minBarsForSignal: Int = 30
): BacktestResult

fun signalAt(candles: List<Candle>, index: Int, minBars: Int): StrategyAction
```

### `run()` — загрузка данных

1. `from = now - days`.
2. `candleRepo.findByTickerAndTimeframeAndTimeBetween(ticker, timeframe, from, now)` — свечи из PostgreSQL.
3. Если свечей `< minBarsForSignal + 2` — возвращает `emptyResult()` (лог `insufficient candles`).
4. Иначе — `simulate()`.

Warm-up: индикаторы считаются на окне `subList(0, index+1)`; пока `window.size < minBars` (30 свечей) сигнал всегда `HOLD`. Это соответствует поведению `IndicatorCalculator.calculate` в живом боте (`< 30 → null`).

### `simulate()` — главный цикл

Проход по отсортированным по времени свечам, с индекса 1:

```kotlin
for (i in 1 until sorted.size) {
    val current = sorted[i]

    // 1) SL/TP по внутрисвечному диапазону текущей свечи
    if (position != null && sl/tp заданы) {
        when (hitStopOrTarget(current, sl, tp)) {
            STOP   -> closePosition("STOP_LOSS",   sl)      // по стопу
            TARGET -> closePosition("TAKE_PROFIT", tp)      // по тейку
        }
    }

    // 2) сигнал по бару i-1
    val signal = signalAt(sorted, i - 1, minBars)
    if (signal == HOLD || signal == CLOSE) { фиксируем equity; continue }

    // 3) если позиция открыта и сигнал инверсный — REVERSAL
    if (позиция открыта) {
        if (направление противоположно сигналу) {
            closePosition("REVERSAL", current.openPrice)
            position = openPosition(signal, current.openPrice)
        }
        continue
    }

    // 4) открытие новой позиции по цене открытия текущей свечи
    position = openPosition(signal, current.openPrice)
    cash -= entry*qty + commission
    equityCurve.add(equityAt(cash, position, current.closePrice))
}
// 5) закрытие оставшейся позиции по последней цене (END_OF_PERIOD)
```

**Учёт денег** (документировано в коде):

- При открытии: `cash -= entry * qty + commission_entry`.
- При закрытии: `cash += exit * qty - commission_exit` (тело возвращается автоматически).
- PnL сделки включает обе комиссии (entry + exit).

### `signalAt()` — детерминированный сигнал

```kotlin
val ind = IndicatorCalculator.calculate(window) ?: return HOLD
return when {
    ind.rsi < 30 && ind.macdHistogram > 0 -> BUY   // перепроданность + бычий импульс
    ind.rsi > 70 && ind.macdHistogram < 0 -> SELL  // перекупленность + медвежий импульс
    else -> HOLD
}
```

Логика простая и прозрачная:

- `RSI < 30` — зона перепроданности, + положительный гистограммный MACD (сигнальная линия над MACD) — бычий импульс → `BUY`.
- `RSI > 70` — зона перекупленности, + отрицательный гистограммный MACD — медвежий импульс → `SELL`.
- Всё остальное — `HOLD`.

### `openPosition()` — сайзинг

```kotlin
if (cash <= 0) return null
val capitalSlice = cash * 0.20                    // 20% текущего капитала на одну позицию
val qty = (capitalSlice / price).toInt()          // вниз
val lotQty = SimulatedExecution.lotRounded(qty)   // кратно лоту
if (lotQty <= 0) return null
val fill = marketFill(price, isBuy)               // +0.1% проскальзывание
SL = fill * (1 - 2%)  /  fill * (1 + 2%)          // по направлению
TP = fill * (1 + 4%)  /  fill * (1 - 4%)
```

Константы текущей версии — вынесены в конфиг `bt.*` (см. 11.8.1, реализовано v2.3):

| Константа | Ключ конфига | По умолчанию | Комментарий |
|---|---|---|---|
| Слайс капитала | `bt.capital-slice` | `0.20` | соответствует духу `max-position-rub` в живом боте |
| Стоп-лосс | `bt.sl-percent` | `2.0` | % от цены входа; соответствует `risk.default-stop-loss-percent` |
| Тейк-профит | `bt.tp-percent` | `4.0` | % от цены входа; соответствует `risk.default-take-profit-percent` |
| Min лот | — | `1` | `lotRounded` округляет вниз, `0` → позиция не открывается |

### `closePosition()`

```kotlin
val fill = marketFill(price, isSell)              // +0.1% для закрытия SHORT, -0.1% для LONG
val pnl = (fill - entry) * qty  - commission_entry - commission_exit
tradeReturns.add(pnl)
val proceeds = fill * qty - commission_exit
equityCurve.add(cash + proceeds)
return cash + proceeds
```

Причины закрытия в текущей версии: `STOP_LOSS`, `TAKE_PROFIT`, `REVERSAL`, `END_OF_PERIOD`.

## 11.4. SimulatedExecution

`object` с чистыми функциями без состояния.

| Параметр | Значение | Примечание |
|---|---|---|
| `COMMISSION_RATE` | `0.0005` (0.05%) | от оборота, entry + exit; соответствует типовым брокерским тарифам |
| `MARKET_SLIPPAGE_RATE` | `0.001` (0.1%) | только для market-ордеров |
| Слиппедж limit | `0` | исполнение ровно по лимиту или лучше |
| Лотность | округление вниз до целого | `lotRounded(qty)` = `if (qty < 1) 0 else qty` |

### Функции

```kotlin
fun limitFill(limitPrice: BigDecimal, nextOpen: BigDecimal, isBuy: Boolean): Fill
    // isBuy:  price = min(nextOpen, limitPrice)   (исполнение не хуже лимита)
    // isSell: price = max(nextOpen, limitPrice)

fun marketFill(reference: BigDecimal, isBuy: Boolean): Fill
    // isBuy:  price = reference + 0.1%
    // isSell: price = reference - 0.1%

fun commissionOn(price: BigDecimal): BigDecimal
    // price * 0.0005, scale=4, HALF_UP

fun hitStopOrTarget(candle: Candle, sl: BigDecimal, tp: BigDecimal): StopTpHit?
    // low <= sl  -> STOP
    // high >= tp -> TARGET
    // иначе      -> null

enum class StopTpHit { STOP, TARGET }
```

### Проверка SL/TP по внутрисвечному диапазону

Главное отличие от живого мониторинга: в бэктесте стоп и тейк проверяются по `highPrice`/`lowPrice` **каждой свечи**, а не по текущей цене в момент опроса. Это консервативно: если внутри свечи цена коснулась `stopLoss` и развернулась, сделка всё равно закроется по стопу.

Порядок проверки в `simulate()`: сначала `STOP`, затем `TARGET`. При одновременном касании в одной свече приоритет у стопа (соответствует худшему исходу).

## 11.5. BacktestResult и BacktestMetrics

### BacktestResult

```kotlin
data class BacktestResult(
    val ticker: String,
    val totalReturn: Double,
    val sharpeRatio: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val profitFactor: Double,
    val totalTrades: Int,
    val avgHoldBars: Double,
    val equityCurve: List<BigDecimal>,
    val monthlyReturns: Map<String, Double>
) {
    fun isPassable(): Boolean =
        sharpeRatio > 1.2 &&
        maxDrawdown < 0.15 &&
        profitFactor > 1.3 &&
        totalTrades >= 100
}
```

> Примечание: `avgHoldBars` и `monthlyReturns` в текущей реализации — заглушки (`0.0` / `emptyMap()`). Заполнение — roadmap (раздел 11.8).

### BacktestMetrics.compute()

| Метрика | Формула | Цель |
|---|---|---|
| `totalReturn` | `equity_final / initialCapital - 1` | — |
| `sharpeRatio` | `mean(r - rf) / std(r) * sqrt(N)`, `rf=0` | > 1.2 |
| `maxDrawdown` | `max(1 - equity/peak)` по кривой | < 15% |
| `winRate` | `wins / totalTrades` | — |
| `profitFactor` | `grossProfit / |grossLoss|` (∞, если потерь нет) | > 1.3 |
| `totalTrades` | `tradeReturns.size` | >= 100 |

Особенности:

- `sharpeRatio()` возвращает `0.0` при `< 2` сделках или нулевой дисперсии — защита от деления на ноль.
- `maxDrawdown()` принимает `equityCurve` (первый элемент = начальный капитал + первая сделка, поэтому MDD считается корректно с учётом начального капитала).
- `profitFactor` — `POSITIVE_INFINITY`, если `grossLoss == 0` и есть прибыль; `0.0`, если прибыли нет вовсе.

### Критерии приёма в прод

Все четыре условия обязательны (`isPassable()`):

```kotlin
sharpeRatio > 1.2 &&
maxDrawdown < 0.15 &&
profitFactor > 1.3 &&
totalTrades >= 100
```

Логика логов движка:

```kotlin
"return=12.34%, Sharpe=1.41, MDD=8.10%, PF=1.87, win=54.21%, trades=152 -> PASS/REJECT"
```

## 11.6. REST endpoint

### GET /api/v1/backtest/{ticker}?days=365

- **Query**: `days` (default 365).
- **Метрика**: `api.backtest` counter, тег `ticker`.
- **Response 200**: `BacktestResult` (JSON):

```json
{
  "ticker": "SBER",
  "totalReturn": 0.1234,
  "sharpeRatio": 1.41,
  "maxDrawdown": 0.081,
  "winRate": 0.5421,
  "profitFactor": 1.87,
  "totalTrades": 152,
  "avgHoldBars": 0.0,
  "equityCurve": [100000, 100320.5, 100150.2, 101005.7],
  "monthlyReturns": {}
}
```

`equityCurve` содержит значения после каждой свечи (включая последнее = итоговый капитал) и может быть использован UI для построения графика.

Пример curl:

```bash
curl "http://localhost:8080/api/v1/backtest/SBER?days=365"
```

Предостережение: при малом числе свечей (тикер недавно добавлен, данных `< 32`) движок возвращает `emptyResult()` — все метрики `0.0`, `totalTrades = 0`, PASS/REJECT = `REJECT`.

## 11.7. Тесты

Файл `src/test/kotlin/com/trading/bot/backtest/BacktestEngineTest.kt` — 9 тестов (нет Spring-контекста, чистые unit-тесты):

| Тест | Что проверяет |
|---|---|
| `simulate produces results on trending data` | прогон на нисходящем тренде: сделки, equity curve, метрики конечны |
| `sharpe ratio is zero for flat returns` | Sharpe = 0 при нулевых доходностях |
| `max drawdown is computed correctly` | расчёт MDD на серии эквити (пик → минимум) |
| `acceptance criteria reject weak results` | слабый результат → `isPassable() = false` |
| `acceptance criteria pass strong results` | сильный результат → `isPassable() = true` |
| `backtest metrics include risk and quality ratios` | Sortino, Expectancy, WinLoss, RecoveryFactor, AvgTrade |
| `metrics map is compact and excludes heavy series` | `metrics()` — 13 полей, без `equityCurve`/`monthlyReturns`/`tradeReturns` |
| `commission and slippage constants` | комиссия 0.05%, проскальзывание 0.1% |
| `lot rounding is down to whole lots of instrument` | округление до целых лотов (SBER=10, VTBR=1000) |

Запуск:

```bash
.\gradlew.bat test --tests "com.trading.bot.backtest.BacktestEngineTest"
```

## 11.8. Ограничения текущей версии и roadmap

### Конфигурация `bt.*` (реализовано, v2.3)

Параметры прогона по умолчанию вынесены из кода в конфиг `bt.*`
(`BacktestConfig`, `@ConfigurationProperties(prefix = "bt")`). Используются,
когда REST-вызов не передаёт query-параметры:

| Ключ | Env | По умолчанию | Назначение |
|---|---|---|---|
| `bt.initial-capital` | `BT_INITIAL_CAPITAL` | `100000` | стартовый капитал (руб) |
| `bt.days` | `BT_DAYS` | `365` | глубина истории (дней) |
| `bt.timeframe` | `BT_TIMEFRAME` | `MINUTE_10` | таймфрейм свечей |
| `bt.min-bars-for-signal` | `BT_MIN_BARS_FOR_SIGNAL` | `30` | warm-up: минимальное число баров для сигнала |
| `bt.sl-percent` | `BT_SL_PERCENT` | `2.0` | стоп-лосс, % от цены входа |
| `bt.tp-percent` | `BT_TP_PERCENT` | `4.0` | тейк-профит, % от цены входа |
| `bt.capital-slice` | `BT_CAPITAL_SLICE` | `0.20` | доля капитала на одну позицию |

Значения применяются в `BacktestEngine.run/simulate` (дефолты параметров),
`BacktestValidator.validate` (initialCapital, minBarsForSignal) и в эндпоинтах
`ApiController` (дефолты `days`/`timeframe`).

Честный список того, чего в текущей реализации **нет** (важно не путать с дизайном из исходного раздела):

1. **Нет LLM-агентов** — сигналы чисто индикаторные. Интеграция с конвейером (tech→fund→strategy→contrarian→arbitrator) — отдельная задача.
2. **Нет `avgHoldBars` и `monthlyReturns`** — заглушки, расчёт по месяцам отложен.
3. **Out-of-sample — детерминированно** — OOS покрыт walk-forward `BacktestValidator` (`/api/v1/backtest/{ticker}/validate`, раздел 13.7.7); LLM/ML-подход — roadmap.
4. **Нет отдельного Spring-профиля `backtest`** — запуск через REST. Профиль (автозапуск по `--bt.tickers`, отчёт в консоль) — roadmap. Персист результатов в `backtest_results` реализован (разделы 13.7.3, 7.2).
5. **Нет распределения по тикерам** — один вызов = один тикер; панельный прогон не реализован.
6. ~~**Константы захардкожены** — 20% слайс капитала, 2%/4% SL/TP, `initialCapital = 100000`~~. Вынесено в конфиг `bt.*` (11.8.1).
7. **Внутрисвечное исполнение SL/TP** — по уровню без уточнения «цена открытия следующей свечи» для limit-ордеров: вход всегда по открытию `t+1` через `marketFill`, лимитные входы не используются в цикле (функция `limitFill` готова, но в `simulate` не задействована).

### Проектный запуск (целевое, раздел 11.9)

```bash
./gradlew bootRun --args="--spring.profiles.active=backtest --bt.tickers=SBER,GAZP --bt.days=365"
```

## 11.9. Проектное состояние (для реализации)

Ниже — проектный дизайн, зафиксированный до реализации, как основа для расширения.

### Источники данных

- Таблица `candles` (10-минутные свечи, `MINUTE_10`) — основной источник (реализовано).
- Наполнение: `MoexClient.getCandles(ticker, days, from)` — исторические данные MOEX ISS (interval=10).
- Для длинных горизонтов — расширить MOEX-загрузку по 20-дневным окнам (ISS отдаёт ~2000 свечей на запрос).
- Альтернатива: CSV-экспорт из QUIK/Finam.

### Профиль `backtest`

`BacktestApplication` с `@Profile("backtest")` — вместо планировщиков и Alor включается `BacktestEngine`. Результат пишется в консоль и в БД (таблица `backtest_results`) для сравнения итераций.

### Дополнительные проверки (целевые)

- **Warm-up**: 30 свечей (реализовано в `minBarsForSignal`).
- **Распределение по тикерам**: результат не должен достигаться за счёт 1–2 тикеров.
- **Режимы рынка**: отдельные прогоны на трендовых и волатильных периодах.
- **Переобучение**: out-of-sample на удержанных 20%.
