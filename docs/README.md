# MMVB Trading Bot v2 — Техническая документация

> Kotlin 1.9.21 · Spring Boot 3.2.0 · Java 21 · PostgreSQL 15 · Redis 7 · MOEX через Alor Broker · LLM-агенты (Kimi API)

Это полная техническая документация проекта. Она рассчитана на работу команды из 3–5 человек без дополнительных вопросов: архитектор, backend-разработчики, DevOps-инженер, аналитик.

## Структура документации

| Раздел | Файл | О чём |
|---|---|---|
| 1. Executive Summary | `01-executive-summary.md` | Для бизнеса: что делает бот, метрики, требования |
| 2. Архитектура | `02-architecture.md` | C4-диаграммы, слои, event-driven, потоки данных |
| 3. Мультиагентный конвейер | `03-llm-pipeline.md` | 6 агентов, промпты, guardrails, LLM-инфраструктура |
| 4. Интеграции | `04-integrations.md` | Alor REST/WS, MOEX ISS, Kimi API, Outbox, slippage |
| 5. Risk Management | `05-risk-management.md` | RiskEngine, Kelly, Daily Loss Limit, мониторинг позиций |
| 6. База данных | `06-database.md` | ER-диаграмма, таблицы, Liquibase, оптимизация |
| 7. API интерфейс | `07-api.md` | Все REST endpoints с примерами |
| 8. Конфигурация | `08-configuration.md` | application.yml, env-переменные, .env, режимы |
| 9. Мониторинг | `09-monitoring.md` | Prometheus метрики, Grafana, Alertmanager, логи |
| 10. Деплой на cloud.ru | `10-deployment.md` | k8s манифесты, CI/CD, безопасность |
| 11. Backtest | `11-backtest.md` | Фреймворк бэктеста, метрики, критерии приёма |
| 12. Troubleshooting | `12-troubleshooting.md` | Частые проблемы и диагностика |
| 13. Roadmap | `13-roadmap.md` | Текущее состояние и план v2.1–v2.4 |
| 14. Приложения | `14-appendices.md` | Глоссарий, тикеры, .env, docker-compose, kubectl |
| 15. Фьючерсный контур (Si) | `15-futures-trading.md` | FuturesTradingBotService, риск-движок, ликвидация, daily loss limit, e2e |

## Как пользоваться

- **Новичок в проекте** — начните с раздела 1, затем 2 (архитектура) и 3 (конвейер LLM).
- **DevOps** — разделы 8, 10, 12.
- **Разработчик** — разделы 3, 4, 5, 6, 7.
- **Аналитик** — разделы 1, 9, 11, 13.

## Быстрый старт (за 5 минут)

```bash
# 1. Поднять PostgreSQL и Redis
docker compose up -d postgres redis

# 2. Запустить бота в SIMULATION режиме
$env:KIMI_API_KEY="sk-..."
$env:TRADING_MODE="SIMULATION"
.\gradlew.bat bootRun

# 3. Проверить здоровье
curl http://localhost:8080/actuator/health

# 4. Прогнать тесты (нужен Docker для Testcontainers)
.\gradlew.bat test
```

## Состояние репозитория

- Сборка: **BUILD SUCCESSFUL**, все **69 тестов** зелёные (PromptTemplateTest, PromptRegistryTest, GuardrailsTest, SemanticCacheTest, SelfLearningIntegrationTest, BacktestEngineTest + фьючерсный контур: FuturesPositionSizerTest, FuturesRiskEngineTest, AlorFuturesClientTest, DailyLossCircuitBreakerTest, FuturesTradingBotServiceIntegrationTest).
- Реализовано: LLM-инфраструктура (resilience + semantic cache + prompts), мультиагентный конвейер, Alor REST/WS интеграция с Outbox и slippage control, **event-driven слой** (раздел 2.3), **sector/volatility guard** (раздел 5), **backtest framework** (раздел 11), **фьючерсный контур Si** (раздел 15): риск-first сайзинг, ликвидационные guardrails, daily loss limit с персистентностью в `daily_risk_snapshot`, торговые часы, e2e-подтверждение в SIMULATION.
- На стадии проектирования: k8s-манифесты и CI/CD (раздел 10), emergency stop endpoint (раздел 5.8), партиционирование БД (раздел 6.4).
