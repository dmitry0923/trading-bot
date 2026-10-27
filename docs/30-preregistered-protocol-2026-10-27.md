# Предрегистрация: протокол IS 70 % / OOS 30 % на `engineVersion 2026-10-26.2`

**Статус:** зафиксирован **ДО** прогона (2026-10-27). Правки после получения чисел запрещены —
изменение любого пункта этого файла означает новый протокол и новый holdout.

**Контекст:** `docs/29` §4 — INVALID-список утверждён; единственный валидный количественный
результат — новый baseline `artifacts/baseline_engine_2026-10-26_2.json`
(CNYRUBF 730д, `maxC=1`: Return **+0,026 %**, PF **1,012**, P(noEdge) **0,486**, 82 сделки,
`passable=false`). Все прочие research-числа не используются.

---

## 1. Данные и движок (фиксированы)

| Параметр | Значение |
|---|---|
| Тикер / ТФ | `CNYRUBF` / `MINUTE_10` |
| История | `days=730`, 56 104 свечи (2024-10-24 … 2026-10-27) |
| Funding | `funding_history` (SWAPRATE MOEX, per-clearing) |
| `engineVersion` | **2026-10-26.2** |
| Издержки | комиссия 1,0 + 0,5 ₽/контракт/сторона, slippage 1,0 bp, funding по факту |
| Ликвидация | `bt.futures-liquidation-simulation=true` |

## 2. Кандидаты (заморожены, подбор НЕ выполняется)

**C1 — `live_like_reference`** (референс live-сайзинга; контроль, а не кандидат на LIVE):

```
riskPerTradePercent=5 & futuresMaxContractsPerPosition=1
все research-фильтры off (maxHold=0, fundingVeto=false, timeDirection=false, ML=false)
```

**C2 — `combo730_frozen`** (лидер combo730 из AGENTS, перенесён 1:1, БЕЗ донастройки):

```
riskPerTradePercent=30 & futuresMaxContractsPerPosition=100     # калибровочный риск-профиль
maxHoldBars=368
fundingVetoEnabled=true & fundingVetoLongThresholdRub=6 & fundingVetoShortThresholdRub=6
timeDirectionEnabled=true & timeDirectionLongBlockUntilHour=9
mlDirectionEnabled=false
```

Обоснование выбора C2: это единственная комбинация, которая на **полной** 730д истории давала
OOS PF 1,65 на dev-части (docs/28 §combo730-gate) — проверяем её ещё раз на чистом
IS70/OOS30-разрезании, где holdout не участвует ни в каком подборе.
Набор кандидатов **не расширяется** при неудаче.

## 3. Метод

Инструмент: `scripts/research_oos70.ps1` (без `-RunGate`).

```
GET /api/v1/backtest/CNYRUBF/holdout
    ?days=730&folds=8&holdoutFraction=0.30&adaptiveConfidenceThreshold=0.60
    &riskPerTradePercent=<Cx>&futuresMaxContractsPerPosition=<Cx>[&<фильтры C2>]
```

- dev = первые 70 % истории (≈511 торговых дней), WFA `folds=8` считается **только** на dev;
- OOS = последние 30 % (≈219 дней), в подборе и в WFA **не участвует**;
- по каждому кандидату — **один** прогон; повтор допускается только при инфраструктурном
  сбое (5xx/таймаут), с тем же URL.

## 4. Гейты (обязательны ВСЕ)

| # | Гейт | Порог | Источник значения |
|---|---|---|---|
| G1 | OOS Profit Factor | **≥ 1,30** | `holdout.profitFactor` |
| G2 | OOS сделок | **≥ 30** | `holdout.totalTrades` |
| G3 | OOS P(noEdge) | **< 0,05** | `holdout.probabilityOfNoEdge` |
| G4 | OOS CI95 средней сделки | **не содержит 0** | `holdout.meanTradeCI95Low/High` |
| G5 | Dev-consistency (WFA на dev) | **≥ 0,60** | `wfaConsistency` |
| G6 | OOS Max Drawdown | **≤ 40 %** | `holdout.maxDrawdown` |

## 5. Правило вердикта (без интерпретаций)

- **PASS (research)** — только если пройдены G1–G6 **все**;
- **REJECTED** — если `G1` не выполнен (PF < 1,30) или `G6` не выполнен;
- **INCONCLUSIVE** — G1/G6 выполнены, но не выполнен хотя бы один из G2–G5
  (в первую очередь «мало сделок» — это ограничение выборки, а не доказательство edge);
- **INVALID (infra)** — прогон прервался по инфраструктуре; повтор с тем же URL.

`PASS` не означает допуск в LIVE: live-допуск — отдельный `deployment-gate` с
`researchMode=false` + `confirmedForProduction=true` и одобрением оператора.

## 6. Анти-подбор (обязательства)

1. Никаких сеток/вариаций порогов, `folds`, `days` или `holdoutFraction` после получения чисел.
2. Никакого повторного использования этого holdout для других кандидатов: 30 % истории
   «сгорают» после прогона и для новых гипотез нужен новый горизонт данных (или новые данные).
3. Результат публикуется **любым** (включая отрицательный) и заносится в §7 этого файла.
4. Если C2 не проходит — класс «детерминированная база + фильтры (funding/maxHold/time)»
   закрывается как не подтвердивший edge; новые фильтры на этом же holdout не проверяются.
5. Склейка фьючерсных контрактов (колонка `contract` в `candles`) в этом прогоне
   **не участвует** — Si/RI не входят в кандидатов.

## 7. Результаты

_(заполняется после прогона, без правки §1–§6)_
