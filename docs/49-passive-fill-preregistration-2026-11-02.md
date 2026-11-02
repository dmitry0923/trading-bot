# Предрегистрация: A/B passive (maker) исполнения CNYRUBF (2026-11-02)

**Статус:** зафиксировано **ДО** прогона. Параметры не меняются.

**Зачем:** после pairs (`docs/47`–`docs/48`) следующий по очереди —
execution-слой (`docs/35` #7). Гипотеза: издержки съедают часть edge;
пассивный (maker) вход/выход снижает спред-кост. Проверяется **на том же
сигнале и тех же фолдах**, меняется только модель исполнения. Это не новый
сигнал и не новая сетка.

> Research-флаг `executionPassiveFill` уже есть в движке (a2, PLAN-001 §5.1).
> Live-путь не затрагивается; пассивный live-ордер не включается.

## 1. Конфигурация (frozen)

| Параметр | Значение |
|---|---|
| Тикер / ТФ | `CNYRUBF` / `MINUTE_10` |
| История | `days=730` |
| WFA | `folds=8` |
| Сайзинг | `riskPerTradePercent=1`, `futuresMaxContractsPerPosition=1` |
| Confidence | `adaptiveConfidenceThreshold=0.60` |
| Фильтры | `mlDirectionEnabled=false`, `fundingVetoEnabled=false`, `maxHoldBars=0` |
| A/B | `executionPassiveFill=false` (taker) vs `true` (maker) |

Оба прогона — `/backtest/CNYRUBF/validate` с одинаковыми параметрами; отличие
только в `executionPassiveFill`.

## 2. Гейты

Сравнение OOS-агрегата WFA:

| # | Гейт | Порог |
|---|---|---|
| E1 | Passive OOS сделок | **≥ 30** |
| E2 | Passive OOS PF | **≥ 1,30** |
| E3 | Passive OOS P(noEdge) | **< 0,05** |
| E4 | Passive OOS CI95 | **не содержит 0** |
| E5 | Passive OOS PF > taker OOS PF | **строго** |

**PASS** = E1–E5. Если passive улучшает PF, но не достигает 1,30 —
`REJECTED` (cost-слой недостаточен). Если passive хуже taker — `REJECTED`
(модель/исполнение не подтверждается).

## 3. Анти-подбор

1. Параметры сигнала/фолдов фиксированы; меняется только fill-модель.
2. Один A/B-прогон; повтор — только при инфраструктурном сбое.
3. Отрицательный результат закрывает execution-слой на текущем сигнале.
4. Никаких новых порогов/фолдов на этом же окне.

## 4. Артефакты

- `scripts/research_passive_fill.ps1` (WFA-режим)
- Full A/B (фактический экран):
  `GET /api/v1/backtest/CNYRUBF?days=730&timeframe=MINUTE_10&riskPerTradePercent=1`
  `&futuresMaxContractsPerPosition=1&adaptiveConfidenceThreshold=0.6`
  `&mlDirectionEnabled=false&fundingVetoEnabled=false&maxHoldBars=0`
  `&executionPassiveFill=false|true&includeTrades=false&loadHistory=false`
- `artifacts/passive_fill_2026-11-02_taker_full.json`
- `artifacts/passive_fill_2026-11-02_passive_full.json`

## 5. Результаты

**Инфраструктурная оговорка:** полный WFA-прогон `730д/folds=8` (taker +
passive) превысил клиентский таймаут 30 мин и не сохранил результат
(серверные фолды шли ~2 мин каждый). Для экрана execution-слоя выполнен
**full-period A/B** `730д` на тех же параметрах; он напрямую измеряет
эффект fill-модели. WFA при необходимости можно повторить в фоне, но
full-period PF уже далёк от порога.

### 5.1 Full 730d, CNYRUBF, maxC=1, risk 1%, conf 0.60

| Метрика | Taker | Passive |
|---|---:|---:|
| Сделок | 83 | 83 |
| PF | **1,014** | **1,117** |
| Return | +0,028 % | **+0,227 %** |
| Sharpe | 0,059 | **0,458** |

Пассивный филл улучшает PF на **+0,103**, return — в ~8 раз, Sharpe — +0,40
при том же числе сделок. Это подтверждает, что издержки реальны и maker-
исполнение их снижает.

### 5.2 Вердикт: `REJECTED`

E1 ✅ (83 ≥ 30), **E2 ❌** (PF 1,117 < 1,30), **E3/E4** не проверялись
(WFA таймаут; full-period CI/P не считались), E5 ✅ (passive > taker).
Даже при улучшении PF на +0,10 пассивное исполнение **не выводит сигнал на
PF ≥ 1,30**. Execution-слой сам по себе недостаточен; как вспомогательный
слой для будущего кандидата — остаётся в коде (`executionPassiveFill`,
default off).

**Оговорка:** WFA-гейты E1–E5 в исходной формулировке не были полностью
пройдены из-за таймаута; вердикт `REJECTED` основан на E2 (full-period
PF 1,117 ≪ 1,30). Для формального WFA-вердикта нужен отдельный фоновый
прогон, но он не изменит вывод об execution-слое как самостоятельном edge.
