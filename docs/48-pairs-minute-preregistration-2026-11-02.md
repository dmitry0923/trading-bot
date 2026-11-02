# Предрегистрация: pairs CNYRUBF–USDRUBF на MINUTE_10 2024–2026 (2026-11-02)

**Статус:** зафиксировано **ДО** прогона. Параметры не меняются.

**Зачем:** DAY_1-версия пары (`docs/47`) дала всего 14 сделок за 4,5 года
(OOS 4 сделки, PF 0,32) — выборка слишком тонкая, вердикт `INCONCLUSIVE`, а
не доказательство отсутствия эффекта. MINUTE_10 даёт ~500 сделок на 730д и
позволяет проверить ту же гипотезу с достаточной мощностью. Это **другой
таймфрейм**, не повторный тест DAY_1 OOS.

> Research-эндпоинт `/api/v1/pairs/*`; live не затрагивается. Для LIVE нужна
> вторая нога (USDRUBF), сейчас allowlist CNYRUBF-only.

## 1. Данные и параметры (frozen)

| Параметр | Значение |
|---|---|
| Ноги | `CNYRUBF` / `USDRUBF` |
| ТФ | `MINUTE_10`, 730д (2024-10-24…2026-10-30) |
| Параметры | `lookbackBars=240`, `entryZ=2.0`, `exitZ=0.5`, `stopZ=4.0`, `maxHoldBars=0` |
| Капитал | 100 000 ₽, `capitalSlice=0.50` |
| Издержки | комиссия 1,5 ₽/контракт/сторона, slippage 1 bp, funding по факту |

Параметры — те же, что в `docs/47`/`docs/25`; не подбираются.

## 2. Гейты

### 2.1 OOS (`/pairs/backtest`, `oosFraction=0.30`)

P1 OOS сделок ≥ 30; P2 OOS PF ≥ 1,30; P3 OOS P(noEdge) < 0,05;
P4 OOS CI95 не содержит 0; P5 OOS MDD ≤ 40 %.

### 2.2 WFA (`/pairs/validate`, `folds=6`)

consistency ≥ 0,60; aggregate OOS PF ≥ 1,30; OOS сделок ≥ 30; robust=true.

PASS = все; FAIL = P2/P5 или WFA PF < 1,30; иначе INCONCLUSIVE.

## 3. Анти-подбор

1. Пара/параметры/ТФ фиксированы.
2. OOS 2024–2026 запускается один раз.
3. Отрицательный результат закрывает пару CNYRUBF/USDRUBF на MINUTE_10.
4. Новые пары/пороги на этом OOS запрещены.

## 4. Артефакты

- `scripts/research_pairs_cnyusd.ps1` (параметризован `-Timeframe MINUTE_10 -Folds 6 -Days 730`)
- `artifacts/pairs_cnyusd_minute_2026-11-02_backtest.json`
- `artifacts/pairs_cnyusd_minute_2026-11-02_wfa.json`

## 5. Результаты

Прогон: 2026-11-02, `scripts/research_pairs_cnyusd.ps1 -Timeframe MINUTE_10
-Folds 6 -Days 730`, `artifacts/pairs_cnyusd_minute_2026-11-02_{backtest,wfa}.json`.
43 653 бара, `spreadCorr=0,864`, `meanBeta=0,952`.

### 5.1 Backtest IS70/OOS30

| Метрика | Full | IS | OOS |
|---|---:|---:|---:|
| Сделок | 546 | 397 | **149** |
| PF | 0,621 | 0,643 | **0,505** |
| P(noEdge) | — | — | **0,9865** |
| CI95 средней | — | — | **[−50,13; −2,86] ₽** |
| MDD | — | — | 4,88 % |

Вердикт эндпоинта: **`REJECTED (oos PF 0,505)`**.

### 5.2 WFA folds=6

535 OOS-сделок, aggregate PF **0,625**, consistency **0,167**, robust=false,
вердикт **`REJECTED (consistency 0,167)`**.

### 5.3 Вывод

На MINUTE_10 пара даёт достаточную выборку (149 OOS-сделок) и **однозначно
убыточна**: PF 0,505, P(noEdge)=0,9865, CI95 средней сделки **целиком
отрицательный** [−50,13; −2,86] ₽. WFA подтверждает (PF 0,625,
consistency 0,167). Гипотеза relative-value CNY/USD **не подтверждается**;
класс пары CNYRUBF/USDRUBF закрыт на DAY_1 (INCONCLUSIVE) и MINUTE_10
(REJECTED). OOS 2024–2026 израсходован; новые пары/пороги на нём запрещены.
