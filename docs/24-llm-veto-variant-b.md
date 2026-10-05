# docs/24 — LLM-veto Вариант B: Методология и Гипотеза

**Статус:** 🟡 В разработке (не запускалось, ждёт прогона)  
**Дата создания:** 2026-10  
**Основание:** [docs/23](23-microstructure-forward-pilot.md) §7 (закрытый OBI), [AGENTS.md](../AGENTS.md)  
**Коммит-ориентир:** `39ef4006` — закрытие OBI/микроструктуры

---

## 1. Контекст: что было закрыто

Коммит `39ef4006` зафиксировал окончательное закрытие OBI/микроструктурной гипотезы:

- Хвостовая калибровка OBI: **net ≤ −2.9 bps** (нужно: ≥ +5 bps) → отрицательный результат
- Покупка платного архива MOEX Level 2: **не обоснована** при текущем edge
- MarketMicrostructureAgent с OBI: **не нужен**
- OBI/микроструктура как сигнал: **полностью закрыта**

Единственная открытая гипотеза на момент создания этого документа — LLM-veto Вариант B.

---

## 2. Гипотеза: LLM как фильтр ложных входов

### Предпосылка

Baseline CNYRUBF (RSI+MACD) на WFA показывает OOS PF=0.92 — недостаточно для торговли,  
но это точка отсчёта с реальным сигналом. Гипотеза: **LLM способен отфильтровать убыточные  
входы из детерминированного сигнала**, тем самым подняв OOS PF выше 1.2.

### Механизм (Вариант B)

```
Детерминированный сигнал (RSI+MACD)
        ↓
   BUY / SELL ?
        │
        ├─ HOLD → пропустить LLM (не торговать, экономия API)
        │
        └─ BUY/SELL → ContrarianAgent.challenge()
                               ↓
                     LLM недоступен? → fail-closed → HOLD
                               ↓
                     ArbitratorAgent.adjudicate()
                               ↓
                     ALLOW → выполнить сигнал
                     HOLD  → заблокировать вход
```

Вариант B отличается от Варианта A тем, что **детерминированный сигнал является primary source**,  
а LLM — фильтром, а не генератором. Это снижает риск hallucination-driven сигналов.

### Fail-closed поведение

При недоступности LLM (таймаут, ошибка API, нет ключа):
- Возвращается **HOLD** — не торговать
- Инкрементируется `bt_llm_veto_blocked_total{reason="llm_unavailable"}`
- Логируется WARNING для мониторинга

Это принципиально отличается от fail-open, который бы торговал при любом сбое.

---

## 3. Реализация

### Новые файлы

| Файл | Назначение |
|------|-----------|
| `backtest/AgentVetoBacktestSignalGenerator.kt` | Основная логика veto-генератора |
| `agent/VetoResult.kt` | Sealed class ALLOW/BLOCK/HOLD |
| `config/BacktestAgentConfig.kt` | Добавлен флаг `vetoMode` |
| `api/ApiController.kt` | Добавлен `?agentVetoMode=true` |
| `application.yml` | Секция `bt.agent.veto-mode` |

### Активация в бэктесте

```bash
# Запуск прогона с LLM-veto
curl "http://localhost:8080/backtest?ticker=CNYRUBF&agentVetoMode=true"
```

### Метрики

- `bt_llm_veto_blocked_total{ticker, reason}` — количество заблокированных сигналов
- `bt_llm_veto_allowed_total{ticker, reason}` — количество пропущенных сигналов

---

## 4. Критерии прохождения гипотезы

| Метрика | Минимум для PASS | Целевое значение |
|---------|-----------------|-----------------|
| OOS Profit Factor | > 1.2 | > 1.4 |
| OOS Sharpe | > 0.3 | > 0.5 |
| WFA Consistency | > 0.60 | > 0.70 |
| Monte-Carlo P(loss) | < 30% | < 20% |
| Veto Block Rate | < 60% | 20-40% |

**Veto Block Rate** — доля сигналов, заблокированных LLM-veto. Если > 60% — LLM блокирует  
слишком много, вето неэффективно. Если < 10% — LLM почти не фильтрует, veto бесполезен.

---

## 5. Методология прогона

### WFA конфигурация

```
Expanding window:
  IS: 2024-01 → 2025-12 (24 месяца)
  OOS фолды: 2026-01, 2026-02, 2026-03 ... (6+ фолдов)
  
Базовая сравнение: DeterministicBacktestSignalGenerator (RSI+MACD, без LLM)
Экспериментальное: AgentVetoBacktestSignalGenerator (RSI+MACD + LLM-veto)
```

### Ключевые вопросы для анализа

1. **Какой процент сигналов отфильтровывает LLM?** (Veto Block Rate)
2. **Какие входы LLM блокирует правильно?** (precision veto)
3. **Есть ли смещение по времени суток / волатильности?**
4. **Стоимость API vs прирост PF:** окупается ли LLM-вызов?

---

## 6. Связанные документы

- [docs/21 — Handoff 2026-09-28](21-handoff-2026-09-28.md): первоначальный план LLM-veto Вариант B
- [docs/23 — Microstructure Pilot](23-microstructure-forward-pilot.md): **§7 — закрытие OBI**
- [AGENTS.md](../AGENTS.md): актуальный статус гипотез и агентного конвейера
- [docs/Monthly Tuning Engine](24-llm-veto-variant-b.md#monthly-tuning): Фаза 3.5

---

## 7. Monthly Tuning Engine (Фаза 3.5) {#monthly-tuning}

После подтверждения edge LLM-veto (OOS PF > 1.2) активируется Monthly Tuning Engine:

```
Конец месяца
    ↓
PerformanceGateCheck
    ├─ PASS  → micro-tuning (параметры RSI/MACD ±20%)
    ├─ WARN  → усиленный мониторинг + tuning
    └─ ALERT → автоостановка торговли + email trading-alerts@example.com
```

Детали реализации: `tuning/MonthlyTuningService.kt`, `tuning/MonthlyTuningScheduler.kt`.  
Cron: первый рабочий день месяца в 09:05 МСК (`0 5 9 1-7 * MON-FRI`).

---

## 8. Следующие шаги

1. Запустить прогон: `curl ".../backtest?ticker=CNYRUBF&agentVetoMode=true"`
2. Проверить Veto Block Rate в метриках (`bt_llm_veto_blocked_total`)
3. Сравнить OOS PF с baseline (WFA без veto)
4. Если OOS PF > 1.2 → shadow-тест 30 дней
5. Если OOS PF ≤ 1.2 → переход к Гипотезе H3 (Carry+Momentum)

---

*Документ создан в рамках Фазы 2 (LLM-veto Вариант B). OBI/микроструктура закрыта (docs/23 §7,  
коммит 39ef4006). Все гипотезы измеряются, не угадываются.*
