# 10. Деплой

> **Фактический прод**: Yandex Cloud (VM + Managed PostgreSQL + Managed Redis + Container Registry + docker compose).
> Автодеплой выполняется GitHub Actions после merge в `main`/`master` и прохождения всех тестов
> (`.github/workflows/ci.yml`, job `deploy`).
> Разделы 10.2 (k8s-манифесты) — альтернативный/архивный вариант развёртывания.

## 10.0. Фактический прод: Yandex Cloud

### 10.0.1. Инфраструктура

| Сервис | YC-продукт | Комментарий |
|---|---|---|
| Compute VM | Yandex Compute (Ubuntu 22.04, 2 vCPU / 4 GB, 30 GB) | бот + docker compose |
| PostgreSQL | Yandex Managed PostgreSQL (15, daily backup) | основной боевой БД |
| Redis | Yandex Managed Redis (7, AOF) | semantic cache, стратегии |
| Registry | Yandex Container Registry | образы `trading-bot-app`, `trading-bot-frontend` |
| Мониторинг | Prometheus + Grafana в docker compose на той же VM | `:9090`, `:3000` |

### 10.0.2. Шаги первоначального поднятия

```bash
# 1. Создать VM, реестр, Managed БД (через yc CLI или консоль)
yc compute instance create --name trading-bot \
  --cores 2 --memory 4 --create-boot-disk size=30GB,image-folder-id=standard-images,image-family=ubuntu-2204-lts
yc container registry create --name trading-bot-registry

# 2. На VM: установить docker + docker compose plugin
curl -fsSL https://get.docker.com | sh

# 3. Склонировать репо и поднять compose-стек
mkdir -p /opt/trading-bot && cd /opt/trading-bot
git clone git@github.com:dmitry0923/trading-bot.git .
# .env заполняется workflow при деплое (см. 10.0.4)

# 4. Проверить здоровье
curl http://localhost:8080/actuator/health
curl -u "$AUTH_USER:$AUTH_PASSWORD" http://localhost:8080/api/v1/trading/status
```

### 10.0.3. GitHub Secrets (обязательные)

| Secret | Назначение |
|---|---|
| `YC_FOLDER_ID` | ID каталога YC |
| `YC_REGISTRY_ID` | ID Yandex Container Registry (для `cr.yandex/<id>`) |
| `YC_SA_JSON` | JSON-ключ сервисного аккаунта (права: container registry push, read) |
| `VM_HOST` / `VM_USER` / `VM_SSH_KEY` | SSH-доступ к VM для деплоя |
| `ALOR_TOKEN` / `ALOR_REFRESH_TOKEN` | доступ к Alor |
| `LLM_PROVIDER` | `ROUTER_AI` (по умолчанию) / `KIMI` / `DEEPSEEK` / `QWEN` |
| `LLM_API_KEY` | API-ключ активного LLM-провайдера |
| `TRADING_MODE` | `SIMULATION` (рекоменд.) или `LIVE` |
| `AUTH_USER` / `AUTH_PASSWORD` | UI/API администратор (роль ADMIN) |
| `ANALYTICS_USER` / `ANALYTICS_PASSWORD` | отдельный read-only пользователь аналитики |

### 10.0.4. Pipeline деплоя (`.github/workflows/ci.yml`)

```
push / PR → [backend: ktlint + test + koverVerify] + [frontend: npm build]
        ↓ (needs: оба прошли)
push в main/master → [deploy]: сборка образов → push в YCR → SSH на VM →
        docker compose pull && up -d
```

Ключевые моменты:
- Деплой **не запускается**, пока не пройдут тесты (job `deploy` имеет `needs: [backend, frontend]`).
- `REGISTRY`/`TAG` передаются в удалённую оболочку через `envs: REGISTRY,TAG` (appleboy/ssh-action), иначе compose упадёт с `${REGISTRY:?}`.
- Runtime-секреты пишутся в `/opt/trading-bot/.env`, docker-compose подхватывает их автоматически.
- `docker-compose.prod.yml` отключает сборку на VM — образы тянутся из YCR по тегу `$TAG` (= `$GITHUB_SHA`).


## 10.1. Требования к инфраструктуре

| Сервис | Требование | Комментарий |
|---|---|---|
| Managed Kubernetes | версия 1.28+, 1 node pool (1–2 узла, 2 vCPU / 4 GB) | бот — singleton, нагрузка низкая |
| Managed PostgreSQL | 15, автоматический backup (ежедневный), реплика (RPO ≤ 1 мин) | резервирование данных сделок |
| Managed Redis | 7, cluster mode (1 мастер), AOF persist | semantic cache, стратегии |
| Container Registry | `cr.cloud.ru/...` | хранение Docker-образов |
| Network | egress к api.alor.ru, iss.moex.com, api.moonshot.cn, registry | см. NetworkPolicy |

## 10.2. Kubernetes манифесты

### Namespace

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: trading-bot
```

### ConfigMap (конфиг + промпты)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: bot-config
  namespace: trading-bot
data:
  application.yml: |
    spring:
      datasource:
        url: jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}
      r2dbc:
        url: r2dbc:postgresql://${DB_HOST}:5432/${DB_NAME}
      redis:
        host: ${REDIS_HOST}
    trading:
      mode: ${TRADING_MODE}
      ...
  technical-analysis.yml: |
    prompts:
      default:
        system: >...
        user_template: >...
  # ... остальные 5 промптов
```

> **Hot-reload промптов**: `PromptRegistry.load()` перечитывает файлы из classpath. Чтобы изменения ConfigMap подхватывались без перезапуска пода, монтируйте промпты в директорию на диске и подключайте её в classpath через `spring.config.additional-location` или рабочий каталог. В текущей сборке промпты лежат в jar — обновление требует пересборки (или монтирования поверх classpath через `CLASSPATH` в Docker).

### Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: bot-secrets
  namespace: trading-bot
type: Opaque
stringData:
  DB_PASS: "***"
  ALOR_TOKEN: "***"
  ALOR_REFRESH_TOKEN: "***"
  ALOR_PORTFOLIO: "D12345"
  KIMI_API_KEY: "***"
```

### Deployment (singleton + Recreate)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: trading-bot
  namespace: trading-bot
spec:
  replicas: 1                      # КРИТИЧНО: singleton, см. раздел 2.6
  strategy:
    type: Recreate                  # без rolling: не допускаем 2 пода одновременно
  selector:
    matchLabels:
      app: trading-bot
  template:
    metadata:
      labels:
        app: trading-bot
    spec:
      containers:
        - name: trading-bot
          image: cr.cloud.ru/trading/mmvb-bot:2.0.0
          ports:
            - containerPort: 8080
          envFrom:
            - secretRef:
                name: bot-secrets
          env:
            - name: TRADING_MODE
              value: "SIMULATION"     # LIVE — только через изменение Secret+манифеста
            - name: DB_HOST
              value: "postgres-master.managed.database.cloud.ru"
            - name: REDIS_HOST
              value: "redis.managed.cache.cloud.ru"
            - name: KIMI_MODEL
              value: "kimi-k3"
          volumeMounts:
            - name: config
              mountPath: /workspace/config
            - name: prompts
              mountPath: /workspace/prompts
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 90
            periodSeconds: 30
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 15
          resources:
            requests:
              cpu: 500m
              memory: 512Mi
            limits:
              cpu: "1"
              memory: 1Gi
      volumes:
        - name: config
          configMap:
            name: bot-config
        - name: prompts
          configMap:
            name: bot-prompts
```

### Service (ClusterIP)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: trading-bot
  namespace: trading-bot
spec:
  selector:
    app: trading-bot
  ports:
    - port: 8080
      targetPort: 8080
  type: ClusterIP
```

### ServiceAccount + RBAC

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: trading-bot
  namespace: trading-bot

---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: trading-bot
  namespace: trading-bot
rules:
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["get", "list"]          # только чтение конфигов (для hot-reload промптов)
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: trading-bot
  namespace: trading-bot
subjects:
  - kind: ServiceAccount
    name: trading-bot
    namespace: trading-bot
roleRef:
  kind: Role
  name: trading-bot
  apiGroup: rbac.authorization.k8s.io
```

### NetworkPolicy

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: trading-bot-egress
  namespace: trading-bot
spec:
  podSelector:
    matchLabels:
      app: trading-bot
  policyTypes: [Egress]
  egress:
    - to:
        - namespaceSelector: {}      # доступ к метаданным кластера не нужен
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
      ports:
        - protocol: TCP
          port: 443                  # только HTTPS наружу
```

## 10.3. CI/CD pipeline

> **Актуальный pipeline** — см. раздел 10.0.4 и `.github/workflows/ci.yml`.
> Ниже — архивный вариант под k8s (cloud.ru), сохранённый для справки.

### GitHub Actions

```yaml
name: ci-cd
on:
  push:
    branches: [main]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15-alpine
        env:
          POSTGRES_DB: trading_bot
          POSTGRES_USER: trader
          POSTGRES_PASSWORD: trader
        ports: ["5432:5432"]
      redis:
        image: redis:7-alpine
        ports: ["6379:6379"]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew test

  build-and-deploy:
    if: github.ref == 'refs/heads/main'
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Login to registry
        run: echo "${{ secrets.REGISTRY_PASSWORD }}" | docker login cr.cloud.ru -u "${{ secrets.REGISTRY_USER }}" --password-stdin
      - name: Build image
        run: docker build -t cr.cloud.ru/trading/mmvb-bot:${{ github.sha }} .
      - name: Push image
        run: docker push cr.cloud.ru/trading/mmvb-bot:${{ github.sha }}
      - name: Deploy
        run: |
          # 1. Liquibase: apply changesets (переходная job на БД)
          kubectl -n trading-bot create job --from=cronjob/liquibase-migrate migrate-$(date +%s) || true
          # 2. Deploy bot (Recreate)
          kubectl -n trading-bot set image deployment/trading-bot trading-bot=cr.cloud.ru/trading/mmvb-bot:${{ github.sha }}
          kubectl -n trading-bot rollout status deployment/trading-bot --timeout=180s
      - name: Smoke test
        run: |
          kubectl -n trading-bot exec deploy/trading-bot -- wget -qO- http://localhost:8080/actuator/health
          kubectl -n trading-bot exec deploy/trading-bot -- wget -qO- http://localhost:8080/api/v1/analytics/health
```

### Dockerfile (проект)

```dockerfile
FROM gradle:8.5-jdk21 AS build
WORKDIR /app
COPY --chown=gradle:gradle . .
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /workspace
COPY --from=build /app/build/libs/trading-bot-2.0.0.jar /workspace/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/workspace/app.jar"]
```

## 10.4. Безопасность

| Область | Мера |
|---|---|
| Secrets | только в K8s Secret / Managed Secret Manager; **никогда** в Git или application.yml |
| Network | NetworkPolicy: egress только 443; ingress — через Service, без внешних LoadBalancer наружу |
| RBAC | ServiceAccount с минимальными правами (чтение ConfigMap) |
| Контейнер | non-root user, read-only rootfs, minimal image (jre-alpine) |
| Health | liveness/readiness через `/actuator/health` |
| Токены | Alor token обновляется автоматически; KIMI_API_KEY в Secret |
| Аудит | все торговые действия в БД (`positions`, `agent_logs`, `order_outbox`) — возможность пост-анализа |

## 10.5. Запуск через docker compose (локально)

```bash
docker compose up -d postgres redis
docker compose up app        # в foreground для логов
```

`docker-compose.yml` уже включает healthcheck'и PostgreSQL/Redis и прокидывает все env-переменные.

## 10.6. Переход в LIVE — чек-лист

1. [ ] Прогнать SIMULATION минимум 1 неделю.
2. [ ] Проверить метрики: win rate, profit factor, cache hit rate, slippage.
3. [ ] В `Secret`: заполнить `ALOR_TOKEN`, `ALOR_REFRESH_TOKEN`, `ALOR_PORTFOLIO`.
4. [ ] Изменить `TRADING_MODE=LIVE` и `MAX_OPEN_POS` (например, 1) — только в манифесте, перезапуск.
5. [ ] Проверить `/actuator/health`, алерты, логи первого цикла.
6. [ ] Следить за `trade.slippage.rub` и `outbox.failed`.
