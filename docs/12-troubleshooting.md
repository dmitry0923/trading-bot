# 12. Troubleshooting и FAQ

## 12.1. Частые проблемы и решения

### «Бот не открывает позиции»

**Чек-лист диагностики (по порядку):**

1. **Режим и лимиты** — самое частое:
   ```bash
   curl http://localhost:8080/api/v1/risk/daily-pnl      # дневной P&L
   curl http://localhost:8080/api/v1/analytics/adaptive-params/SBER
   ```
   - `TRADING_MODE=SIMULATION` и `MAX_OPEN_POS=0` → бот сознательно не открывает. Это **не баг**.
   - `dailyPnl <= -5000` → сработал дневной лимит (`bot.halted.daily_loss` в метриках).
2. **Решение арбитра**:
   ```bash
   curl "http://localhost:8080/api/v1/logs" | grep Agent-5-Arbitrator
   ```
   - HOLD с `overrideReason` → смотрим причину: CRITICAL_CHALLENGE, LOW_DRAFT_CONFIDENCE, DAILY_LOSS_LIMIT.
   - `confidence` стратегий близко к порогу → повышается порог, ничего не проходит.
3. **LLM недоступен**:
   ```bash
   curl http://localhost:8080/actuator/prometheus | grep llm_fallback
   ```
   - Большой рост `llm.fallback.activated{reason=CALL_ERROR}` → проверить `KIMI_API_KEY`, сеть до `api.moonshot.cn`, CB (`resilience4j_circuitbreaker_state{name="llm"}`).
4. **Маркет-ордер заблокирован**:
   ```bash
   curl http://localhost:8080/actuator/prometheus | grep alor_order_blocked
   ```
   - `reason=WIDE_SPREAD` → спред > 0.5%, ордер отклонён. Ожидаемое поведение.
5. **Пауза по статистике**:
   ```bash
   curl http://localhost:8080/api/v1/analytics/health
   ```
   - `pausedTickers` содержит тикер → `adaptive.pause=1`, `strategy.skipped`.

### «LLM возвращает невалидный JSON»

Защитные слои уже работают автоматически:

1. `response_format={"type":"json_object"}` — модель принудительно отдаёт JSON.
2. Парсер каждого агента ловит исключение:
   - StrategyAgent: `strategy.agent.parse.error{ticker}` → HOLD;
   - ArbitratorAgent: `agent.arbitrator.parse.error` → HOLD `FALLBACK: PARSE_ERROR`;
   - Technical/Fundamental: NEUTRAL + baseline.
3. `raw_output` сохраняется в `agent_logs` — можно посмотреть, что именно вернула модель:
   ```text
   SELECT cycle_id, agent_name, raw_output FROM agent_logs WHERE raw_output LIKE '%```%' ORDER BY created_at DESC LIMIT 10;
   ```
4. Если модель «оборачивает» JSON в ```json ... ``` — парсеры уже делают `.replace("```json","").replace("```","")`.

### «Ордер висит в PENDING»

```text
SELECT id, status, alor_order_id, created_at, error_message FROM order_outbox ORDER BY created_at DESC LIMIT 20;
```

1. **Worker не работает** — проверить, что приложение запущено (`@Scheduled` активен, `@EnableScheduling` в `TradingBotApplication`).
2. **PENDING старше 30 c** — worker переотправит при следующем цикле (10 c). Если статус не меняется:
   - Alor недоступен → проверьте `ALOR_TOKEN`/`ALOR_REFRESH_TOKEN`, логи `AlorClient`;
   - постоянные FAILED → `error_message` в таблице;
3. **Идемпотентность**: повторная отправка идёт с тем же `idempotencyKey` — Alor не создаст дубль.
4. Если `FAILED` из-за `WIDE_SPREAD` для market — это защита от проскальзывания, не ошибка.

### «Память пода растёт»

1. Проверка heap:
   ```bash
   kubectl -n trading-bot top pod trading-bot-xxx
   curl http://localhost:8080/actuator/metrics/jvm.memory.used
   ```
2. Типичные причины:
   - **Незакрытые корутины**: `CallbackFlow`-поток WS (переподключение) — должен жить, остальные scope заканчиваются. Если `alor.ws.reconnect` растёт — поток пытается переподключиться, это нормально, но проверить количество активных соединений.
   - **ConcurrentHashMap кэш шаблонов** (`PromptTemplate.COMPILED`) — растёт только при большом числе шаблонов/переменных, лимитирован.
   - **agent_logs.raw_output** — хранение сырых ответов LLM (TEXT) может раздуть БД, не память. Архивация — roadmap.
3. **Корутины-утечки**: все scope — `SupervisorJob + Dispatchers.Default`, закрываются при завершении работы. Если циклы накладываются (таймаут > интервала) — см. следующий пункт.

### «Redis недоступен»

Graceful degradation встроен везде:

| Компонент | Поведение без Redis |
|---|---|
| `RedisCacheService` | логирует ERROR, возвращает null — стратегии берутся из БД |
| `SemanticCache` | логирует WARN, `llm.cache.error` — LLM-вызовы идут без кэша |
| `PerformanceFeedbackAgent` | кэш недоступен → feedback генерируется каждый цикл |
| Бот | продолжает работать (стратегии из `strategies` таблицы) |

> Единственное, что теряется: «быстрое» распространение стратегий между циклами и семантический кэш. Риск: при росте нагрузки на LLM без кэша вырастет стоимость.

### «Тикер не попадает в секторную проверку»

- Тикер не в `risk.sectors` → сектор `UNKNOWN`.
- Для сектора `UNKNOWN` проверка работает (не более `max-sector-exposure` позиций с `UNKNOWN`), но смысл теряется: два разных тикера без маппинга считаются «одним сектором».
- **Решение**: добавить тикер в `risk.sectors` в `application.yml` при добавлении в `trading.tickers`.
- Проверить конфиг: `curl http://localhost:8080/api/v1/settings` не показывает `sectors` — смотрите лог старта (INFO) и `application.yml`.

### «Бэктест возвращает нулевые метрики»

Причины:

1. **Мало свечей** — `BacktestEngine` требует ≥ 32 свечей (`minBarsForSignal + 2`), иначе `emptyResult()`:
   ```text
   SELECT count(*) FROM candles WHERE ticker='SBER' AND timeframe='MINUTE_10';
   ```
   Если < 32 — подождите накопления данных или загрузите историю через MOEX ISS (fallback в `loadCandles`).
2. **Нет сигналов** — RSI/MACD не входили в зоны (RSI<30 с MACD>0 или RSI>70 с MACD<0) → 0 сделок, это валидный результат, но `isPassable=false`.
3. **Тикер с пробелами данных** — свечи есть, но непокрытый период. Проверьте диапазон:
   ```text
   SELECT min(time), max(time) FROM candles WHERE ticker='SBER' AND timeframe='MINUTE_10';
   ```

### «Стратегия HOLD без понятной причины»

1. `volatility guard` — ATR% > 5%:
   ```bash
   curl http://localhost:8080/actuator/prometheus | grep risk_volatility_blocked
   ```
   Лог: `Volatility guard: SBER ATR=... > 5.0%, strategy -> HOLD`.
2. `overrideReason` в `agent_logs`:
   ```text
   SELECT ticker, action, confidence, override_reason FROM agent_logs
   WHERE agent_name='Agent-5-Arbitrator' AND ticker='SBER' ORDER BY created_at DESC LIMIT 5;
   ```
 3. Порог confidence — стратегия `confidence` ниже адаптивного порога (калибровка 0.50–0.85, fallback 0.55–0.80, раздел 13.11.8).

### «События не доходят до обработчика»

Событийный слой синхронный (`ApplicationEventPublisher`), поэтому «потеря» события маловероятна. Если `event.handled` заметно меньше `event.published`:

1. Проверьте, что подписчик зарегистрирован: лог старта не должен содержать ошибок контекста.
2. `@EventListener`-методы в `TradingBotService` не выбрасывают исключений до конца (иначе событие «теряется» — см. журнал `event.handler.error`).
3. Между публикацией и обработкой может быть рестарт — резерв: Redis `strategy:<ticker>` TTL 15 мин.

## 12.2. Команды для диагностики

### Логи

```bash
# Kubernetes
kubectl -n trading-bot logs deploy/trading-bot --tail=200 -f
kubectl -n trading-bot logs deploy/trading-bot --since=1h | grep ERROR

# Docker compose
docker compose logs app --tail=200

# Локально
.\gradlew.bat bootRun 2>&1 | Select-String -Pattern "ERROR|WARN|FINAL"
```

### psql

```bash
psql -U trader -d trading_bot

# Последние решения арбитра
SELECT cycle_id, ticker, action, confidence, override_reason FROM agent_logs
WHERE agent_name='Agent-5-Arbitrator' ORDER BY created_at DESC LIMIT 20;

# Текущие позиции
SELECT ticker, direction, quantity, entry_price, current_price, status, pnl FROM positions ORDER BY opened_at DESC LIMIT 10;

# Outbox
SELECT status, count(*) FROM order_outbox GROUP BY status;

# Свечи по тикеру (для индикаторов)
SELECT count(*) FROM candles WHERE ticker='SBER';

# Диапазон исторических данных для бэктеста
SELECT min(time), max(time) FROM candles WHERE ticker='SBER' AND timeframe='MINUTE_10';
```

### Метрики Prometheus

```bash
curl http://localhost:8080/actuator/prometheus | grep -E "bot_|llm_|strategy_|outbox_|alor_"
```

### Ручной запуск цикла

```bash
curl -X POST http://localhost:8080/api/v1/strategy/trigger   # стратегии
curl -X POST http://localhost:8080/api/v1/bot/trigger        # бот (открытие)
```

## 12.3. FAQ

**Q: Почему `MAX_OPEN_POS=0`?**
A: Сознательная страховка: бот генерирует стратегии, но не открывает позиции. Для боекомбинации поставьте `1`, затем увеличивайте.

**Q: Что означают статусы позиций TAKE_PROFIT vs CLOSED?**
A: `TAKE_PROFIT` ставится при закрытии по тейку, `CLOSED` — по SL/strategy/другое. При WS-fill без известной причины ставится `CLOSED` с `closeReason=EXECUTION_FILL`.

**Q: Бот использует Hibernate?**
A: Нет. Репозитории на R2DBC (`DatabaseClient`, все методы suspend). JDBC (`spring-boot-starter-jdbc` + `spring.datasource.*`) используется только Liquibase для миграций схемы.

**Q: R2DBC: «Cannot decode value of type long/int with OID 20/23» при чтении колонки?**
A: Kotlin `Long::class.java`/`Int::class.java`/`Boolean::class.java`/`Double::class.java` возвращают примитивные JVM-классы (`long`/`int`), которых нет в codec-карте r2dbc-postgresql. Используйте `Long::class.javaObjectType` (и аналогично для остальных примитивов). Также для void-запросов (`.then()`) вместо `awaitSingle()` используйте `awaitSingleOrNull()` — `.then()` завершается пустым Mono, и `awaitSingle()` бросает `NoSuchElementException`.

**Q: Можно ли запускать 2 экземпляра бота?**
A: Нет, см. раздел 2.6 (singleton). Если очень нужно — внедрить distributed lock.

**Q: Что если Kimi API недоступен неделю?**
A: Бот продолжит работу на детерминированных fallback: тех. анализ по индикаторам (confidence 0.55), стратег → HOLD (без LLM не торгует), арбитр → HOLD. Без LLM бот фактически не открывает сделки — это безопасно, но не прибыльно.

**Q: Как посмотреть стоимость LLM за день?**
A: `sum(increase(llm_tokens_used_total[24h]))` × цена за 1K токенов.

**Q: Где хранится daily PnL?**
A: В памяти `RiskManagementService` (теряется при рестарте). Перенос в БД — roadmap.

**Q: Что такое sector concentration и как его отключить?**
A: Ограничение числа открытых позиций в одном секторе (по умолчанию 2) — `risk.max-sector-exposure`. Отключить: `risk.max-sector-exposure=0` или `risk.enabled=false`. Справочник секторов: `risk.sectors` в `application.yml`.

**Q: Почему бот не открывает позицию при ATR% > 5%?**
A: `risk.max-volatility-percent=5.0` — вход запрещён при экстремальной волатильности. Поднять лимит: `risk.max-volatility-percent=8.0` (осторожно: стопы 2% будут чаще пробиваться).

**Q: Бэктест учитывает комиссии и проскальзывание?**
A: Да. Комиссия 0.05% на оборот (entry+exit), проскальзывание 0.1% для market-ордеров (раздел 11.4).

**Q: Бэктест использует LLM-агентов?**
A: Нет, сигналы детерминированные (RSI + MACD). LLM-интеграция в бэктесте — roadmap (раздел 11.8).

**Q: Почему бот должен работать в одной реплике?**
A: Две реплики могут открыть две позиции по одному сигналу (гонка). Подробно — раздел 2.6.

**Q: Как проверить прохождение бэктестом критериев приёма?**
A: `BacktestResult.isPassable()`: Sharpe > 1.2, MDD < 15%, PF > 1.3, ≥ 200 сделок. Все 4 обязательны.

**Q: Что делать, если ордер заблокирован WIDE_SPREAD?**
A: Это защита от покупки по разорванному стакану (спред > 0.5%). Обычно достаточно подождать цикл — спред сойдётся. Если повторяется — снизить размер ордера или проверить ликвидность тикера.

**Q: Почему в логах `Volatility guard: ... strategy -> HOLD`?**
A: ATR% от текущей цены превысил `risk.max-volatility-percent` (5.0). Это защита от входа в экстремально волатильный инструмент. Поднять лимит — `risk.max-volatility-percent` (раздел 5.4).

**Q: Как понять, что сработал секторный лимит?**
A: Лог `Sector concentration exceeded: ... in sector FINANCE >= max 2` + метрика `risk.sector.exceeded`. Подробнее — раздел 5.3.

**Q: Бэктест — это бесплатный способ проверить стратегию?**
A: Да, он не ходит в LLM и не размещает ордера. Единственный ресурс — чтение свечей из БД. Ограничение: сигналы детерминированные (RSI/MACD), поэтому он проверяет индикаторную логику, а не LLM-решения.

**Q: Почему при `MAX_OPEN_POS>0` в SIMULATION бот открывает позиции в БД?**
A: SIMULATION заменяет только исполнение ордеров (фиктивные цены/ордера), но не управление позициями — они пишутся в PostgreSQL. Это позволяет тестировать весь цикл, включая мониторинг и P&L.

**Q: Что значит `dailyPnl <= -5000` в метрике `bot.halted.daily_loss`?**
A: Сработал дневной лимит убытка: дневной P&L опустился до `-maxDailyLossRub`. Бот переходит в HALT до конца торгового дня (значение персистится в `daily_risk_snapshot`, сброс — по календарной дате 00:00 МСК, раздел 5.6).

**Q: Как проверить, что event-driven слой работает?**
A: Метрики `event.published` и `event.handled` по типам. Должны быть близки (потери = рестарт между публикацией и обработкой). Раздел 9.5.

## 12.4. Симптом → диагноз → действие (быстрая таблица)

| Симптом | Диагноз | Действие |
|---|---|---|
| `bot.halted.daily_loss` > 0 | дневной лимит достигнут | дождаться сброса / перезапустить |
| `alor.ws.disconnected{reason=MAX_ATTEMPTS}` | WS не переподключился | проверить сеть/токены, рестарт |
| `outbox` много PENDING | worker или Alor недоступен | логи AlorClient, метрика `outbox.failed` |
| `llm.circuit_open` | CB открыт (50% ошибок за окно) | подождать `waitDurationInOpenState` (20 c) |
| `llm.fallback.activated{reason=CALL_ERROR}` | LLM недоступен | проверить `KIMI_API_KEY`, сеть до `api.moonshot.cn` |
| `strategy.skipped{ticker}` | тикер на паузе по статистике | `adaptive.pause`, `analytics/health` |
| `risk.volatility.blocked` | ATR% > 5% | поднять лимит или дождаться снижения волатильности |
| `risk.sector.exceeded` | концентрация в секторе | дождаться закрытия позиции или поднять `max-sector-exposure` |
| `bt_pass_total{result="REJECT"}` | бэктест не прошёл критерии | правка параметров стратегии |
| `event.handled` < `event.published` | потеря событий (рестарт) | резерв — Redis `strategy:<ticker>` TTL 15 мин |

## 12.5. Аварийные процедуры

### Остановка торговли

1. `POST /api/v1/bot/emergency-stop` — блокирует входы, опционально ликвидирует позиции (`{"liquidate": true}`). Снятие — `POST /api/v1/bot/resume` (раздел 5.8).
2. `kubectl scale deploy/trading-bot --replicas=0` (или `docker compose stop app`).
3. Перезапуск с `MAX_OPEN_POS=0` — новые входы запрещены, открытые позиции мониторятся и закрываются.
4. При необходимости закрыть позиции вручную через Alor-терминал (не через бота).

### После аварии

1. Собрать: `kubectl logs`, дамп метрик, `SELECT * FROM order_outbox WHERE status='PENDING'`.
2. Проверить целостность позиций: `SELECT ticker, status, pnl FROM positions WHERE status='OPEN'`.
3. Дневной P&L после рестарта восстанавливается из `daily_risk_snapshot` — при повторном включении лимит не «обнуляется» (раздел 5.6).

## 12.6. Распределение ответственности (RACI)

| Задача | Разработчик | DevOps | Трейдер/Аналитик |
|---|---|---|---|
| Мониторинг позиций/P&L | C | A | R |
| Реагирование на алерты | C | A | R |
| Обновление промптов агентов | R | C | C |
| Изменение риск-лимитов | C | C | R |
| Деплой новой версии | R | A | C |
| Проверка бэктестов перед боем | R | — | A |
| Аудит секретов | C | R | — |

R = responsible, A = accountable, C = consulted.

## 12.7. Известные ограничения (не баги)

| Ограничение | Раздел | Влияние |
|---|---|---|
| Дневной P&L в БД (daily_risk_snapshot) | 5.6 | восстанавливается после рестарта; сброс по календарной дате 00:00 МСК |
| Emergency stop — нет авто-остановки по убытку | 5.8 | ручной emergency stop доступен; авто-стоп (source=AUTO) — roadmap |
| Бэктест без LLM | 11.8 | индикаторные сигналы, не LLM-решения |
| Бэктест: вход по market (с slippage) | 11.3 | limit-входы не используются в цикле |
| Позиция только в одной реплике | 2.6 | мульти-реплика требует distributed lock |
| Партиционирование candles не выполнено | 6.4 | рост таблицы ~42 000 строк/мес |
| Сектор `UNKNOWN` для тикеров без маппинга | 5.3 | справочник `risk.sectors` нужно дополнять |
| Мета-агент feedback через LLM имеет fallback на rule-based | 3.x | при недоступности LLM используется rule-based |
