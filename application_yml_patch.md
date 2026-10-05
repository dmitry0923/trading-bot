# application.yml — патч (секция bt.agent.veto-mode)

Добавить в `src/main/resources/application.yml` в секцию `bt.agent`:

```yaml
bt:
  agent:
    enabled: false          # основной агентный режим (LLM-конвейер)
    veto-mode: false        # LLM-veto Вариант B (docs/24) — off by default
    sample-every: 20
    temperature: 0.0
    cache-namespace: backtest
    confidence-threshold: 0.60
    signal-budget-ms: 0
    timeout-injection-rate: 0.0
    prompt-version: default
    tech-min-signal-strength: 0.0
```

Примечание: при `veto-mode=true` активируется `AgentVetoBacktestSignalGenerator`.
Это не влияет на live-торговлю (live использует отдельный конвейер).
Оба флага `enabled` и `veto-mode` независимы и не должны быть `true` одновременно.

## Полная секция application.yml для вставки:

Найдите в файле секцию `bt:` и обновите подсекцию `agent:`:

```yaml
bt:
  agent:
    enabled: ${BT_AGENT_ENABLED:false}
    veto-mode: ${BT_AGENT_VETO_MODE:false}
    sample-every: ${BT_AGENT_SAMPLE_EVERY:20}
    temperature: ${BT_AGENT_TEMPERATURE:0.0}
    cache-namespace: ${BT_AGENT_CACHE_NAMESPACE:backtest}
    confidence-threshold: ${BT_AGENT_CONFIDENCE_THRESHOLD:0.60}
    signal-budget-ms: ${BT_AGENT_SIGNAL_BUDGET_MS:0}
    timeout-injection-rate: ${BT_AGENT_TIMEOUT_INJECTION_RATE:0.0}
    prompt-version: ${BT_AGENT_PROMPT_VERSION:default}
    tech-min-signal-strength: ${BT_AGENT_TECH_MIN_SIGNAL_STRENGTH:0.0}
```
