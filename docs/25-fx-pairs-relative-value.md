# FX-pairs relative-value (market-neutral) — план и реализация

Статус: **research-инструмент** (default off). Live-путь, LIVE-guard и
`max-contracts-per-position=1` не затрагиваются.

## 1. Мотивация

PLAN-001 (≥100%/год на CNYRUBF) не достигнут: направленная детерминированная
стратегия на полной 730д истории OOS убыточна (PF 0.92), все входные фильтры
(ML/funding-veto/session/pullback/ORB/time-direction/max-hold) и LLM-путь
отбракованы (декомпозиция — AGENTS.md). Единственный не измеренный класс —
**relative-value / stat-arb**: торгуется не направление, а расхождение между
двумя высококоррелированными инструментами. Позиция market-neutral к рублю,
поэтому источник P&L — возврат спреда, а не угадывание курса.

## 2. Почему это работает на доступных данных (эмпирика из БД)

Доступны только свечи (bid/ask/тиков/стакана нет) → OBI-класс закрыт
(docs/22/23). Но пары хорошо коррлированы и спред реверсирует:

| Пара (HOUR_1) | corr доходностей | corr (DAY_1) |
|---|---|---|
| CNYRUBF ↔ USDRUBF | 0.898 | 0.942 |
| CNYRUBF ↔ EURRUBF | 0.825 | 0.888 |
| USDRUBF ↔ EURRUBF | 0.844 | 0.888 |
| GLDRUBF ↔ FX | 0.48–0.55 | — |
| IMOEXF ↔ FX | ≈0 | — |

Реверсия спреда устойчива по всем годам (не одиночный режим):

- CNYRUBF/USDRUBF, MINUTE_10, z-окно 240 бар, форвард 100 мин:
  `z≥2` → −0.113%, `z≤−2` → +0.128% (n 2790 / 2297).
- CNY vs корзина (USD·EUR)^0.5, HOUR_1, форвард 6ч:
  `z≥2` → −0.172%, `z≤−2` → +0.167% (n 1405 / 1283).
- По годам (CNY/USD): 2024 −0.24%/+0.05%, 2025 −0.09%/+0.11%, 2026 −0.14%/+0.21%.
- AR(1) дневного ratio β=0.9978, r2=0.9908 (медленный возврат).
- Частота |z|≥2 ≈ **4.38 входа/день** → ≥100 OOS-сделок набирается ~за месяц.

## 3. Экономика и издержки

- Нотионал/контракт: CNY ≈11.6k ₽, USD ≈81.5k ₽, EUR ≈93k ₽.
- Хедж нотионал-матч (≈ beta·notional): **≈ 7 CNY : 1 USD**.
- ГО: 7×850 (CNY) + 12 630 (USD) ≈ **18.6k ₽** на ~162k ₽ нотионала (~8.7×).
- Издержки круговой пары ≈ **5–7 bps** (комиссия 1.0+0.5 ₽/контракт/сторона +
  слиппедж 1 bp ×2 ноги), funding — per-clearing из `funding_history`.
- Gross-сигнал 11–17 bps → net потенциально 5–10 bps/сделку.

**Риски (честно):** несинхронные котировки ног (ложная реверсия от bid-ask
bounce при отсутствии bid/ask), слиппедж на z-экстремумах (часто на новостях),
неделимость контрактов (ошибка хеджа), общая рублёвая ликвидность ног и funding.
100%/год не гарантируется — решит честный OOS (IS70/OOS30).

## 4. Архитектура (изолированно, core не трогаем)

Новый пакет `com.trading.bot.backtest.pairs`:

- `PairsBacktestEngine` — **чистая** функция (без Spring/БД): на входе
  выровненные по времени бары двух ног + `LegSpec` двух инструментов + funding-ряды,
  на выходе — список `PairTrade`, кривая капитала, диагностика (corr, mean beta).
  Легко юнит-тестируется.
- `PairsBacktestService` — загрузка свечей (`CandleRepository`), funding
  (`FundingHistoryRepository`), спека (`InstrumentsConfig`), календарь
  (`MoexHolidayCalendar`), inner-join по времени, IS/OOS split, метрики
  (`BacktestMetrics.compute`).
- `PairsBacktestController` — `GET /api/v1/pairs/backtest` (GET → достаточно
  `authenticated`, нового permit не требуется).
- `PairsBacktestConfig` — prefix `bt.pairs`, default off.

**`BacktestEngine`/`BacktestConfig`/live-контур НЕ меняются.**

## 5. Алгоритм движка

1. Свечи обеих ног inner-join`ятся по `time`; бар i виден только целиком
   (close известен в момент закрытия бара).
2. `la=ln(closeA)`, `lb=ln(closeB)`.
3. На баре i по окну `[i-lookbackBars+1 .. i]` rolling OLS:
   `beta_i = cov(la,lb)/var(lb)`, затем `spread_j = la_j − beta_i·lb_j`,
   `mean`, `sd` по тому же окну, `z_i = (la_i − beta_i·lb_i − mean)/sd`.
   Всё на данных ≤ i (lookahead закрыт).
4. Сигнал на закрытии бара i, **исполнение на открытии i+1**:
   - flat: `z≥entryZ` → short spread (short A / long B);
     `z≤−entryZ` → long spread (long A / short B); требуется `beta > betaMin`.
   - в позиции: `|z|≥stopZ` → `STOP`; `|z|≤exitZ` → `CONVERGED`;
     `maxHoldBars>0 && hold≥maxHoldBars` → `MAX_HOLD`.
5. Сайзинг: anchor — нога с большим нотионалом/контракт, `qty=round(target/notional)`
   (≥1); вторая нога масштабируется `qtyA = beta·qtyB·notionalB/notionalA` (≥1).
   `target = capitalSlice·initialCapital`.
6. P&L: обе ноги MtM по slipped open-ценам; `commission = Σ|qty|·commSide·2`;
   `funding = Σ_clearing (signA·qtyA·rateA + signB·qtyB·rateB)` по
   `FundingCosts.clearingDates` с календарём; `netPnl = gross − commission − funding`.
7. Кривая капитала: `equity = initialCapital + realizedNet + unrealizedMtM`,
   точка на каждом баре. Метрики — `BacktestMetrics.compute(isFutures=true)`.

## 6. IS/OOS протокол (честная валидация)

Свечи делятся по времени: первые `1−oosFraction` — in-sample, последние
`oosFraction` — out-of-sample. Метрики считаются на обоих отрезках. Вердикт —
**только по OOS** по тем же порогам, что `BacktestResult.isPassable()` /
docs `research_oos70.ps1`:

- `OOS PF < 1.3` → **REJECTED**;
- OOS-сделок `< 30` → **INCONCLUSIVE**;
- `OOS PF ≥ 1.3` и ≥30 сделок и `P(noEdge) ≤ 0.05` → кандидат на WFA/deployment-gate.

## 7. Конфиг (`bt.pairs.*`, env `BT_PAIRS_*`)

| Ключ | Default | Смысл |
|---|---|---|
| `enabled` | false | research-флаг |
| `leg-a` / `leg-b` | CNYRUBF / USDRUBF | ноги пары |
| `lookback-bars` | 240 | окно OLS/z-score (MINUTE_10) |
| `entry-z` | 2.0 | порог входа |
| `exit-z` | 0.5 | порог возврата к среднему |
| `stop-z` | 4.0 | стоп по расширению спреда |
| `max-hold-bars` | 0 | принудительный выход (0 = off) |
| `oos-fraction` | 0.30 | доля OOS |
| `capital-slice` | 0.50 | целевой нотионал ноги / капитал |
| `initial-capital` | 100000 | стартовый капитал |
| `use-funding-history` | true | фактические SWAPRATE из `funding_history` |

Query-оверрайды на эндпоинте: `legA/legB/timeframe/days/lookbackBars/entryZ/
exitZ/stopZ/maxHoldBars/oosFraction/capitalSlice/initialCapital`.

## 8. Что НЕ сделано / следующий шаг

- Оптимизация порогов (entry/exit/stop/lookback) — только после первого
  честного OOS-замера; иначе — overfit на полной истории (урок combo730/ORB).
- Динамический ре-хедж beta внутри позиции, отдельный exit «возврат к VWAP» —
  вне скоупа.
- Расширение на корзину (CNY vs USD·EUR) — после подтверждения на одной паре.
