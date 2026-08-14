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
├── MonteCarloAnalyzer.kt    # Monte Carlo bootstrap + стресс-сценарии (13.7.8)
└── src/test/kotlin/com/trading/bot/backtest/  # BacktestEngineTest, MonteCarloAnalyzerTest и др.
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
    val pos0 = position
    if (pos0 != null && pos0.stopLoss != null && pos0.takeProfit != null) {
        when (SimulatedExecution.hitStopOrTarget(current, pos0.stopLoss, pos0.takeProfit)) {
            STOP   -> closePosition("STOP_LOSS",   pos0.stopLoss)   // по стопу
            TARGET -> closePosition("TAKE_PROFIT", pos0.takeProfit) // по тейку
        }
    }

    // 2) сигнал по бару i-1
    val signal = signalAt(sorted, i - 1, minBarsForSignal)
    if (signal == HOLD || signal == CLOSE) {
        equityCurve.add(equityAt(cash, position, current.closePrice)) // фиксируем equity
        continue
    }

    // 3) если позиция открыта и сигнал инверсный — REVERSAL
    val curPos = position
    if (curPos != null) {
        val opposite = if (signal == BUY) SHORT else LONG
        if (curPos.direction == opposite) {
            closePosition("REVERSAL", current.openPrice)
            position = openPosition(signal, current.openPrice)
        }
        equityCurve.add(equityAt(cash, position, current.closePrice))
        continue
    }

    // 4) открытие новой позиции по цене открытия текущей свечи
    position = openPosition(signal, current.openPrice)
    cash -= position.entryPrice * position.quantity + commission
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
val sl = if (isBuy) fill * (1 - 0.02) else fill * (1 + 0.02) // SL: вниз для LONG, вверх для SHORT
val tp = if (isBuy) fill * (1 + 0.04) else fill * (1 - 0.04) // TP: вверх для LONG, вниз для SHORT
```

> **В реальном коде** значения `0.02`/`0.04` берутся из конфига `bt.sl-percent`/`bt.tp-percent`
> (в долях от цены входа), слайс капитала — из `bt.capital-slice` (см. 11.8.1);
> в `BacktestEngine.openPosition` используется `BigDecimal.ONE ± slPercent`.

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
        totalTrades >= 200
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
| `totalTrades` | `tradeReturns.size` | >= 200 |

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
totalTrades >= 200
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

### POST /api/v1/backtest/panel

Панельный бэктест (roadmap v2.3): прогон стратегии по нескольким тикерам за один вызов
(`PanelBacktestService`, параллельный `async`/`awaitAll`). Тикеры можно подгрузить с MOEX
(`loadHistory = true`, `historicalDataLoader.loadAndSaveAll`) или прогонять по уже сохранённой
истории. Каждый тикер инкрементирует `bt_pass_total`, результаты персистятся в `backtest_results`.

- **Request** (JSON): `tickers` (обязателен, 400 при пустом), `days`, `timeframe`, `loadHistory`,
  `initialCapital`, `slPercent`, `tpPercent`, `minBarsForSignal`. Последние шесть опциональны —
  дефолты из конфига `bt.*` (11.8.1).
- **Response 200**: `results[]` — `PanelTickerSummary` (без `equityCurve`) + `summary` —
  распределение: `tickerCount`, `passCount`, `passShare`, `avgTotalReturn`, `medianTotalReturn`,
  `minTotalReturn`, `maxTotalReturn`, `totalTrades`.
- **Метрика**: `api.backtest.panel` counter.
- **UI**: `BacktestPage.tsx` — таблица распределения по тикерам со статусом PASS/REJECT.

```bash
curl -X POST "http://localhost:8080/api/v1/backtest/panel" \
  -H "Content-Type: application/json" \
  -d '{"tickers":["SBER","GAZP"],"days":365}'
```

### GET /api/v1/backtest/{ticker}/robustness?days=365

Анализ устойчивости бэктеста (roadmap 13.7.8): Monte Carlo bootstrap по фактическим сделкам
+ стресс-перепрогоны с увеличенными издержками. Дополняет walk-forward (`/validate`) оценкой
«хрупкости» доходности к порядку сделок и росту комиссии/проскальзывания.

- **Query**: `days` (default `bt.days`), `loadHistory`, `timeframe`, `simulations`
  (default `bt.monte-carlo-simulations` = 1000), `seed` (default `bt.monte-carlo-seed` = 42).
- **Метрика**: `api.backtest.robustness` counter, тег `ticker`.
- **Response 200**: `base` (метрики базового прогона + `passable`), `monteCarlo`
  (распределение путей: `medianReturn`, `p5Return`, `p95Return`, `avgReturn`, `minReturn`,
  `maxReturn`, `probabilityOfLoss`, `mcRobust`), `stress[]` (5 сценариев: `commission_x2`,
  `commission_x5`, `slippage_x2`, `slippage_x5`, `combined_stress` — каждый со своими
  множителями и метриками), `robust` — сводный вердикт.

```bash
curl "http://localhost:8080/api/v1/backtest/SBER/robustness?days=365&simulations=500"
```

Пример ответа:

```json
{
  "ticker": "SBER",
  "simulations": 500,
  "robust": true,
  "base": {"name": "base", "totalReturn": 0.1234, "sharpeRatio": 1.41, "maxDrawdown": 0.081, "profitFactor": 1.87, "totalTrades": 152, "passable": true},
  "monteCarlo": {"medianReturn": 0.1100, "p5Return": 0.0210, "p95Return": 0.2100, "avgReturn": 0.1100, "minReturn": -0.0310, "maxReturn": 0.3000, "probabilityOfLoss": 0.02, "mcRobust": true},
  "stress": [
    {"name": "commission_x2", "description": "Комиссия ×2", "commissionMultiplier": 2.0, "slippageMultiplier": 1.0, "totalReturn": 0.1050, "passable": true},
    {"name": "combined_stress", "description": "Комиссия ×3 + проскальзывание ×3", "commissionMultiplier": 3.0, "slippageMultiplier": 3.0, "totalReturn": 0.0620, "passable": true}
  ]
}
```

Если Monte Carlo неустойчив (`p5Return <= 0` или `probabilityOfLoss >= 0.25`) или любой
стресс-сценарий роняет `passable` — `robust: false`: доходность скорее зависит от удачного
порядка сделок / низких издержек, чем от преимущества стратегии.

## 11.7. Тесты

Файл `src/test/kotlin/com/trading/bot/backtest/BacktestEngineTest.kt` — 12 тестов (нет Spring-контекста, чистые unit-тесты):

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
| `backtest config exposes default values` | `BacktestConfig()` дефолты совпадают с `bt.*` (100000/365/MINUTE_10/30/2.0/4.0/0.20) |
| `initial capital from config scales equity proportionally` | `bt.initial-capital` 200000 масштабирует эквити ~2x |
| `capital slice from config changes position size` | `bt.capital-slice` 0.40 масштабирует P&L vs 0.20 |
| `ml filter blocks all entries when enabled and model rejects` | `bt.ml-filter-enabled` + вероятность < порога → 0 сделок, `bt_ml_blocked_total` растёт |
| `ml filter pass-through keeps trades when model allows` | вероятность ≥ порога → сделки не блокируются |
| `ml filter is not consulted when bt flag disabled` | при `bt.ml-filter-enabled=false` фильтр не вызывается |
| `mtf filter blocks opposing entries when enabled` | `bt.mtf-filter-enabled` + тренд/нехватка баров старшего ТФ → 0 сделок, `bt_mtf_blocked_total` растёт |
| `mtf filter pass-through keeps trades when filter allows` | фильтр пропускает входы → сделки есть |
| `mtf filter is not consulted when bt flag disabled` | при `bt.mtf-filter-enabled=false` фильтр не вызывается |
| `execution costs are parameterizable for stress runs` | параметризация ставок комиссии/проскальзывания (стресс, 13.7.8) |
| `stress multipliers degrade backtest equity` | ×5 комиссии/проскальзывания снижают эквити, число сделок не меняется |

`MonteCarloAnalyzerTest` — детерминизм Monte Carlo по seed, квантили (P5/медиана/P95), доля убыточных путей, отображение стресс-сценариев.
`PanelBacktestSummarizerTest` — 3 теста агрегации распределения (пустой список, доля PASS + средняя доходность, медиана при чётном количестве).

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
| `bt.ml-filter-enabled` | `BT_ML_FILTER_ENABLED` | `false` | ML-фильтр входа в бэктесте (раздел 13.11.6); не влияет на live-гейт |
| `bt.mtf-filter-enabled` | `BT_MTF_FILTER_ENABLED` | `false` | multi-timeframe фильтр тренда в бэктесте (раздел 13.12.1); не влияет на live-гейт |
| `bt.monte-carlo-simulations` | `BT_MONTE_CARLO_SIMULATIONS` | `1000` | число bootstrap-симуляций Monte Carlo (раздел 13.7.8) |
| `bt.monte-carlo-seed` | `BT_MONTE_CARLO_SEED` | `42` | seed генератора Monte Carlo (воспроизводимость) |

Значения применяются в `BacktestEngine.run/simulate` (дефолты параметров),
`BacktestValidator.validate` (initialCapital, minBarsForSignal) и в эндпоинтах
`ApiController` (дефолты `days`/`timeframe`).

### Конфигурация `bt.agent.*` (реализовано, раздел 13.8.1)

Агентный режим генерации сигналов выключается/включается конфигом `bt.agent.*`
(`BacktestAgentConfig`, `@ConfigurationProperties(prefix = "bt.agent")`). Режимы
выбираются условно через `@ConditionalOnProperty` — агентный компонент
`AgentBacktestSignalGenerator` существует только при `bt.agent.enabled=true`,
иначе создаётся `DeterministicBacktestSignalGenerator` (индикаторный RSI/MACD):

| Ключ | Env | По умолчанию | Назначение |
|---|---|---|---|
| `bt.agent.enabled` | `BT_AGENT_ENABLED` | `false` | агентный конвейер (false = индикаторный режим) |
| `bt.agent.sample-every` | `BT_AGENT_SAMPLE_EVERY` | `20` | сигнал каждые N баров (warm-up первые `bt.min-bars-for-signal` баров — HOLD) |
| `bt.agent.temperature` | `BT_AGENT_TEMPERATURE` | `0.0` | температура LLM (детерминированность) |
| `bt.agent.cache-namespace` | `BT_AGENT_CACHE_NAMESPACE` | `backtest` | изоляция semantic cache от live-кэша |
| `bt.agent.confidence-threshold` | `BT_AGENT_CONFIDENCE_THRESHOLD` | `0.60` | единый порог уверенности стратега и арбитра = live-fallback без статистики (`AdaptiveRiskService` при `stats == null`); адаптивный порог live в бэктесте не вычисляется — нет истории сделок |

Профиль `backtest` (`application-backtest.yml`) задаёт `bt.agent.enabled=true` —
это же значение принимает `KIMI_API_KEY`: если ключ пуст, все агенты мгновенно
дают детерминированные fallback (INSUFFICIENT_DATA/NEUTRAL/HOLD), бэктест не падает.

### ML-фильтр входа в бэктесте (реализовано, раздел 13.11.6)

При `bt.ml-filter-enabled=true` (`BT_ML_FILTER_ENABLED`) `BacktestEngine` гейтит
входы тем же `MlEntryFilter`, что и live (`DecisionEngine`), для консистентности
live/бэктест. Признаки строятся на момент бара (`at = current.time`),
`strategy_confidence=null` (детерминированный генератор не даёт уверенности).
Глобальные флаги `ml.enabled`/`ml.filter.enabled` при этом игнорируются
(изолированный прогон), но модель должна быть доступна: при `ml.enabled=false`
или отсутствующем файле входы блокируются (fail-closed). Блокировки считаются в
метрику `bt_ml_blocked_total{ticker}` и не создают сделок (пустой прогон = 0
сделок). Если включён тренд-гейт входа (`ml.filter.trend-gate-enabled=true`,
раздел 13.11.7), он применяется и к бэктесту: вход дополнительно требует
`trendScore >= ml.filter.trend-min-score`.

### Multi-timeframe фильтр в бэктесте (реализовано, раздел 13.12.1)

При `bt.mtf-filter-enabled=true` (`BT_MTF_FILTER_ENABLED`) `BacktestEngine` гейтит
входы тем же `HigherTfTrendFilter`, что и live (`DecisionEngine`), для
консистентности live/бэктест. Старший ТФ (по умолчанию `HOUR_1`) строится
ресемплингом (`CandleResampler`) 10-минутных свечей, завершённых к моменту бара
(`subList(0, i)` + `completedBefore = bar.time`) — без lookahead. Флаг
`mtf.filter.enabled` при этом игнорируется (изолированный прогон); тренд
вычисляется по данным фикстуры, при нехватке баров старшего ТФ входы
блокируются (fail-closed). Блокировки считаются в метрику
`bt_mtf_blocked_total{ticker}`; инверсия с заблокированным встречным входом
закрывает позицию как `MTF_FILTER_REVERSAL` (аналог ML-фильтра).

### Поток агентного сигнала (11.8.2)

`AgentBacktestSignalGenerator.signal(ticker, candles, index, minBars, cycleId)`:
warm-up (index < minBars) и несэмплируемые бары возвращают `HOLD` без вызовов агентов;
на сэмплируемом баре `TechnicalAnalysisAgent` и `FundamentalAnalysisAgent` считаются
параллельно (`coroutineScope`/`async`), затем `StrategyAgent` → `ContrarianAgent` →
`ArbitratorAgent` — конвейер возвращает `Final` (аналог `DiscretionaryStrategy`).
Все агенты вызываются с `temperature=bt.agent.temperature` и
`cacheNamespace=bt.agent.cache-namespace` (изолированный кэш — защита от look-ahead
и загрязнения live-кэша). Метрики: `backtest.agent.evaluations` (Counter, tag
`agent`) и `backtest.agent.signal` (Counter, tag `signal`). Обвязка движка:
`BacktestEngine.simulate`/`BacktestValidator.validate` стали suspend, цикл
использует `signalGenerator.signal(...)` вместо детерминированного `signalAt()`.

Честный список того, чего в текущей реализации **нет** (важно не путать с дизайном из исходного раздела):

1. ~~**Нет LLM-агентов**~~ — реализовано: конвейер tech→fund→strategy→contrarian→arbitrator
   в агентном режиме `bt.agent.enabled=true` (11.8.1), по умолчанию — индикаторный режим.
2. **Нет `avgHoldBars` и `monthlyReturns`** — заглушки, расчёт по месяцам отложен.
3. **Out-of-sample — детерминированно** — OOS покрыт walk-forward `BacktestValidator` (`/api/v1/backtest/{ticker}/validate`, раздел 13.7.7); LLM/ML-подход — roadmap.
4. **Нет отдельного Spring-профиля `backtest`** — профиль-конфиг `application-backtest.yml`
   создан (включает `bt.agent.enabled=true`, 11.8.1), но автозапуска по `--bt.tickers`
   (отдельный `BacktestApplication` с отчётом в консоль) ещё нет. Запуск — через REST.
   Персист результатов в `backtest_results` реализован (разделы 13.7.3, 7.2).
5. ~~**Нет распределения по тикерам**~~ — реализовано: панельный прогон `/api/v1/backtest/panel` (11.6.1). Один вызов = несколько тикеров с итоговой сводкой. Фронт: `BacktestPage.tsx`.
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
