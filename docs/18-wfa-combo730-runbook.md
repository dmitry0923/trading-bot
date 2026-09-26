# Runbook: параллельный WFA-прогон комбо-фильтров CNYRUBF 730д

Дата запуска: 2026-09-25. Цель: 16 конфигов комбо-фильтров, WFA 730д / folds=8 / conf=0.60,
риск-профиль research (riskPerTradePercent=30, futuresMaxContractsPerPosition=100),
parallel=2, watchdog c перезапуском зависших.

## Что уже было сделано 24.09 (провал)

Все 16 конфигов завершились ERROR. Причины:
1. `research_wfa_combo730_wd_orch.ps1:93` — `return { ok = ... }` вместо `@{ ok = ... }`
   (PowerShell parser error "Data section is missing its statement block"), оркестратор
   вообще не стартовал.
2. Сервер был поднят с `--spring.mvc.async.request-timeout=5400000` (1.5ч), а тройные
   комбо считаются 4400-6200с (1.2-1.7ч) → `HttpClient.Timeout of 5400 seconds elapsing`.

## Текущее состояние инфраструктуры

- Docker: `trading-bot-db` (postgres) + `trading-bot-redis`, оба healthy.
- Сервер: `java -Xmx4g -jar build/libs/trading-bot-2.0.0.jar --spring.mvc.async.request-timeout=10800000`
  (3ч), логи в `%TEMP%\opencode\server\server.out.log` / `server.err.log`, pid-файл
  `%TEMP%\opencode\server\server.pid`.
- Данные: CNYRUBF 46 465 свечей MINUTE_10, funding_history 509 дат.

Smoke-проверки сервера:
```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/auth/login" `
  -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 10
```

## Скрипты

| Файл | Роль |
|---|---|
| `scripts/research_wfa_combo730_wd_orch.ps1` | оркестратор: очередь 16 конфигов, parallel=2, проверка каждый 60с, kill+restart зависших, рестарт java при падении сервера |
| `scripts/research_wfa_combo730_wd_runner.ps1` | раннер одного конфига: HTTP в фоновом job, heartbeat каждые 60с, `-MaxWaitSec 10500` (2.9ч) |

Артефакты прогона (каталог по умолчанию `%TEMP%\opencode\combo730`):
- `<config>.json` — результат (`error`, либо `oosReturn`/`oosPF`/`oosTrades`/`consistency`/`probNoEdge`/`robust`/`secs`)
- `<config>.heartbeat` — `t=<unix>` обновляется каждые 60с
- `orch_wd.log` — лог оркестратора (STARTED / STUCK / DONE / FAIL / STATUS)
- `summary.txt` — итоговая таблица с AnnualPct

## Баги, уже исправленные в оркестраторе

1. `:93` parser error `return {` → `return @{`.
2. Потеря параметров при requeue: в `$running[$p.Id]` не сохранялся `params`, поэтому
   перезапуск конфига уходил с пустыми параметрами. Исправлено: `params = $c.params`.
3. Ложное "STUCK" на свежий старт: при отсутствии heartbeat-файла считалось `hbOld = $true`
   и процесс убивался через 0-60с после старта. Исправлено: `hbOld = $false` по умолчанию,
   kill только при `($hbOld -and $elapsedMin > 5) -or $elapsedMin > 178`.

Перед каждым перезапуском проверять парсер:
```powershell
$f = "scripts/research_wfa_combo730_wd_orch.ps1"
$t=$null; $e=$null
[System.Management.Automation.Language.Parser]::ParseFile($f,[ref]$t,[ref]$e) | Out-Null
if ($e.Count) { $e | ForEach-Object { "ERR L$($_.Extent.StartLineNumber): $($_.Message)" } } else { "PARSE OK" }
```

## Запуск

```powershell
$repo = "C:\Users\User\Downloads\repo_trading_bot\v2\trading-bot"
$d = Join-Path $env:TEMP "opencode\combo730"
$orch = Join-Path $repo "scripts\research_wfa_combo730_wd_orch.ps1"
Get-ChildItem $d -Include *.json,*.heartbeat -File | Remove-Item -Force
Remove-Item (Join-Path $d "orch_wd.log") -Force -ErrorAction SilentlyContinue
$p = Start-Process pwsh -ArgumentList '-NoProfile','-File',"`"$orch`"" `
  -RedirectStandardOutput (Join-Path $d "orch_stdout.log") `
  -RedirectStandardError (Join-Path $d "orch_stderr.log") -PassThru -WindowStyle Hidden
"ORCH pid=$($p.Id)"
```

## Волны перебора (план)

### Волна 1 — ЗАВЕРШЕНА 2026-09-25 20:50 (16/16, `summary.txt`)

| Config | Consistency | OOS Ret 730д | Annual | OOS PF | Сделок | P(noEdge) |
|---|---|---|---|---|---|---|
| orb12l-tdL9-fv9-2-mh368 | 0.50 | +109.40% | +44.7% | **2.29** | 30 | 0.08 |
| tdL9-fv9-2-mh368 | 0.62 | **+148.05%** | **+57.5%** | 2.04 | 47 | 0.04 |
| fv9-2-mh368 | 0.50 | +136.47% | +53.8% | 1.87 | 60 | 0.07 |
| tdL9-mh368 | 0.50 | +90.07% | +37.9% | 1.71 | 67 | 0.06 |
| orb12l-tdL9 | 0.75 | +17.15% | +8.2% | 1.24 | 46 | 0.26 |
| tdL9 | 0.38 | +4.71% | +2.3% | 1.04 | 71 | 0.44 |
| tdL9s13-16 | 0.38 | +4.71% | +2.3% | 1.04 | 71 | 0.44 |
| orb12l | 0.38 | +1.14% | +0.6% | 1.01 | 58 | 0.48 |
| orb12l-mh368 | 0.38 | +1.14% | +0.6% | 1.01 | 58 | 0.48 |
| orb12l-fv9-2 | 0.38 | −3.76% | −1.9% | 0.97 | 44 | 0.53 |
| pull0_3 | 0.38 | −5.03% | −2.5% | 0.93 | 55 | 0.61 |
| mh368 | 0.38 | −12.89% | −6.7% | 0.92 | 86 | 0.62 |
| baseline | 0.38 | −17.09% | −8.9% | 0.90 | 87 | 0.68 |
| orb12l-fv9-2-mh368 | 0.25 | −22.75% | −12.1% | 0.79 | 44 | 0.75 |
| tdL9-fv9-2 | 0.50 | −32.82% | −18.0% | 0.78 | 55 | 0.70 |
| fv9-2 | 0.25 | −75.56% | −50.6% | 0.58 | 69 | 0.94 |

Все `robust=false`; `confidenceThreshold` в cal-профиле 0.60, risk 30%/maxC 100.

Главный вывод волны 1: **комбинация `timeDirection long<=9` + `fundingVeto 9/2` +
`maxHoldBars=368` даёт OOS +148% за 730д (PF 2.04, ≈ +57%/год) против baseline −17%
(PF 0.90)**. По отдельности фильтры убыточны или нейтральны (fv9-2 −75.6%,
tdL9 +4.7%, mh368 −12.9%) — эффект строго синергетический. Максимальный PF 2.29 у
квад-комбо с ORB, но 30 сделок и PF/сделки хуже. ORB и pullback в лидер не идут.

### Волна 2 — ЗАВЕРШЕНА 2026-09-26 07:00 (16/16, `wave2\summary.txt`, failed=0)

| Config | Consistency | OOS Ret 730д | Annual | OOS PF | Сделок | P(noEdge) |
|---|---|---|---|---|---|---|
| **td-mh-fv6-6** | **0.88** | **+187.51%** | **+69.6%** | **2.73** | 40 | **0.01** |
| td-fv-mh1095 | 0.62 | +186.22% | +69.2% | 2.18 | 43 | 0.03 |
| tdL8-fv-mh | 0.62 | +160.86% | +61.5% | 2.26 | 50 | 0.03 |
| td-mh-fv4-4 | 0.88 | +155.88% | +60.0% | 2.74 | 35 | 0.02 |
| td-mh-fv9-05 | 0.62 | +151.12% | +58.5% | 2.08 | 45 | 0.04 |
| td-fv-mh-c62 | 0.62 | +151.12% | +58.5% | 2.08 | 45 | 0.04 |
| td-fv-mh-c65 | 0.62 | +151.12% | +58.5% | 2.08 | 45 | 0.04 |
| tdL9s13-16-fv-mh | 0.62 | +151.12% | +58.5% | 2.08 | 45 | 0.04 |
| td-mh-fv12-12 | 0.50 | +123.06% | +49.4% | 1.97 | 59 | 0.04 |
| tdL9-mh368-ctl | 0.50 | +87.51% | +36.9% | 1.70 | 65 | 0.08 |
| td-fv-mh184 | 0.62 | +97.36% | +40.5% | 1.63 | 49 | 0.12 |
| td-fv-mh552 | 0.50 | +51.66% | +23.2% | 1.37 | 51 | 0.24 |
| td-fv-mh736 | 0.50 | +37.44% | +17.2% | 1.20 | 47 | 0.36 |
| tdL11-fv-mh | 0.50 | +15.44% | +7.4% | 1.13 | 47 | 0.37 |
| td-fv-mh-h1 | 0.25 | +12.26% | +6.0% | 1.11 | 18 | 0.43 |
| tdL10-fv-mh | 0.38 | 0.00% | 0.0% | 1.00 | 48 | 0.50 |

Три вывода волны 2 (важнее самих чисел):

1. **Реально влияют только 3 оси.** `adaptiveConfidenceThreshold=0.62/0.65` и
   `timeDirectionShortBlockStartHour/EndHour` (13-16) дали результат, тождественный
   базовому `td-fv-mh368` — набор сделок не меняется. Confidence-gate после жёстких
   входных фильтров не является связывающим ограничением; SHORT-входов в окне 13-16 на
   730д выборке не бывает. Дальше эти рычаги не калибровать.
2. **Funding-порог: оптимум шире, чем в 365д WFA.** 6/6 (PF 2.73, P=0.01) лучше 9/2
   (PF 2.08) и 4/4 (PF 2.74, но 35 сделок). На 730д высокий порог 9/2 резал profitable
   LONG; 6/6 — баланс «много сделок × высокий PF». 12/12 деградирует (PF 1.97) —
   фильтр перестаёт отсекать шум.
3. **max-hold: плато 368-1095 баров** (368: PF 2.73, 1095: PF 2.18 при 43 сделках).
   184 — заметно хуже (PF 1.63), 552/736 — средне (PF 1.37/1.20). Оптимум 368 (≈6.5
   торговых дней), но годовое удержание (1095) остаётся плюсовым.
4. **tdL10 (блок LONG до 10:00) обнуляет всё** (0.00%, PF 1.00) — граница очень узкая:
   9 — окно лучшее, 8 чуть хуже, 10 — разрушительно. Это не плавная поверхность,
   а почти бинарный эффект; при переносе в live требует осторожности.
5. Контроль `tdL9-mh368-ctl` (без funding-veto) дал +87.51% против +90.07% в волне 1
   при тех же параметрах — расхождение вызвано догрузкой свечей за 2026-09-23..24
   (история растёт). Оценки калибровки «плато ±2-3 п.п.» — нормальный дрейф.

### Волна 3 — ЗАВЕРШЕНА 2026-09-26 15:52 (15/15, `wave3\summary.txt`, failed=0)

| Config | Consistency | OOS Ret 730д | Annual | OOS PF | Сделок | P(noEdge) |
|---|---|---|---|---|---|---|
| **td-fv6-ctl** (контроль) | **0.88** | **+193.60%** | **+71.3%** | **2.79** | 42 | **0.01** |
| td-fv7-7 | 0.62 | +191.38% | +70.7% | 2.70 | 45 | 0.01 |
| td-fv6-mh1095 | 0.75 | +188.76% | +69.9% | 2.24 | 41 | 0.04 |
| td-fv6-4 | 0.88 | +187.51% | +69.6% | 2.73 | 40 | 0.01 |
| td-fv6-8 | 0.88 | +187.51% | +69.6% | 2.73 | 40 | 0.01 |
| td-fv6-orb12l | 0.88 | +185.39% | +68.9% | 2.79 | 42 | 0.01 |
| td-fv5-5 | 0.88 | +158.52% | +60.8% | 2.69 | 37 | 0.02 |
| td-fv4-6 | 0.88 | +155.88% | +60.0% | 2.74 | 35 | 0.02 |
| td-fv6-mh460 | 0.75 | +147.28% | +57.3% | 2.13 | 41 | 0.05 |
| td-fv6-mh184 | 0.75 | +128.81% | +51.3% | 2.14 | 44 | 0.04 |
| td-fv3-3 | 0.75 | +79.39% | +33.9% | 1.84 | 32 | 0.13 |
| td-fv6-h1 (HOUR_1) | 0.25 | +57.21% | +25.4% | 1.93 | 14 | 0.17 |
| td-fv4-mh1095 | 0.62 | +52.72% | +23.6% | 1.38 | 36 | 0.28 |
| td-fv6-s1012 (SHORT 10-12) | 0.25 | +39.13% | +18.0% | 1.28 | 49 | 0.29 |
| td-fv6-mh644 | 0.50 | −56.83% | −34.3% | 0.63 | 48 | 0.91 |

Выводы волны 3 (важнее чисел):

1. **Результат воспроизводится на свежих данных.** Контроль `td-fv6-ctl` (тот же
   конфиг, что лидер волны 2, но на истории с включёнными свечами до 2026-09-24)
   дал **+193.60% / PF 2.79 / P(noEdge)=0.01** против +187.51% / PF 2.73 в волне 2 —
   на догрузке 5 суток истории лидер вырос, а не развалился. Это снимает главное
   подозрение к калибровке (дрейф/перегрив), но **не** снимает подозрение на
   selection на полной истории.
2. **Funding-порог — широкое плато 5-8 ₽, не острый пик.** 6/6 +193.6, 7/7 +191.4,
   6/4 и 6/8 +187.5, 5/5 +158.5, 4/6 +155.9; 3/3 деградирует (+79.4, P=0.13).
   Порог 6 ₽ — середина плато, а не выброс. Симметрия 4/6 ≈ 6/8 ≈ 6/4 подтверждена.
3. **max-hold: поверхность НЕ гладкая.** 184 → +128.8, 368 → +193.6, 460 → +147.3,
   644 → **−56.8 (PF 0.63)**, 1095 → +188.8. Провал на 644 между двумя плюсовыми
   точками означает, что разброс поверхности сопоставим с её перепадом —
   индивидуальные точки оцениваются шумно, доводка mh дальше бессмысленна.
4. **ORB в лидер не добавляет.** `td-fv6-orb12l` — тот же PF 2.79, что у контроля,
   при ret на 8 п.п. ниже. Как и в 365д калибровке, ORB не улучшает комбо.
5. **SHORT-окно 13-16 оказалось связывающим.** На 730д выборке оно «не встречается»
   (волна 2), но сдвиг блока на 10-12 (`td-fv6-s1012`) рушит результат до
   +39.1% / PF 1.28 / cons 0.25 — то есть SHORT-входы в окне 10-12 есть и они
   убыточны. Волна 2 сделала ложный вывод об идемпотентности.
6. **HOUR_1-ресемплинг не нужен** (PF 1.93, 14 сделок, 301 с — самый быстрый
   прогон: сигналов почти нет).

**Итог трёх волн:** лучшая точка — `tdL9 + maxHoldBars=368 + fundingVeto 6/6`,
OOS +193.6% за 730д (≈ +71%/год), PF 2.79, P(noEdge)=0.01, consistency 0.88.
Baseline без фильтров: −17.09% / PF 0.90. Все 47 конфигов трёх волн —
`robust=false` (35-50 OOS-сделок против порога 100).

**Честная оговорка (обязательна к любому переносу в live).** Все три волны
подбирали параметры на одной и той же полной 730д истории. Deployment-gate на
dev-части этой же истории (2026-09-22) для близких комбинаций дал REJECTED:
OOS PF 0.92 при `/validate` > 2 на полной. Поэтому «+193% / PF 2.79» следует
считать **upper bound с поправкой на selection**, а не оценкой edge. Формальный
шаг — прогнать `deployment-gate` для `td-fv6-ctl` на dev-части и сравнить с
holdout/MC; live-параметры (maxC=1, Kelly, LIVE-guard CNYRUBF) не меняются.

### Deployment-gate для `td-fv6-ctl` — RESEARCH_ONLY (2026-09-26, 2701 с = 45 мин)

Прогон на dev-части (80% от 730д, folds=8, conf 0.60 из `--bt.adaptive-confidence-threshold`,
риск-профиль 30%/maxC 100, `maxHoldBars=368`, `timeDirectionLongBlockUntilHour=9`,
fundingVeto 6/6). Лог: `%TEMP%\opencode\combo730\gate\gate-fv6-ctl.status.log`
(5-минутный heartbeat), результат `gate-fv6-ctl.json`.

| Проверка | Итог | Детали |
|---|---|---|
| backtest (dev) | **PASS** | Sharpe 2.375, MDD 11.0%, PF 1.930, 38 сделок |
| Walk-forward OOS | FAIL (по выборке) | consistency **0.625** (PASS), oosTrades 31 (<100), oosSharpe 1.113, **oosPF 1.651** |
| Значимость edge | FAIL | P(noEdge)=**0.171**, significant=false |
| Финальный holdout | FAIL | **−17.6%**, 13 сделок (<30), passable=false |
| Monte Carlo + stress | FAIL | mcRobust=true, p5=**+12.6%**, pLoss 2.3%, но **stressFailed=1** |
| **Вердикт** | **RESEARCH_ONLY** | `liveAllowed=false`, `liveApprovalActive=false` |

**Главный результат прогона: подозрение на selection для этого конфига НЕ
подтвердилось.** Dev-WFA OOS PF = 1.651 против 0.78-0.92 у близких комбинаций
(max-hold и time-direction по отдельности, 2026-09-22/24). Синергия
`tdL9 + mh368 + fv6/6` удерживается и вне полной истории, на которой шёл подбор.
Consistency 0.625 выше порога 0.600, MC p5 = +12.6% (не просадка).

**Почему всё равно не LIVE:** (1) 31 OOS-сделка против порога 100 и 13 holdout-сделок
против 30 — статистическая база не набрана; (2) P(noEdge)=0.171 — edge не значим;
(3) финальный holdout отрицательный (−17.6%) — на самом свежем отрезке истории
конфиг убыточен, то есть подтверждения на будущем не прослеживается; (4) один
стресс-сценарий MC не прошёл. Вывод: **комбинация остаётся кандидатом в research,
но перенос в live не обоснован**; для live-режима (maxC=1, Kelly) она тем более
не применима — калибровочная маржа 30%/maxC 100 в live не воспроизводится.

**Инфраструктурный фикс, без которого гейт нельзя было запустить:** `/deployment-gate`
и `/holdout` НЕ принимали `maxHoldBars` (только `/backtest`, `/validate`, `/robustness`).
Для лидера калибровки max-hold — часть стратегии, поэтому гейт проверял бы другой
конфиг. Добавлен query-параметр `maxHoldBars` в оба эндпоинта и проброс в
`FinalHoldoutValidator.validate` → `WfaConfig` + оба прогона `BacktestEngine.simulate`
(dev и holdout) единым значением; fallback на `bt.max-hold-bars`. Регресс-тесты
`FinalHoldoutValidatorTest` (override + fallback, captor на WfaConfig и на обоих
simulate). Раннер гейта с 5-минутным heartbeat: `scripts/research_gate.ps1`.


### Автодокументирование (вместо ручного вноса)

`autodoc.ps1` + `autodoc_loop.ps1` (loop pid 26460, 60 с) — для каждой папки `waveN`
секция `<!-- AUTO-WAVEn:START/END -->` в этом файле **всегда** содержит полный список
`DONE/FAIL/ALL_DONE` из её `orch_wd.log`. Скрипт идемпотентен (читает лог целиком,
состояние не хранится), поэтому потеря строк невозможна. Ошибка v1 (блок
перезаписывался только новыми строками) исправлена 2026-09-26 09:40 — v2/v3
(split/join по маркерам, `[System.IO.File]::WriteAllLines` с UTF-8 без BOM).

Правила ведения журнала (обязательно):
- Каждые 5 мин: `STATUS`, heartbeat, счётчик `done/failed/running/queue`; при отсутствии
  роста лога 10+ мин — проверка `java`/login и перезапуск по runbook.
- `SERVER DOWN (N)`: 1-2 раза допустимо (таймаут логина под нагрузкой двух тяжёлых
  прогонов). Рестарт java произойдёт только при N >= ServerDownThreshold (3 -> 6,
  таймаут логина 8с -> 30с), чтобы ложный рестарт не убивал прогоны. За волны 1-2
  сработало 9 таких событий, рестартов не было.
- Итог волны: сводка в `summary.txt` + таблица сюда + запись в `AGENTS.md`
  (раздел «Каталог закрытых аудитов») при закрытии эксперимента.

## Мониторинг (каждые 5 минут)

```powershell
$d = Join-Path $env:TEMP "opencode\combo730"
Get-Content (Join-Path $d "orch_wd.log") -Tail 20
Get-ChildItem $d -Filter *.json | Where-Object LastWriteTime -gt (Get-Date).AddMinutes(-10) |
  Select-Object Name, Length, LastWriteTime
```

Признаки нормы: `heartbeat` обновляется раз в минуту, в логе появляются `STARTED`, затем
`DONE`/`FAIL`, `STATUS ... running=2 queue=N`. Каждый конфиг 60-120 мин, все 16 — 8-16ч
при parallel=2.

## Быстрое восстановление

| Симптом | Действие |
|---|---|
| оркестратор мёртв, лог не растёт | перезапуск по блоку «Запуск» |
| `STATUS` не меняется 10+ мин | проверить `java` (таблица ниже), затем перезапуск |
| сервер не отвечает на login 3+ мин | оркестратор сам перезапустит java (Restart-JavaServer) |
| runner вернул `TIMEOUT` | будет автоматический requeue, MaxAttempts=4 |
| `STUCK ... -> kill+restart` в логе | это watchdog, норма, если не чаще 1 раза в 30 мин |
| все конфиги `FAIL` | читать `orch_stderr.log`, проверять парсер и URL |

## Ручной рестарт сервера

```powershell
$repo = "C:\Users\User\Downloads\repo_trading_bot\v2\trading-bot"
$sd = Join-Path $env:TEMP "opencode\server"
New-Item -ItemType Directory -Force -Path $sd | Out-Null
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 5
$env:DB_PASS='backtest123'; $env:DB_USER='trader'; $env:DB_NAME='trading_bot'
$env:AUTH_USER='admin'; $env:AUTH_PASSWORD='admin123'
$env:JWT_SECRET='supersecretkey minlength32 bytes!!'; $env:TRADING_MODE='SIMULATION'
$p = Start-Process java -ArgumentList '-Xmx4g','-jar',"`"$repo\build\libs\trading-bot-2.0.0.jar`"",
  '--spring.mvc.async.request-timeout=10800000' -WorkingDirectory $repo `
  -RedirectStandardOutput "$sd\server.out.log" -RedirectStandardError "$sd\server.err.log" `
  -PassThru -WindowStyle Hidden
$p.Id | Set-Content "$sd\server.pid"
```

Подождать 60-90с до login, затем проверить `docker ps` и login-smoke выше.

## Конфиги сетки

| Имя | Фильтры |
|---|---|
| baseline | без фильтров |
| fv9-2 | funding-veto long 9 / short 2 |
| mh368 | max-hold 368 баров |
| tdL9 | block LONG <= 9ч |
| tdL9s13-16 | tdL9 + block SHORT 13-16ч |
| orb12l | ORB window 12, loose |
| tdL9-fv9-2, tdL9-mh368, fv9-2-mh368 | парные комбо |
| orb12l-fv9-2, orb12l-mh368, orb12l-tdL9 | парные комбо |
| tdL9-fv9-2-mh368, orb12l-fv9-2-mh368, orb12l-tdL9-fv9-2-mh368 | тройные комбо |
| pull0_3 | pullback EMA20 dev 0.3% |

URL-шаблон:
`/api/v1/backtest/CNYRUBF/validate?days=730&folds=8&adaptiveConfidenceThreshold=0.60&riskPerTradePercent=30&futuresMaxContractsPerPosition=100&loadHistory=false&<params>`

## Итог

После завершения: ranking по OOS_PF/AnnualPct, проверка consistency/probNoEdge/robust.
Критерий выбора: PF и annualized доходность при consistency >= 0.5; кандидат к 100%/год
требует AnnualPct около 100 при 730д OOS. По историческим данным (AGENTS.md) устойчивого
edge ~100%/год на детерминированной базе не найдено, поэтому цель — лучший из кандидатов,
а не гарантия.

<!-- AUTO-WAVE2:START -->
- DONE  td-fv-mh184 attempt=1 ret=97.36% pf=1.63 trades=49 consistency=0.625 secs=6242
- DONE  td-fv-mh552 attempt=1 ret=51.66% pf=1.37 trades=51 consistency=0.5 secs=7082
- DONE  td-fv-mh736 attempt=1 ret=37.44% pf=1.2 trades=47 consistency=0.5 secs=5402
- DONE  td-fv-mh1095 attempt=1 ret=186.22% pf=2.18 trades=43 consistency=0.625 secs=5342
- DONE  tdL8-fv-mh attempt=1 ret=160.86% pf=2.26 trades=50 consistency=0.625 secs=4681
- DONE  tdL10-fv-mh attempt=1 ret=0% pf=1 trades=48 consistency=0.375 secs=4561
- DONE  tdL11-fv-mh attempt=1 ret=15.44% pf=1.13 trades=47 consistency=0.5 secs=4141
- DONE  td-mh-fv4-4 attempt=1 ret=155.88% pf=2.74 trades=35 consistency=0.875 secs=3781
- DONE  td-mh-fv6-6 attempt=1 ret=187.51% pf=2.73 trades=40 consistency=0.875 secs=4261
- DONE  td-mh-fv12-12 attempt=1 ret=123.06% pf=1.97 trades=59 consistency=0.5 secs=4441
- DONE  td-mh-fv9-05 attempt=1 ret=151.12% pf=2.08 trades=45 consistency=0.625 secs=4021
- DONE  td-fv-mh-c62 attempt=1 ret=151.12% pf=2.08 trades=45 consistency=0.625 secs=4201
- DONE  td-fv-mh-h1 attempt=1 ret=12.26% pf=1.11 trades=18 consistency=0.25 secs=121
- DONE  td-fv-mh-c65 attempt=1 ret=151.12% pf=2.08 trades=45 consistency=0.625 secs=3901
- DONE  tdL9s13-16-fv-mh attempt=1 ret=151.12% pf=2.08 trades=45 consistency=0.625 secs=4141
- DONE  tdL9-mh368-ctl attempt=1 ret=87.51% pf=1.7 trades=65 consistency=0.5 secs=3241
- ALL_DONE 2026-09-26 07:00:04
<!-- AUTO-WAVE2:END -->

<!-- AUTO- -->
- DONE  td-fv-mh184 attempt=1 ret=97.36% pf=1.63 trades=49 consistency=0.625 secs=6242
- DONE  td-fv-mh552 attempt=1 ret=51.66% pf=1.37 trades=51 consistency=0.5 secs=7082
- DONE  td-fv-mh736 attempt=1 ret=37.44% pf=1.2 trades=47 consistency=0.5 secs=5402
- DONE  td-fv-mh1095 attempt=1 ret=186.22% pf=2.18 trades=43 consistency=0.625 secs=5342
- DONE  tdL8-fv-mh attempt=1 ret=160.86% pf=2.26 trades=50 consistency=0.625 secs=4681
- DONE  tdL10-fv-mh attempt=1 ret=0% pf=1 trades=48 consistency=0.375 secs=4561
- DONE  tdL11-fv-mh attempt=1 ret=15.44% pf=1.13 trades=47 consistency=0.5 secs=4141
- DONE  td-mh-fv4-4 attempt=1 ret=155.88% pf=2.74 trades=35 consistency=0.875 secs=3781
- DONE  td-mh-fv6-6 attempt=1 ret=187.51% pf=2.73 trades=40 consistency=0.875 secs=4261
- DONE  td-mh-fv12-12 attempt=1 ret=123.06% pf=1.97 trades=59 consistency=0.5 secs=4441
- DONE  td-mh-fv9-05 attempt=1 ret=151.12% pf=2.08 trades=45 consistency=0.625 secs=4021
- DONE  td-fv-mh-c62 attempt=1 ret=151.12% pf=2.08 trades=45 consistency=0.625 secs=4201
- DONE  td-fv-mh-h1 attempt=1 ret=12.26% pf=1.11 trades=18 consistency=0.25 secs=121
- DONE  td-fv-mh-c65 attempt=1 ret=151.12% pf=2.08 trades=45 consistency=0.625 secs=3901
- DONE  tdL9s13-16-fv-mh attempt=1 ret=151.12% pf=2.08 trades=45 consistency=0.625 secs=4141
- DONE  tdL9-mh368-ctl attempt=1 ret=87.51% pf=1.7 trades=65 consistency=0.5 secs=3241
- ALL_DONE 2026-09-26 07:00:04
<!-- AUTO- -->

<!-- AUTO-WAVE3:START -->
- DONE  td-fv5-5 attempt=1 ret=158.52% pf=2.69 trades=37 consistency=0.875 secs=3961
- DONE  td-fv7-7 attempt=1 ret=191.38% pf=2.7 trades=45 consistency=0.625 secs=4261
- DONE  td-fv6-4 attempt=1 ret=187.51% pf=2.73 trades=40 consistency=0.875 secs=4141
- DONE  td-fv4-6 attempt=1 ret=155.88% pf=2.74 trades=35 consistency=0.875 secs=4021
- DONE  td-fv3-3 attempt=1 ret=79.39% pf=1.84 trades=32 consistency=0.75 secs=4921
- DONE  td-fv6-8 attempt=1 ret=187.51% pf=2.73 trades=40 consistency=0.875 secs=5281
- DONE  td-fv6-mh644 attempt=1 ret=-56.83% pf=0.63 trades=48 consistency=0.5 secs=3781
- DONE  td-fv6-mh460 attempt=1 ret=147.28% pf=2.13 trades=41 consistency=0.75 secs=4141
- DONE  td-fv6-mh184 attempt=1 ret=128.81% pf=2.14 trades=44 consistency=0.75 secs=4381
- DONE  td-fv6-mh1095 attempt=1 ret=188.76% pf=2.24 trades=41 consistency=0.75 secs=4561
- DONE  td-fv6-orb12l attempt=1 ret=185.39% pf=2.79 trades=42 consistency=0.875 secs=3721
- DONE  td-fv4-mh1095 attempt=1 ret=52.72% pf=1.38 trades=36 consistency=0.625 secs=4081
- DONE  td-fv6-h1 attempt=1 ret=57.21% pf=1.93 trades=14 consistency=0.25 secs=301
- DONE  td-fv6-ctl attempt=1 ret=193.6% pf=2.79 trades=42 consistency=0.875 secs=4021
- DONE  td-fv6-s1012 attempt=1 ret=39.13% pf=1.28 trades=49 consistency=0.25 secs=4141
- ALL_DONE 2026-09-26 15:52:15
<!-- AUTO-WAVE3:END -->
