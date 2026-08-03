# 🤖 MMVB AI Trading Bot

**Мультиагентная торговая система для Московской биржи (ММВБ) с ИИ-аналитикой.**

Система работает по принципу **7 агентов**, каждый из которых использует LLM (Kimi k3) для принятия решений. На выходе от ИИ — только одно из трёх: **BUY**, **SELL** или **HOLD** + конкретная цена.

---

## 📋 Содержание

- [Что это такое](#что-это-такое)
- [Архитектура 7 агентов](#архитектура-7-агентов)
- [Поток данных](#поток-данных)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Настройка](#настройка)
- [Режимы работы](#режимы-работы)
- [Как работает бот](#как-работает-бот)
- [Риск-менеджмент](#риск-менеджмент)
- [API](#api)
- [React UI](#react-ui)
- [Устранение неполадок](#устранение-неполадок)
- [Лицензия](#лицензия)

---

## Что это такое

Это **монолитное** Spring Boot приложение на Kotlin, которое:

1. **Каждые N минут** (настраивается) пересчитывает торговую стратегию через 5 ИИ-агентов
2. **Каждые M минут** (настраивается) проверяет — есть ли открытые позиции
3. **Если открытых позиций 0** (или меньше лимита — настраивается) — спрашивает у ИИ: покупать, продавать или ждать
4. **Открывает позицию** через брокера [Алор](https://alor.ru)
5. **Каждые K минут** (настраивается) пересматривает стоп-лоссы и тейк-профиты открытых позиций на основе свежей стратегии
6. **Всё сохраняет** в PostgreSQL, актуальную стратегию — в Redis, UI — на React

---

## Архитектура 7 агентов

| # | Агент | Что делает | LLM | Вход | Выход |
|---|-------|-----------|-----|------|-------|
| **1** | **Technical Analysis** | Анализирует графики | Kimi k3 | Свечи (MOEX/Алор), RSI, MACD, BB, ATR | JSON: тренд, уровни, паттерн, вывод |
| **2** | **Fundamental Analysis** | Анализирует компанию | Kimi k3 | Макро ЦБ РФ, сектор, отчётность | JSON: P/E, P/B, EPS, дивиденды, вывод |
| **3** | **Strategist** | Синтезирует стратегию | Kimi k3 | Отчёты агентов 1+2, текущая цена | JSON: BUY/SELL/HOLD, цена, стопы |
| **4** | **Contrarian** | Оспаривает стратегию | Kimi k3 | Стратегия + все данные | Текст: слабые места, риски |
| **5** | **Arbitrator** | **Принимает финальное решение** | Kimi k3 | Стратегия + контраргументы | **BUY/SELL/HOLD + цена X** |
| **6** | **Executor** | Выставляет заявки | — | Решение агента 5 | Заявка в Алор |
| **7** | **Monitor** | Корректирует стопы/тейки | — | Цена каждые 10 мин | Новые стоп-лоссы и тейк-профиты |

> **Правило:** Агент 5 — единственный, кто решает **BUY/SELL/HOLD**. Никакой хардкод стратегии. Каждое решение — динамическое, на основе текущих данных.

---

## Поток данных

```
┌─────────────┐     ┌─────────────┐
│  MOEX ISS   │     │   Алор API  │
│(история для │     │(котировки  │
│  тестов)    │     │  + заявки) │
└──────┬──────┘     └──────┬──────┘
       │                   │
       ▼                   ▼
┌─────────────────────────────────────┐
│      StrategyService (каждые       │
│      10 мин — настраивается)        │
│  ┌─────────┐    ┌─────────────┐    │
│  │ Agent 1 │    │   Agent 2   │    │
│  │  Tech   │    │   Fund      │    │
│  └────┬────┘    └──────┬──────┘    │
│       └────────┬─────────┘           │
│                ▼                     │
│         ┌─────────────┐              │
│         │  Agent 3    │              │
│         │ Strategist  │              │
│         └──────┬──────┘              │
│                ▼                     │
│         ┌─────────────┐              │
│         │  Agent 4    │              │
│         │ Contrarian  │              │
│         └──────┬──────┘              │
│                ▼                     │
│         ┌─────────────┐              │
│         │  Agent 5    │              │
│         │ Arbitrator  │──► Strategy   │
│         │  (FINAL)    │    (BUY/SELL/ │
│         └─────────────┘     HOLD)     │
└─────────────────────────────────────┘
                   │
                   ▼
            ┌──────────┐
            │  Redis   │ ◄──── TradingBotService читает отсюда
            │   +      │       (каждые 5 мин — настраивается)
            │   DB     │
            └────┬─────┘
                 │
                 ▼
┌─────────────────────────────────────┐
│     TradingBotService (каждые       │
│      5 мин — настраивается)         │
│                                     │
│  IF open_positions < max_limit:     │
│     READ strategy from Redis        │
│     IF strategy == BUY/SELL:        │
│        OPEN position via Алор       │
│                                     │
│  FOR EACH open position:            │
│     READ fresh strategy from Redis   │
│     ADJUST stop-loss / take-profit  │
│     CHECK trailing-stop             │
└─────────────────────────────────────┘
```

---

## Требования

### Железо
- **CPU:** 2+ ядра
- **RAM:** 4 GB минимум (8 GB рекомендуется)
- **Диск:** 10 GB

### Софт
- **JDK 21** (Eclipse Temurin рекомендуется)
- **Docker + Docker Compose** (опционально, но рекомендуется)
- **Node.js 20** (для сборки React UI)
- **Gradle 8.5** (wrapper включён)

### API ключи
1. **Kimi k3 (Moonshot AI)** — для работы всех ИИ-агентов
   - Получить: [platform.moonshot.cn](https://platform.moonshot.cn)
   - Нужен API Key вида `sk-...`

2. **Алор Брокер** — для реальной торговли (в SIMULATION не нужен)
   - Получить: [alor.ru](https://alor.ru) → Личный кабинет → API
   - Нужны: `token`, `refreshToken`, `portfolio` (номер счёта вида `D12345`)

---

## Быстрый старт

### Шаг 1. Клонировать / распаковать

```bash
cd mmvb-trading-bot-v3
```

### Шаг 2. Запустить инфраструктуру (PostgreSQL + Redis)

```bash
docker-compose up -d postgres redis
```

Проверить:
```bash
docker ps
# Должны быть контейнеры postgres и redis
```

### Шаг 3. Настроить переменные окружения

```bash
export KIMI_API_KEY="sk-your-key-here"
export ALOR_TOKEN="your-alor-token"
export ALOR_REFRESH_TOKEN="your-refresh-token"
export ALOR_PORTFOLIO="D12345"
export TRADING_MODE="SIMULATION"   # SIMULATION или PRODUCTION
```

> **Важно:** В режиме `SIMULATION` токен Алор **не нужен**. Бот будет генерировать случайные цены и не выходить на биржу.

### Шаг 4. Собрать и запустить backend

```bash
./gradlew bootRun
```

Или через Docker:
```bash
docker-compose up -d bot
```

### Шаг 5. Собрать и запустить React UI

```bash
cd frontend
npm install
npm start
```

Открыть в браузере: **http://localhost:3000**

> UI работает на порту 3000, backend API — на порту 8080. React проксирует `/api` на backend автоматически (через `proxy` в `package.json`).

---

## Настройка

Все настройки можно менять **тремя способами**:

### 1. Через UI (React)

Откройте вкладку **«Настройки»** в UI. Измените значения, нажмите **«Сохранить»**. Настройки мгновенно сохраняются в Redis и применяются к следующему циклу.

### 2. Через переменные окружения

| Переменная | Описание | По умолчанию |
|------------|----------|--------------|
| `KIMI_API_KEY` | API ключ Kimi k3 | — **обязательно** |
| `KIMI_MODEL` | Модель LLM | `kimi-k3` |
| `ALOR_TOKEN` | Токен Алор | — |
| `ALOR_REFRESH_TOKEN` | Refresh токен Алор | — |
| `ALOR_PORTFOLIO` | Номер портфеля | `D12345` |
| `TRADING_MODE` | `SIMULATION` или `PRODUCTION` | `SIMULATION` |
| `BOT_INTERVAL_MS` | Интервал работы бота (мс) | `300000` (5 мин) |
| `STRATEGY_INTERVAL_MS` | Интервал пересчёта стратегии (мс) | `600000` (10 мин) |
| `MONITOR_INTERVAL_MS` | Интервал мониторинга позиций (мс) | `600000` (10 мин) |
| `MAX_OPEN_POS` | Макс. открытых позиций для НОВОГО входа | `0` (входим только при 0 позиций) |
| `DB_USER` | Пользователь PostgreSQL | `trader` |
| `DB_PASS` | Пароль PostgreSQL | `trader` |

### 3. Через `application.yml`

Файл `src/main/resources/application.yml` — базовая конфигурация. Переменные окружения **переопределяют** значения из YAML.

### Примеры настроек

**Консервативный режим (только 1 позиция, частые проверки):**
```bash
export BOT_INTERVAL_MS=300000        # 5 мин
export STRATEGY_INTERVAL_MS=600000   # 10 мин
export MONITOR_INTERVAL_MS=300000      # 5 мин
export MAX_OPEN_POS=0                  # входим только если 0 позиций
export TRADING_MODE=SIMULATION
```

**Агрессивный режим (до 3 позиций):**
```bash
export BOT_INTERVAL_MS=120000        # 2 мин
export STRATEGY_INTERVAL_MS=300000   # 5 мин
export MONITOR_INTERVAL_MS=300000    # 5 мин
export MAX_OPEN_POS=3                # входим пока позиций < 3
export TRADING_MODE=PRODUCTION
```

---

## Режимы работы

### SIMULATION (по умолчанию)

- ❌ Не выходит на биржу
- ❌ Не нужен токен Алор
- ✅ Генерирует случайные цены для тестирования
- ✅ Все 7 агентов работают полностью
- ✅ Сохраняет позиции в БД (как "виртуальные")
- ✅ Показывает P&L в UI

**Используйте для:** отладки, тестирования стратегий, проверки логики без риска.

### PRODUCTION

- ✅ Реальные заявки через Алор API
- ✅ Реальные котировки с ММВБ
- ✅ Реальные деньги на кону
- ⚠️ **Требуется токен Алор**

**Перед запуском в PRODUCTION:**
1. Убедитесь, что `TRADING_MODE=PRODUCTION`
2. Проверьте токен Алор: `export ALOR_TOKEN=...`
3. Установите консервативные лимиты (`MAX_OPEN_POS=0`, небольшие суммы)
4. Запустите на 1-2 часа в SIMULATION, убедитесь в стабильности

---

## Как работает бот

### Алгоритм работы TradingBotService (каждые 5 мин)

```kotlin
1. Получить список открытых позиций из БД
2. Получить актуальные стратегии из Redis (для всех тикеров)

3. ДЛЯ КАЖДОЙ открытой позиции:
   а) Получить текущую цену от Алор
   б) Пересчитать P&L
   в) Проверить стоп-лосс / тейк-профит
   г) Если сработал — закрыть позицию
   д) Прочитать СВЕЖУЮ стратегию из Redis
   е) Если стратегия рекомендует новые стоп/тейк — обновить
   ё) Если стратегия == CLOSE — закрыть позицию

4. ЕСЛИ открытых позиций < MAX_OPEN_POS:
   а) Для каждого тикера прочитать стратегию из Redis
   б) Если стратегия == BUY или SELL:
      - Проверить риск-менеджмент
      - Выставить заявку в Алор
      - Сохранить позицию в БД
      - Запустить мониторинг

5. Сохранить статистику
```

### Алгоритм работы StrategyService (каждые 10 мин)

```kotlin
1. Для каждого тикера:
   а) Загрузить исторические свечи (MOEX → БД)
   б) Получить текущую цену (Алор)

   в) Agent 1: Технический анализ (LLM)
   г) Agent 2: Фундаментальный анализ (LLM)
   д) Agent 3: Стратегия (LLM)
   е) Agent 4: Контраргументы (LLM)
   ё) Agent 5: Финальное решение (LLM) → BUY/SELL/HOLD + цена

2. Сохранить стратегию:
   - В PostgreSQL (история)
   - В Redis (актуальная для бота)

3. Бот-трейдер при следующем цикле прочитает её из Redis
```

---

## Риск-менеджмент

Риск-менеджмент работает **автоматически** и **не допускает** сделку, если:

| Правило | Действие |
|---------|----------|
| Дневной убыток > лимита | ❌ Блокировка новых входов |
| Открытых позиций ≥ MAX_OPEN_POS | ❌ Блокировка новых входов |
| Уверенность ИИ < 60% | ❌ Блокировка сделки |
| Размер позиции > maxPositionRub | ⚠️ Уменьшение количества лотов |
| Стоп-лосс сработал | 🔴 Автоматическое закрытие |
| Тейк-профит сработал | 🟢 Автоматическое закрытие |
| Трейлинг-стоп сработал | 🟡 Автоматическое закрытие |

**Все лимиты настраиваются через UI или переменные окружения.**

---

## API

### REST Endpoints

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/v1/settings` | Текущие настройки |
| `POST` | `/api/v1/settings` | Обновить настройки |
| `GET` | `/api/v1/strategies` | Последние 50 стратегий |
| `GET` | `/api/v1/strategies/{ticker}` | Актуальная стратегия (Redis → БД) |
| `GET` | `/api/v1/positions` | Открытые позиции |
| `GET` | `/api/v1/positions/all` | Все позиции |
| `GET` | `/api/v1/logs` | Последние 100 логов агентов |
| `GET` | `/api/v1/risk/daily-pnl` | Дневной P&L |
| `POST` | `/api/v1/strategy/trigger` | 🔄 Принудительно пересчитать стратегии |
| `POST` | `/api/v1/bot/trigger` | ⚡ Принудительно запустить бота |

### Пример запроса

```bash
curl http://localhost:8080/api/v1/strategies/SBER

# Ответ:
{
  "ticker": "SBER",
  "action": "BUY",
  "targetPrice": 312.50,
  "quantity": 10,
  "stopLoss": 306.25,
  "takeProfit": 325.00,
  "confidence": 0.82,
  "reasoning": "RSI перепродан, MACD пересечение вверх, фундаментал недооценен"
}
```

---

## React UI

### Вкладки

1. **Дашборд** — текущее состояние:
   - Дневной P&L
   - Количество открытых позиций
   - Активные стратегии (BUY/SELL/HOLD)
   - Кнопки «Пересчитать стратегии» и «Запустить бота»
   - Таблица позиций с текущими стопами/тейками

2. **Настройки** — все параметры системы:
   - Интервалы (бот, стратегия, мониторинг)
   - Условия входа (макс. позиций, лимиты)
   - Стоп-лоссы и тейк-профиты (%)
   - Трейлинг-стоп

3. **Логи** — журнал работы всех 7 агентов с таймстампами

### Скриншот структуры UI

```
┌─────────────────────────────────────────────┐
│  🤖 MMVB AI Trading Bot    [Дашборд][Настройки][Логи] │
├─────────────────────────────────────────────┤
│  P&L: +1,250 ₽  |  Позиций: 0  |  Стратегий: 3  │
├─────────────────────────────────────────────┤
│  [🔄 Пересчитать] [⚡ Запустить бота]         │
├─────────────────────────────────────────────┤
│  Активные стратегии (из Redis)                │
│  ┌──────┬──────┬────────┬───────┬─────────┐  │
│  │Тикер │Действ│ Цена   │ Стоп  │ Тейк    │  │
│  ├──────┼──────┼────────┼───────┼─────────┤  │
│  │ SBER │ BUY  │ 312.50 │ 306.25│ 325.00  │  │
│  │ GAZP │ HOLD │   —    │   —   │   —     │  │
│  └──────┴──────┴────────┴───────┴─────────┘  │
├─────────────────────────────────────────────┤
│  Открытые позиции                             │
│  ┌──────┬────┬─────┬───────┬──────┬──────┐  │
│  │Тикер │Dir │Кол-во│ Вход  │ Стоп │ Тейк │  │
│  └──────┴────┴─────┴───────┴──────┴──────┘  │
└─────────────────────────────────────────────┘
```

---

## Устранение неполадок

### «Не запускается backend»

```bash
# Проверьте Java
java -version  # Должно быть 21

# Проверьте PostgreSQL
docker-compose ps
# Если postgres не запущен:
docker-compose up -d postgres redis

# Проверьте порт 8080
lsof -i :8080  # Если занят — убейте процесс или поменяйте порт
```

### «Kimi API error»

- Проверьте `KIMI_API_KEY` — должен начинаться с `sk-`
- Проверьте баланс на [platform.moonshot.cn](https://platform.moonshot.cn)
- Увеличьте `timeout-sec` в `application.yml`

### «Алор API error»

- В режиме SIMULATION это нормально — токен не нужен
- В PRODUCTION проверьте:
  ```bash
  curl -H "Authorization: Bearer $ALOR_TOKEN" https://api.alor.ru/md/v2/Securities/MOEX/SBER/quotes
  ```

### «React не подключается к API»

- Убедитесь, что backend запущен на `localhost:8080`
- Проверьте `proxy` в `frontend/package.json` — должен быть `"http://localhost:8080"`
- CORS включён для всех origins (`@CrossOrigin(origins = ["*"])`)

### «Стратегии не обновляются»

- Проверьте Redis: `docker exec -it <redis> redis-cli KEYS "strategy:*"`
- Проверьте логи: `docker logs mmvb-bot`
- Убедитесь, что `KIMI_API_KEY` валиден

---



---

## 🧪 Тесты и бэктестинг (Testcontainers)

Проект использует **Testcontainers** — тесты запускаются с реальными PostgreSQL и Redis в Docker-контейнерах. Это гарантирует полную совместимость с продакшеном.

### Требования для тестов

- **Docker** должен быть запущен
- Testcontainers автоматически скачает `postgres:16-alpine` и `redis:7-alpine`

### Что тестируется

| Тест | Описание |
|------|----------|
| `BacktestIntegrationTest` | Прогон стратегии за 2 года (настраивается) на реальных/мок данных |
| Метрики | Win Rate, Total Return, Max Drawdown, Sharpe Ratio, Profit Factor |

### Запуск тестов

```bash
# Все тесты (автоматически поднимет PostgreSQL + Redis в Docker)
./gradlew test

# Только бэктест
./gradlew test --tests "com.trading.bot.integration.BacktestIntegrationTest"

# С подробным выводом
./gradlew test --info
```

### Настройка периода бэктеста

В `src/test/resources/application-test.yml`:

```yaml
trading:
  backtest-years: 2   # <-- меняйте здесь (1, 2, 5, 10 лет)
```

Или через переменную окружения:
```bash
export BACKTEST_YEARS=5
./gradlew test
```

### Что делает бэктест

1. **Загружает свечи** — сначала пытается получить реальные данные из MOEX ISS, если недоступно — генерирует мок-данные
2. **Проходит по истории** — на каждом шаге проверяет сигнал (SMA-пересечение для теста)
3. **Симулирует сделки** — с учётом комиссии (0.05%) и проскальзывания (0.02%)
4. **Считает метрики**:
   - **Total Return** — итоговая доходность
   - **Win Rate** — процент прибыльных сделок
   - **Max Drawdown** — максимальная просадка
   - **Sharpe Ratio** — соотношение доходности к волатильности
   - **Profit Factor** — отношение прибыли к убытку

### Пример вывода

```
╔══════════════════════════════════════════════════════╗
║           BACKTEST REPORT: SBER                      ║
╠══════════════════════════════════════════════════════╣
║  Period:     2024-07-30 → 2026-07-30                 ║
║  Trades:     47 (Win: 25, Loss: 22)                 ║
║  Win Rate:   53.2%                                   ║
║  Return:     125400 ₽ (12.54%)                       ║
║  Max DD:     23100 ₽ (2.31%)                         ║
║  Sharpe:     1.45                                    ║
║  P.Factor:   1.62                                    ║
╚══════════════════════════════════════════════════════╝
```

### Тестовая архитектура

```
┌─────────────────────────────────────────────┐
│         Gradle Test Task                    │
│  ┌─────────────────────────────────────┐   │
│  │  AbstractTestContainerTest          │   │
│  │  ┌──────────────┐  ┌──────────────┐│   │
│  │  │ PostgreSQL   │  │ Redis        ││   │
│  │  │ Container    │  │ Container    ││   │
│  │  │ (testcontainers)│ (testcontainers)│   │
│  │  └──────────────┘  └──────────────┘│   │
│  └─────────────────────────────────────┘   │
│              │                              │
│  ┌───────────▼──────────────┐             │
│  │ BacktestIntegrationTest  │             │
│  │ - Real DB + Redis        │             │
│  │ - Mock LLM (no tokens)  │             │
│  │ - MOEX or mock candles   │             │
│  └──────────────────────────┘             │
└─────────────────────────────────────────────┘
```

### Почему Testcontainers, а не H2

| | H2 In-Memory | Testcontainers PostgreSQL |
|---|-------------|--------------------------|
| Совместимость | ⚠️ Различия с PostgreSQL | ✅ Идентично продакшену |
| Типы данных | ⚠️ Ограничены | ✅ Полная поддержка |
| JSON/Array | ❌ Нет | ✅ Есть |
| Миграции | ❌ Не проверяются | ✅ Проверяются |
| Redis | ❌ Нет | ✅ Реальный Redis |

Файлы:
- `src/test/kotlin/.../integration/AbstractTestContainerTest.kt` — базовый класс
- `src/test/kotlin/.../integration/BacktestIntegrationTest.kt` — тесты
- `src/test/resources/application-test.yml` — конфигурация



---

## ☁️ Деплой на Cloud.ru

Этот раздел описывает полный цикл развёртывания на облачной платформе [Cloud.ru](https://cloud.ru).

### Варианты деплоя

| Вариант | Сложность | Подходит для |
|---------|-----------|--------------|
| **A. Docker Compose на VPS** | ⭐ Лёгкий | Тестирование, небольшие объёмы |
| **B. Kubernetes (Cloud Containers)** | ⭐⭐ Средний | Продакшен, масштабирование |
| **C. CI/CD через GitHub Actions** | ⭐⭐⭐ Автоматический | Команда, частые релизы |

---

### Вариант A: Docker Compose на Cloud.ru VPS

#### Шаг 1. Создать сервер

1. Зайдите в [панель Cloud.ru](https://console.cloud.ru)
2. **Compute Cloud** → **Создать ВМ**
3. Параметры:
   - **ОС:** Ubuntu 22.04 LTS
   - **Конфигурация:** 2 vCPU, 4 GB RAM (минимум)
   - **Диск:** 50 GB SSD
   - **Сеть:** публичный IP, группа безопасности с портами 22, 80, 443, 8080
4. Подключитесь по SSH:
   ```bash
   ssh ubuntu@<your-vm-ip>
   ```

#### Шаг 2. Установить Docker

```bash
# Обновление системы
sudo apt update && sudo apt upgrade -y

# Установка Docker
sudo apt install -y docker.io docker-compose-plugin
sudo systemctl enable docker
sudo systemctl start docker
sudo usermod -aG docker $USER
# Перелогиньтесь: exit && ssh ubuntu@<ip>

# Проверка
docker --version
docker compose version
```

#### Шаг 3. Клонировать проект

```bash
git clone <your-repo-url> mmvb-trading-bot
cd mmvb-trading-bot
```

#### Шаг 4. Создать .env файл

```bash
cat > .env << 'EOF'
# Database (можно использовать Managed PostgreSQL от Cloud.ru)
DB_URL=jdbc:postgresql://postgres:5432/trading_bot
DB_USER=trader
DB_PASS=your-strong-password-here

# Redis (можно использовать Managed Redis от Cloud.ru)
REDIS_HOST=redis
REDIS_PORT=6379

# API Keys
KIMI_API_KEY=sk-your-kimi-key
ALOR_TOKEN=your-alor-token
ALOR_REFRESH_TOKEN=your-refresh-token
ALOR_PORTFOLIO=D12345

# Trading
TRADING_MODE=SIMULATION
BOT_INTERVAL_MS=300000
STRATEGY_INTERVAL_MS=600000
MONITOR_INTERVAL_MS=600000
MAX_OPEN_POS=0
EOF
```

> **Важно:** Для продакшена используйте **Managed PostgreSQL** и **Managed Redis** от Cloud.ru вместо локальных контейнеров. Это надёжнее и быстрее.

#### Шаг 5. Запустить

```bash
# С локальной БД и Redis
docker compose -f docker-compose.cloud.yml --profile local-db --profile local-redis up -d

# Или если используете Managed DB (только бот + nginx):
docker compose -f docker-compose.cloud.yml up -d
```

#### Шаг 6. Проверить

```bash
# Логи бота
docker logs -f mmvb-bot

# Статус
docker ps

# API доступен на порту 8080
curl http://localhost:8080/api/v1/settings
```

---

### Вариант B: Kubernetes (Cloud Containers)

#### Шаг 1. Создать кластер Kubernetes

1. **Cloud.ru Console** → **Cloud Containers** → **Создать кластер**
2. Параметры:
   - **Версия:** 1.29+
   - **Ноды:** 2+ (2 vCPU, 4 GB RAM каждая)
   - **Сеть:** публичный доступ
3. Скачайте `kubeconfig` и сохраните:
   ```bash
   mkdir -p ~/.kube
   cp ~/Downloads/kubeconfig ~/.kube/config
   kubectl get nodes
   ```

#### Шаг 2. Настроить Container Registry

1. **Cloud.ru Console** → **Container Registry** → **Создать реестр**
2. Создайте репозиторий `mmvb-trading-bot`
3. Получите логин/пароль для registry

#### Шаг 3. Собрать и загрузить образ

```bash
# Логин в registry
docker login cr.cloud.ru -u <your-username> -p <your-password>

# Сборка
docker build -t cr.cloud.ru/mmvb-trading-bot:latest .

# Пуш
docker push cr.cloud.ru/mmvb-trading-bot:latest
```

#### Шаг 4. Настроить секреты

```bash
# Закодируйте значения в base64
echo -n 'your-db-password' | base64
echo -n 'sk-your-kimi-key' | base64

# Отредактируйте k8s/secret.yaml, заменив значения
nano k8s/secret.yaml

# Применить
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml
```

#### Шаг 5. Настроить Ingress и TLS

```bash
# Установить cert-manager (если ещё не установлен)
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.0/cert-manager.yaml

# Настроить ClusterIssuer для Let's Encrypt
kubectl apply -f - << 'EOF'
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: your-email@example.com
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
      - http01:
          ingress:
            class: nginx
EOF

# Обновить ingress.yaml — замените your-domain.cloud.ru
nano k8s/ingress.yaml

# Применить всё
kubectl apply -f k8s/
```

#### Шаг 6. Проверить деплой

```bash
# Статус подов
kubectl get pods -n mmvb-trading

# Логи
kubectl logs -f deployment/mmvb-bot -n mmvb-trading

# Сервисы
kubectl get svc -n mmvb-trading

# Доступ через браузер
open https://your-domain.cloud.ru
```

---

### Вариант C: CI/CD через GitHub Actions

#### Шаг 1. Настроить секреты в GitHub

В репозитории: **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

| Секрет | Значение |
|--------|----------|
| `CLOUDRU_REGISTRY_USERNAME` | Логин от Container Registry |
| `CLOUDRU_REGISTRY_PASSWORD` | Пароль от Container Registry |
| `CLOUDRU_KUBECONFIG` | Base64-encoded kubeconfig |
| `KIMI_API_KEY` | API ключ Kimi |

Закодировать kubeconfig:
```bash
cat ~/.kube/config | base64 | pbcopy  # macOS
cat ~/.kube/config | base64 -w0 | xclip -selection clipboard  # Linux
```

#### Шаг 2. Пуш в main

```bash
git add .
git commit -m "Deploy to Cloud.ru"
git push origin main
```

GitHub Actions автоматически:
1. Запустит тесты (Testcontainers)
2. Соберёт Docker образ
3. Загрузит в `cr.cloud.ru`
4. Обновит Kubernetes deployment

#### Шаг 3. Мониторинг CI/CD

В GitHub: **Actions** → выберите workflow → посмотрите логи

---

### Рекомендуемая архитектура на Cloud.ru

```
┌─────────────────────────────────────────────────────────────┐
│                        Cloud.ru                              │
│  ┌─────────────────┐    ┌─────────────────┐                │
│  │  Cloud DNS      │    │  Cloud CDN      │                │
│  │  your-domain.ru │◄───│  (статика UI)   │                │
│  └────────┬────────┘    └─────────────────┘                │
│           │                                                  │
│  ┌────────▼────────────────────────────────────────────┐   │
│  │              Ingress Controller (Nginx)              │   │
│  │              TLS (Let's Encrypt / Cloud.ru)        │   │
│  └────────┬─────────────────────────────────────────────┘   │
│           │                                                  │
│  ┌────────▼────────┐    ┌──────────────────────────────┐   │
│  │  mmvb-bot Pod   │    │  mmvb-bot Pod (реплика)      │   │
│  │  (Spring Boot)  │    │  (Spring Boot)               │   │
│  └────────┬────────┘    └──────────┬───────────────────┘   │
│           │                        │                       │
│  ┌────────▼────────┐    ┌──────────▼──────────┐           │
│  │ Managed         │    │ Managed              │           │
│  │ PostgreSQL      │    │ Redis                │           │
│  │ (Cloud.ru)      │    │ (Cloud.ru)           │           │
│  └─────────────────┘    └──────────────────────┘           │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  Monitoring: Prometheus + Grafana (опционально)     │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

---

### Управление секретами на Cloud.ru

**Никогда не храните секреты в коде!**

| Где | Как |
|-----|-----|
| Локально | `.env` файл + `.gitignore` |
| VPS | Docker Secrets или `.env` с chmod 600 |
| Kubernetes | `kubectl create secret` |
| CI/CD | GitHub Secrets |
| Cloud.ru | Vault (если доступен) |

---

### Мониторинг на Cloud.ru

```bash
# Логи пода
kubectl logs -f deployment/mmvb-bot -n mmvb-trading --tail=100

# Метрики JVM
kubectl exec -it deployment/mmvb-bot -n mmvb-trading -- curl localhost:8080/actuator/metrics

# Health check
curl https://your-domain.cloud.ru/actuator/health

# Redis
docker exec -it mmvb-redis redis-cli info

# PostgreSQL
docker exec -it mmvb-postgres psql -U trader -d trading_bot -c "SELECT COUNT(*) FROM positions;"
```

---

### Масштабирование

```bash
# Увеличить реплики бота
kubectl scale deployment mmvb-bot --replicas=3 -n mmvb-trading

# Автомасштабирование (HPA)
kubectl autoscale deployment mmvb-bot --min=2 --max=5 --cpu-percent=70 -n mmvb-trading

# Обновление без downtime
kubectl set image deployment/mmvb-bot bot=cr.cloud.ru/mmvb-trading-bot:v1.1 -n mmvb-trading
kubectl rollout status deployment/mmvb-bot -n mmvb-trading
```

---

### Troubleshooting на Cloud.ru

| Проблема | Решение |
|----------|---------|
| `ImagePullBackOff` | Проверьте логин в registry: `docker login cr.cloud.ru` |
| `CrashLoopBackOff` | `kubectl logs` — проверьте DB_URL и секреты |
| `Pending` под | Недостаточно ресурсов — увеличьте ноды |
| 502 Bad Gateway | Проверьте Ingress + Service + Pod health |
| База не доступна | Проверьте security group — порт 5432 должен быть открыт для кластера |
| Redis timeout | Проверьте network policies и security groups |

---

### Стоимость (ориентировочно)

| Компонент | Cloud.ru | Альтернатива |
|-----------|----------|--------------|
| VPS 2vCPU/4GB | ~3 000 ₽/мес | — |
| Managed PostgreSQL | ~2 000 ₽/мес | Локальный контейнер (бесплатно) |
| Managed Redis | ~1 500 ₽/мес | Локальный контейнер (бесплатно) |
| Kubernetes (2 ноды) | ~6 000 ₽/мес | — |
| Container Registry | ~500 ₽/мес | — |
| **Итого (минимум)** | **~3 000 ₽/мес** | VPS + локальные сервисы |
| **Итого (продакшен)** | **~10 000 ₽/мес** | Managed DB + K8s |


## Лицензия

MIT License

**⚠️ Важно:** Торговля на бирже связана с риском потери капитала. Этот бот предоставляется "как есть". Автор не несёт ответственности за ваши финансовые решения. Всегда начинайте с режима SIMULATION.

---

## Контакты и поддержка

- Алор API: [alor.dev](https://alor.dev)
- Kimi API: [platform.moonshot.cn](https://platform.moonshot.cn)
- MOEX ISS: [iss.moex.com](https://iss.moex.com)
