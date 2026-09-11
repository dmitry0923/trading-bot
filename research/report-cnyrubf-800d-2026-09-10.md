# Research-отчёт: CNYRUBF WFA/gate, 800 дней (2026-09-10)

> Прогон на полной 800-дневной истории (50 087 свечей MINUTE_10, 2024-07-02→2026-09-10,
> 572 торг. дня), frozen strategy, conf=0.60, folds=6, издержки 2026-09 (provisional).
> Архитектура заморожена на `deacdeb`; WFA канонизирован — оба эндпоинта
> используют ОДИН `WalkForwardAnalyzer`.

## Итог: REJECTED (liveAllowed=false) — исследование CNYRUBF закрыто

## Ключевой фикс (canonical WFA)

Прежнее расхождение `/validate` (PF 1.61, 28 сделок) vs gate-WFA (PF 0.60, 21 сделка)
объяснялось **разными входными данными**, а не разными алгоритмами:

- `/validate` — WFA на ВСЕЙ выбранной истории;
- deployment-gate — WFA только на dev-части (первые 80% истории, последние 20% — holdout).

Путей WFA в обоих эндпоинтах всегда был один (`BacktestValidator.validate`); после
рефакторинга он оформлен как канонический контракт `WalkForwardAnalyzer(WfaConfig)`,
которому имеют доступ и `/validate`, и `FinalHoldoutValidator` (gate). Регрессионный тест
`WalkForwardCanonicalityTest` фиксирует: gate делегирует WFA РОВНО тому же анализатору и
не пересчитывает его собственным кодом. Расхождение validate vs gate теперь обязано
исключительно holdout-резервированию (анти-leakage by design).

## Результаты (800д, канонический WFA, conf=0.60, folds=6)

**`/validate` (WFA на всех 800д):** consistency=**0.33**, oosTrades=**73**, oosReturn=+0.34%,
oosSharpe=0.27, oosSortino=0.44, oosPF=**1.09**, edge=False (P(noEdge)=0.40) → `robust=false`.

**Deployment-gate (WFA на dev 80% + независимый holdout 20% + MC/stress):**

| Check | Verdict |
|---|---|
| Base IS (dev, 640д) | FAIL — Sharpe −0.07, MDD 0.4%, PF **0.98**, 67 сделок |
| WFA OOS | FAIL — consistency 0.33, oosTrades 51, oosSharpe **−1.36**, oosPF **0.60** |
| OOS-сделок WFA | FAIL — 51 < 100 |
| Consistency | FAIL — 0.33 < 0.60 |
| Edge significance | FAIL — P(noEdge)=0.92 |
| Holdout | FAIL — −0.0%, 20 сделок < 30, passable=false |
| MC+stress | FAIL — p5=−1.0%, P(loss)=53.8%, stressFailed=5 |

## Вывод

Вердикт на полной 2.2-летней истории **хуже** 365д: теперь даже IS-backtest dev-части
PF=0.98 (убыточный), WFA OOS PF=0.60 с отрицательным Sharpe, consistency 0.33, edge
отсутствует (P(noEdge)=0.92), holdout нулевой, а Монте-Карло показывает риск убытка
53.8% с провалом 5 стресс-снапшотов. Это **не** «мало данных», а отсутствие устойчивого
edge: на 73 OOS-сделках (против 8–28 ранее) статистическая картина только ухудшилась.

Согласованные решения (по протоколу): folds=8 и отдельная cost-sensitivity НЕ прогонялись
(покрыто MC-stress). **Исследование CNYRUBF закрыто**: стратегия на «полку», live не
одобрена. Дальнейшие шаги — только новая research-гипотеза (regime / time-of-day /
volatility / microstructure) в отдельной ветке. Производственный код после фикса
канонического WFA не меняется.