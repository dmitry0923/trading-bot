# AGENTS.md — патч (diff для ручного применения)

Этот файл содержит инструкции по обновлению AGENTS.md в репозитории.

## Изменения в разделе "Следующий шаг"

### Удалить (из раздела "Следующий шаг"):
```
- OBI/микроструктура (MarketMicrostructureAgent): сбор данных через REST-поллинг,
  хвостовая калибровка ≥+5 bps
- Покупка платного архива MOEX Level 2 при подтверждении edge в хвостах
```

### Добавить (в раздел "Следующий шаг"):
```
## Следующая гипотеза: LLM-veto Вариант B (docs/24)

**Статус:** 🟡 В разработке — коммит 39ef4006 закрыл OBI/микроструктуру

**Что закрыто (коммит 39ef4006):**
- OBI/микроструктура: net ≤ −2.9 bps (нужно ≥ +5 bps) → ОТРИЦАТЕЛЬНЫЙ РЕЗУЛЬТАТ
- MarketMicrostructureAgent с OBI: НЕ НУЖЕН
- Покупка платного архива MOEX: НЕ ОБОСНОВАНА

**Открытая гипотеза: LLM-veto Вариант B**
- Детерминированный сигнал (RSI+MACD baseline CNYRUBF) → ContrarianAgent LLM-veto → ArbitratorAgent.adjudicate()
- Fail-closed: если LLM недоступен → HOLD (не торговать)
- Критерий прохождения: OOS PF > 1.2 (baseline: 0.92)
- Активация: `/backtest?ticker=CNYRUBF&agentVetoMode=true`

**После подтверждения edge:** Monthly Tuning Engine (Фаза 3.5, MonthlyTuningScheduler)
```

## Полный обновлённый раздел для вставки в AGENTS.md

Найдите в AGENTS.md раздел "## Следующий шаг" и замените его на:

```markdown
## Следующий шаг

### Статус гипотез (актуально на 2026-10)

| Гипотеза | Статус | Результат |
|----------|--------|-----------|
| ML (CatBoost/LightGBM) | ❌ Закрыта | OOS PF < 1.0 |
| Session+Pullback | ❌ Закрыта | OOS PF < 1.0 |
| ORB (Opening Range Breakout) | ❌ Закрыта | OOS PF < 1.0 |
| Time-direction | ❌ Закрыта | OOS PF < 1.0 |
| OBI/Микроструктура | ❌ Закрыта (коммит 39ef4006) | net ≤ −2.9 bps < +5 bps нужно |
| **LLM-veto Вариант B** | 🟡 **ОТКРЫТА** | Не измерялось |

### Текущая открытая гипотеза: LLM-veto Вариант B

**Что закрыто (коммит 39ef4006):**
- OBI/микроструктура: net ≤ −2.9 bps при требуемых ≥ +5 bps → **отрицательный результат**
- MarketMicrostructureAgent с OBI: **не нужен**
- Покупка платного архива MOEX Level 2: **не обоснована**

**Механизм LLM-veto Вариант B (docs/24):**

Детерминированный сигнал (RSI+MACD, baseline CNYRUBF OOS PF=0.92)
→ ContrarianAgent.challenge() → ArbitratorAgent.adjudicate()
→ ALLOW: выполнить сигнал | HOLD: заблокировать вход

Fail-closed: если LLM недоступен → HOLD (не торговать).

**Запуск прогона:**
```bash
curl "http://localhost:8080/backtest?ticker=CNYRUBF&agentVetoMode=true"
```

**Критерий прохождения:** OOS PF > 1.2 (baseline: 0.92)

**После подтверждения edge:** Monthly Tuning Engine (Фаза 3.5)
— cron: первый рабочий день месяца, 09:05 МСК
— gate check: PF > 1.2, DD < 15%, WinRate > 48%, Sharpe > 0.3
— при ALERT: автоостановка + email trading-alerts@example.com
```
