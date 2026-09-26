# Аудит 10 стратегий (пользовательский список, 2026-09-25)

Вход: список из 10 стратегий «классический квант + LLM-агенты» с требованием
протестировать их на 2-летней истории через WFA. Ниже — проверка реализуемости
**на текущем стеке и текущих данных репозитория** (не план, а факт-аудит).

## Что реально есть в данных (проверено 2026-09-25, `candles` MINUTE_10)

| Тикер | Свечей | Период | Годится на 2 года WFA |
|---|---|---|---|
| GAZP, SBER, NVTK, PLZL | 56k–57k | 2024-09-19 … 2026-09-24 | да (≈2 года) |
| CNYRUBF | 46 563 | 2024-09-19 … 2026-09-24 | да |
| USDRUBF, EURRUBF, GLDRUBF, IMOEXF | 43k–50k | 2024-09-23 … 2026-09-22 | да (перпетуалы) |
| MGNT, LKOH, ROSN, TATN | 8.3k–8.9k | 2026-06-11 … | нет (3 мес) |
| VTBR | 1 965 | 2026-09-01 … | нет (3 нед) |
| **Si, RI, RIU6** | 5.7k–6.2k | 2026-06-11 … 2026-08-28 | **нет (2.5 мес)** |
| RIZ6 / RIM7 | 2 / 1 | — | нет (мусор от склейки контрактов) |

`funding_history`: CNYRUBF, USDRUBF, EURRUBF, GLDRUBF, IMOEXF (508–509 дат SWAPRATE).

В БД **нет** таблиц стакана, тиков и сделок-по-всем-тикам: только `candles`
(OHLCV), `macro_snapshots`, `agent_logs`, `trade_events` (собственные сделки бота).
Вывод: любая стратегия, требующая микроструктуры (Order Book Imbalance, VPIN),
структурно не тестируется без нового слоя загрузки и хранения данных.

Инструментов в `InstrumentsConfig`: Si, GAZP, VTBR, CNYRUBF, USDRUBF, GLDRUBF,
IMOEXF. **Нет** SBERP (префы), BR (Brent), RGBI (ОФЗ-индекс), GD/RTS-имён.

## Карта стратегий

| № | Стратегия | Детерминированное ядро | Данные | Вердикт |
|---|---|---|---|---|
| 1 | Интрадей MR CNYRUBF от VWAP (1.5 StdDev + ADX<25) | сессионный VWAP по OHLCV, StdDev, ADX(1ч) | есть, 2 года | **реализуемо** — новая логика входа, чистый research-фильтр |
| 2 | Календарный спред Si (near/far, roll yield) | требует пары контрактов (Si-3.26/Si-6.26) + RUONIA | **нет**: Si только 2.5 мес, пары нет, RUONIA нет | **нереализуемо** без донакачки истории и ставки RUONIA |
| 3 | Скальпинг утреннего аукциона (стакан, OBI) | Order Book Imbalance из стакана | **нет стакана** | **нереализуемо** (нужен новый data-слой) |
| 4 | Overnight-гэп SBER (вход 18:38, выход на открытии) | session-фильтр входа + удержание через max-hold | есть, 2 года | **частично реализуемо**: вход по сессии есть, межсессионное удержание — эмулируется `maxHoldBars`; честный overnight требует доработки движка позиций |
| 5 | StatArb SBER/SBERP (Z-score 20д) | Z-score пары | **нет SBERP** | **нереализуемо** без донакачки SBERP |
| 6 | Вечерний импульс Brent (19:00–23:50, 2ч-пробой) | breakout 2ч + session-фильтр | **нет BR** | **нереализуемо** без донакачки BR; аналог на GLDRUBF/USDRUBF возможен |
| 7 | ORB на золоте (15:30–16:00 МСК, пробой на открытии США) | ORB-фильтр уже реализован (`bt.orb-*`) | GLDRUBF есть, 2 года | **реализуемо сразу** — ORB на вечернем окне на GLDRUBF (нужен `orbWindowStartMinutes`, окно сейчас «с начала дня») |
| 8 | Обеденный MR на RI (13:00–15:00, канал Кельтнера) | канал Кельтнера + session-фильтр | RI только 2.5 мес | **ограниченно**: ядро реализуемо, но 2.5 мес ≠ 2 года (в AGENTS.md уже помечен RI как нестабильный/исключён) |
| 9 | VPIN на Si (токсичность потока) | VPIN по тикам/всем сделкам | **нет тиков/стакана** | **нереализуемо** (нужен новый data-слой) |
| 10 | Flight-to-quality Mix/RGBI (корреляция) | корреляция двух серий, порог −1.5%/час | IMOEXF есть, **RGBI нет** | **нереализуемо** в корректном виде: вторая нога отсутствует. Одноголая эмуляция (шорт IMOEXF) — другой тест, не то же самое |

**Итог: из 10 стратегий полноценно тестируются на текущих данных 2 (№1 и №7),
частично — 2 (№4, №8). Остальные 6 упираются в отсутствие данных** (SBERP, BR,
RGBI, история Si/RI, стакан/тики) либо в новый data-слой.

## Что нужно добавить в код для тестируемых ядер (паттерн уже есть в репо)

Все исследовательские входные фильтры сделаны по одному шаблону (см. `docs/16`,
`docs/18`): параметр в `BacktestConfig` (`bt.*`, env `BT_*`) → query-override в
`ApiController.buildSignalGenerator` → проверка в `LiveStrategyBacktestSignalGenerator`
после confidence-gate → тесты. Новые фильтры не трогают live-путь (он идёт через
`DecisionEngine`) и по умолчанию выключены.

## Реализовано в коде (2026-09-26)

Обе реализуемые стратегии реализованы по общему шаблону research-фильтров
(`BacktestConfig` → query-override → `EntryFilters` → тесты), **дефолт off**,
live-путь не затронут.

### №7 ORB на золоте — окно диапазона (commit `8139342`)

`EntryFilters.orbDirection(candles, index)` получил окно диапазона:
- `bt.orb-window-start-minutes` / `bt.orb-window-end-minutes` (query: `orbWindowStartMinutes`,
  `orbWindowEndMinutes`) — минуты от полуночи;
- диапазон = первые `orbWindowBars` баров текущего дня, начиная с первого бара
  с временем ≥ `orbWindowStartMinutes`;
- проверка пробоя — только на барах с временем ≥ `orbWindowEndMinutes`
  (для суженного окна). Дефолт `0..1440` = исходное поведение (диапазон = первые
  бары дня, проверка весь день) — обратная совместимость;
- до начала окна и внутри окна (диапазон не закрыт) — пропуск либо HOLD при
  `orbBlockOnUnknown` (fail-closed);
- настройка под стратегию: `orbWindowStartMinutes=930`, `orbWindowEndMinutes=960`,
  `orbWindowBars=3` (MINUTE_10 → диапазон 15:30–16:00 МСК, вход на пробое после 16:00).

Тесты (`EntryFiltersTest`, +5 кейсов): до окна / внутри окна / после окна,
пробой вверх и вниз, strict vs loose, fail-closed, день, начинающийся после
начала окна, диаграмма длиннее окна, дефолтное поведение.

### №1 VWAP-MR (commit `1af7944`)

`IndicatorCalculator`:
- `vwap(candles)` — сессионный VWAP по типичной цене с **сбросом по дате**
  (типичная цена = (H+L+C)/3, объёмные веса);
- `vwapStdDevPercent(candles)` — σ типичной цены в % от VWAP;
- `adx(candles, period)` — ADX по Уайлдеру (для трендового фильтра);
- `MIN_MEANINGFUL_VWAP_SIGMA_PERCENT = 1e-6` — **найденный баг**: на полностью
  плоской сессии σ ≈ 1e-14 (float-шум), деление отклонения на такую σ давало
  ложные отклонения в тысячи σ и ложные BUY/SELL на плоском рынке. Регрессионный
  тест `flat session sigma is float noise...`.

`EntryFilters.vwapMrDirection(session, higherTimeframeCandles)`:
- вход только при отклонении |close − VWAP| ≥ `vwapMrDeviationSigma`·σ **и**
  ADX(`vwapMrTimeframe`, по умолчанию HOUR_1) ≤ `vwapMrMaxAdx`;
- close ниже VWAP на Nσ → BUY (возврат вверх), выше → SELL;
- отклонение < Nσ → HOLD; ADX выше порога → HOLD (MR в тренде не работает);
- сессия не набрала `vwapMrMinSessionBars` либо σ ≈ 0 → HOLD при
  `vwapMrBlockOnUnknown` (fail-closed), иначе пропуск;
- ADX старшего ТФ считается через `CandleResampler.resample(..., completedBefore = bar.time)` —
  **без lookahead** (используются только завершённые часовые бары);
- query-override: `vwapMrEnabled`, `vwapMrDeviationSigma`, `vwapMrMaxAdx`,
  `vwapMrTimeframe`, `vwapMrMinSessionBars`, `vwapMrBlockOnUnknown` на 5 эндпоинтах.

Дефолты: `vwapMrEnabled=false`, `vwapMrDeviationSigma=1.5`, `vwapMrMaxAdx=25.0`,
`vwapMrTimeframe=HOUR_1`, `vwapMrMinSessionBars=6`, `vwapMrBlockOnUnknown=true`.

Тесты: `IndicatorCalculatorVwapAdxTest` (11 кейсов: VWAP-веса, сброс сессии,
σ, ADX на тренде/боковике, диапазон, регрессия float-шума) + 7 кейсов
`vwapMr*` в `EntryFiltersTest`. Итого 36 тестов, `test` + `ktlintCheck` зелёные.

**Что ещё не сделано для №1**: выход «возврат к VWAP» — движок позиций не имеет
такого exit-типа; на текущем прогоне выход моделируется SL/TP grid бэктеста.
Это отдельная задача и отдельное исследование (см. ниже).

Требование пользователя «в 5% случаев симулировать таймаут LLM» — **отдельный
нереализованный флаг**: сейчас есть только реальный таймаут
`trading.llm-signal-budget-ms` → fail-closed HOLD. Инъекция искусственных таймаутов
в бэктест требует нового кода (`bt.agent.timeout-injection-rate`).

LLM-роли ( veto по свопам/РЕПО, календарь США, корпоративные новости) в списке
описывают внешние источники, которых в проекте нет: новости приходят только через
`RgRuNewsProvider` (платная подпилка, `news.enabled`, дефолт выключен), макро —
`macro_snapshots`. Парсинг сайта ЦБ и календаря EIA/CPI не реализован.
