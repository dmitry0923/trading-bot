# LIVE-контур: формальное риск-ревью (2026-11-03)

**Объект:** `trading-bot` (HEAD до коммита ревью; движок `2026-10-26.2`).

**Статус:** ревью **без включения LIVE**. `TRADING_MODE=LIVE` не выставлялся,
реальные ордера не отправлялись.

**Вердикт: `NO-GO` для LIVE.** Основной блокер — не код-контроли, а
**отсутствие подтверждённого edge у той стратегии, которая пойдёт в LIVE**:
baseline CNYRUBF на исправленном движке `PF 1.012 / P(noEdge) 0.486 / 82 сделки`,
`passable=false`; комбинации фильтров отклонены deployment-gate. B (forward paper)
проверяет **другую гипотезу и другой юниверс** (5 фьючерсов momentum+low-vol) и
не может легитимизировать LIVE CNYRUBF-only.

## 1. Метод и границы

- Статический разбор пути LIVE: gate → sizing → execution → exits → reconciliation.
- Сверка конфигов `application.yml`/`RiskConfig`/`TradingConfig` и CI (`deploy-live`).
- Прогон unit/integration/ktlint, Docker-smoke (см. §6).
- Не проверялось на реальном счёте Alor: брокерские лимиты, фактические ГО,
  реальные fills/slippage, поведение биржевых стопов. Это отдельный этап
  (paper → минимальный LIVE под наблюдением), который без edge не имеет смысла.

## 2. Контур LIVE

```
Strategy (deterministic) → TradingGate (halt/limits/hours)
  → DecisionEngine (LIVE allowlist, risk, Kelly, margin, NetEV, spread/stale)
  → OrderBuilder (SL/TP, sizing) → ExecutionGateway
  → OrderOutbox (idempotency, lease fence) → WS/REST Alor
  → PositionMonitor / ProtectionOrderManager (exchange SL/TP)
  → StateReconciliation (fill/position/order drift, fail-closed halt)
```

## 3. Инвентарь контролей

| Контроль | Где | Статус | Замечание |
|---|---|---|---|
| LIVE allowlist только CNYRUBF; пустой список = DENY | `TradingConfig`, `DecisionEngine`, `RestOrderTransport`, `WsOrderTransport` | **есть** | входы; close/SL/TP разрешены при открытой позиции |
| Frozen strategy + approval + fingerprint + build SHA | `LiveFrozenStrategyResolver`, `DeploymentApprovalService` | **есть** | `ready=false` при недоступной БД → DENY; approval только production-прогоном gate |
| Build identity | `BuildIdentity` + `GIT_COMMIT` в образе | **есть** | несовпадение SHA → DENY |
| Ручной disable | `TradingControlService`/`TradingGate` | **есть** | блокирует новые входы |
| Emergency stop (+liquidate) | `EmergencyStopService` | **есть** | halt персистится, снятие только `resume` |
| Trading hours | `TradingHoursGuard` | **есть** | только входы; закрытия не блокируются |
| Daily loss `min(2% AUM, 5000 ₽)` | `DrawdownProtectionService` + `DailyLossCircuitBreaker` | **есть** | halt новых входов; позиции закрываются по SL/TP |
| Rolling 7d/30d, consecutive losses, shadow | `DrawdownProtectionService` | **есть** | блокировка входов |
| Sizing: Kelly cold `0.003`, cap `0.10` | `AdaptiveRiskService`/`RiskConfig` | **есть** | без статистики сайзинг минимальный |
| `maxC=1`, futures max open 1 | `RiskConfig`/yml | **есть** | cap на 1 контракт |
| Margin 60%, stressed ×1.5, side-specific GO, snapshot | `RiskManagementService`, `AlorFuturesClient` | **есть** | fail-closed при отсутствии GO/снапшота |
| Stale data 15 c, spread 0.1%/0.5% | `MarketDataGate`, `TradingGate` | **есть** | блокируют вход |
| WS watchdog/reconnect | `WebSocketManager`, `AlorWebSocketClient` | **есть** | timeout → закрытие сессии → reconnect |
| Exactly-once вход | `DecisionEngine` + `entry_reservations` + lease fence | **есть** | проверено аудитом |
| Outbox idempotency/reconcile | `OrderOutboxService` | **есть** | повтор только после reconciliation |
| Partial fills / cancel/fill race | `OrderExecutionEngine`, `CloseFillProcessor` | **есть** | покрыто тестами |
| SL/TP/trailing + exchange protection | `ExitRules`, `ProtectionOrderManager` | **есть** | окно до выставления биржевого стопа — остаточный риск |
| Ликвидация: worst-case симуляция | `BacktestEngine`, `FuturesRiskEngine` | **есть** | SL 150–300 пт срабатывает раньше liq |
| Funding: SWAPRATE × lotSize, `fundingUnknown` | `MoexFundingProvider`, `PnlCalculator` | **есть** | funding-veto в LIVE **off** (решение пользователя) |
| Секреты: Lockbox, truststore, redaction | P0-3, P1-1, P2-1 | **есть** | Lockbox E2E на VM ещё не проверялся |
| Деплой LIVE: manual + environment + `LIVE_CONFIRM` | `.github/workflows/ci.yml` | **есть** | SIM форсируется на push |
| Бэкапы/rollback | Managed PG backups, image tags | **есть** | down-миграций нет; откат — образ + restore |

## 4. Найденные при ревью дефекты (исправлены)

1. **`DeploymentApprovalService.init` блокировал поток `runBlocking`.** Загрузка
   approvals из БД на `ApplicationReadyEvent` могла висеть при деградации БД.
   Переведено на `suspend fun init()`; `ready=false` до/при ошибке сохранён
   (fail-closed). Тесты обновлены.
2. **`TradingGate.onTradingHalted` писал halt в БД через `runBlocking`.** На
   событии halt (daily loss/desync) поток блокировался на R2DBC. Переведено на
   suspend `@EventListener`; порядок «halt → запись» сохранён.

Остаточные startup-`runBlocking` (`SettingsService`, `FrozenStrategyStore`,
`TradingHaltService`) ограничены R2DBC `max-acquire-time=5s`; это startup-путь,
не торговый. Зафиксировано как технический долг, не блокер LIVE.

## 5. Блокеры LIVE

| # | Блокер | Доказательство | Что нужно |
|---|---|---|---|
| 1 | **Нет подтверждённого edge LIVE-стратегии** | baseline `artifacts/baseline_engine_2026-10-26_2.json`: +0,026 %, PF 1,012, P(noEdge) 0,486, 82 сделки, `passable=false`; combo730 gate `RESEARCH_ONLY`, holdout −17,6 % | новый предрегистрированный протокол для фактической LIVE-стратегии; `deployment-gate` в режиме `researchMode=false&confirmedForProduction=true` → `LIVE_ALLOWED` |
| 2 | **B не валидирует LIVE-контур** | B — momentum+low-vol на 5 фьючерсах (paper); LIVE — детерминированный CNYRUBF, `maxC=1` | либо deployment-gate именно CNYRUBF-стратегии, либо отдельное решение о смене LIVE-юниверса/стратегии (новый протокол; research-разрешение `maxC 2–4/6–8` на LIVE не распространяется) |
| 3 | **Нет реальной доставки алертов** | Alertmanager → `alert-sink` (http-echo, лог) | подключить Telegram/webhook-реле и проверить тестовым алертом |
| 4 | **Kill-switch не отработан** | нет журнала учений | провести учения: emergency stop (liquidate true/false), disable, force-close, resume; зафиксировать |
| 5 | **Lockbox E2E не проверен на VM** | P0-3 покрыт unit-тестами, реального SA-ключа в `.env` нет | включить `LOCKBOX_ENABLED=true` на staging/VM, проверить старт и отсутствие секретов в логах |
| 6 | **Ёмкость 1 контракта** | cap: 86 447 ₽ за 730д ≈ 43 253 ₽/год при идеальном тайминге (50k: 86,5 %/год теоретически) | при захвате 20–40 % ожидаемая доходность 17–35 %/год до издержек; 100 %/год на `maxC=1` недостижимы |
| 7 | **Один инструмент, без диверсификации** | allowlist CNYRUBF | портфельный слой (docs/42/50) отклонён/не подтверждён; расширение — новый протокол |

## 6. Что уже проверено

- `test + integrationTest + ktlintCheck` (финальный код, включая правки §4):
  **BUILD SUCCESSFUL in 20m 48s**; **3 400 unit + 225 integration**,
  0 падений, 1 skipped в каждом наборе, ktlint чисто.
- `DeploymentApprovalServiceTest` и `TradingGateTest` — suspend-пути
  (`ready=false`/fail-closed и запись halt) проходят.
- Docker-образ: non-root `app`, health 200/200/200, smoke 6/6.
- Контроли исполнения: `OrderOutboxServiceTest`, `OrderExecutionEngine*`,
  `LeaseFencingIntegrationTest`, `StateReconciliation*`, `RestOrderTransportLiveTest`,
  `WsOrderTransport*`, `FuturesRiskEngineTest`, `RiskManagementService*`,
  `DrawdownProtectionService*`, `LiveFrozenStrategyResolverTest`,
  `DeploymentApprovalServiceTest`.
- Compose/Prometheus/Alertmanager конфиги валидированы; TLS/managed override
  собираются.

## 7. Условия допуска LIVE (все обязательны)

1. **Стратегия:** deployment-gate фактической LIVE-стратегии/юниверса
   (`researchMode=false`, `confirmedForProduction=true`) → `LIVE_ALLOWED`:
   - backtest `isPassable` (Sharpe > 1.2, MDD ≤ 40 %, PF > 1.3, ≥ 10 сделок);
   - WFA OOS ≥ 100 сделок, consistency ≥ 0.6, edge P(noEdge) < 0.05;
   - holdout ≥ 30 сделок и passable;
   - MC robust + stress pass.
2. **B (если LIVE-стратегия = momentum-портфель):** F1–F5 ≥ 30 сделок,
   PF ≥ 1.30, block p < 0.05, CI95 > 0, MDD ≤ 40 %. Для CNYRUBF-only B
   **не является** основанием.
3. **Операции:** реальная доставка алертов; kill-switch учения; Lockbox E2E;
   TLS; бэкапы/PITR; доступ к VM/брокеру 24/7.
4. **Риск-ревью:** подписанный чек-лист §8 и решение пользователя.
5. **Параметры:** `maxC=1`, Kelly, `CNYRUBF`, `ALOR_PORTFOLIO` — реальный
   (не `D12345`), `JWT_COOKIE_SECURE=true`.

## 8. Runbook включения LIVE (после §7)

1. Задеплоить тот же SHA в SIM, прогнать smoke и сутки наблюдения.
2. Выполнить `GET /api/v1/backtest/CNYRUBF/deployment-gate?researchMode=false&confirmedForProduction=true&days=...&folds=...`
   (ADMIN). Ожидается `status=LIVE_ALLOWED`, `approvalPersisted=true`,
   `liveApprovalActive=true`.
3. Проверить `DeploymentApprovalService.ready=true`, frozen strategy и
   fingerprint; `BuildIdentity.gitCommitSha()` == SHA образа.
4. Выставить в VM `.env`: `ALOR_PORTFOLIO=<реальный>`, TLS/`JWT_COOKIE_SECURE=true`,
   `LIVE_TICKERS_ALLOWLIST=CNYRUBF`; **`TRADING_MODE` не задавать** (CI
   deploy-live форсирует LIVE).
5. Запустить `deploy-live` вручную: GitHub Environment `live` + reviewers +
   `live_confirmation=LIVE_CONFIRM`.
6. После старта: проверить `/api/v1/trading/status` (mode=LIVE, allowlist),
   `GIT_COMMIT`, approval, отсутствие `entry.rejected{LIVE_TICKER_NOT_ALLOWED}`,
   первый вход/выход, `order_outbox` (не растёт), `funding_unknown`.
7. Первые дни: ежедневно сверять позиции/ордера с брокером, P&L, дневной лимит,
   алерты; при отклонении — emergency stop.
8. **Rollback:** остановить входы (`/trading/disable` или emergency stop),
   закрыть позиции, задеплоить предыдущий SHA; approval привязан к SHA —
   проверить/отозвать (`/deployment-gate` с research/повторным approve).

## 9. Kill-switch чек-лист (учения)

- [ ] `POST /api/v1/bot/emergency-stop {"reason":"drill","liquidate":false}` →
      новые входы заблокированы, halt в БД, метрика `bot.emergency_stop`.
- [ ] `POST /api/v1/bot/emergency-stop {"liquidate":true}` → позиции закрыты,
      `closed>0`; повторить с открытой тестовой позицией в SIM.
- [ ] `POST /api/v1/trading/disable` → `TradingBlockReason.MANUAL_DISABLE`;
      `POST /api/v1/trading/enable` возвращает торговлю.
- [ ] `POST /api/v1/trading/force-close` → все позиции закрыты; повторный вызов
      не создаёт дублей.
- [ ] `POST /api/v1/bot/resume` после halt → записи сняты, входы разрешены
      (только вручную).
- [ ] Остановка контейнера/VM: биржевые SL/TP остаются; после рестарта
      State Reconciliation не находит расхождений.

## 10. Остаточные риски

| Риск | Оценка | Митигация |
|---|---|---|
| Стратегия без edge (главный) | высокий | §5.1/§7.1 |
| B ≠ LIVE-стратегия (логическая ошибка гейта) | высокий | §5.2/§7.2 |
| Гэп через SL / превышение дневного лимита | средний | SL + ликвид. симуляция; лимит только на входы; ручной контроль |
| Окно до выставления биржевого SL/TP | средний | `ProtectionOrderManager` на каждом тике; ограничить размер 1 контрактом |
| fundingUnknown / funding-veto off | средний | `funding_unknown` в БД/мониторинге; veto включать только по решению |
| Lockbox/секреты на VM | средний | E2E + запрет секретов в логах |
| Один инструмент/одна VM | высокий для доходности | capacity §5.6; HA/резерв — вне скоупа |
| Нет down-миграций | средний | бэкап/PITR до деплоя; rollback образом |
| LLM/фоновые сервисы в LIVE | низкий | LLM-signal off/shadow; LLM-veto off |
| Алерты-заглушка | средний | §5.3 |

## 11. Итог

**LIVE — NO-GO.** Код-контроли исполнения и риска в основном зрелые
(exactly-once, outbox, LIVE-guard, frozen approval, fail-closed GO/AUM/state),
но **нет статистического основания торговать**: фактическая LIVE-стратегия не
прошла deployment-gate, а B проверяет другую гипотезу. Включать LIVE до
устранения §5.1–§5.7 нельзя.

Параметры LIVE не менялись: `maxC=1`, Kelly, `CNYRUBF`. Research-разрешение
`maxC 2–4/6–8` относится только к research/paper.
