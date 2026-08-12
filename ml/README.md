# ML пайплайн (roadmap v2.4 — обучение на CI + инференс)

Python-пайплайн обучения модели исхода позиции. Потребляет CSV-датасет,
экспортированный ботом (`GET /api/v1/ml/dataset`, 29 колонок по
`MlDatasetRow.CSV_HEADER`), обучает градиентный бустинг и отдаёт артефакты
(модель + отчёт + важность признаков). Подробности — раздел 13.11.3
`docs/13-roadmap.md`.

## Установка

```bash
python -m venv .venv
.venv/Scripts/activate        # Windows
source .venv/bin/activate     # Linux/macOS
pip install -r ml/requirements.txt
```

Для запуска тестов: `pip install -r ml/requirements-dev.txt`.

## Запуск обучения

```bash
python ml/train.py \
  --dataset dataset.csv \
  --output-dir ml-artifacts \
  --model catboost          # или lightgbm
  --test-size 0.2           # доля OOS по времени (0.0..0.5)
  --seed 42
```

Аргументы: `--model` (catboost|lightgbm), `--test-size` (default 0.2),
`--seed` (42), `--cv-folds` (5), `--min-rows` (50), `--threshold` (0.5).
Коды выхода: 0 — успех, 2 — данные не пригодны (пусто/мало/один класс),
3 — не установлена библиотека модели.

## Артефакты (`--output-dir`)

| Файл | Содержимое |
|---|---|
| `model.cbm` / `model.txt` | модель (формат CatBoost/LightGBM, пригоден для JVM-инференса) |
| `eval_report.json` | полный отчёт: сплит, метрики OOS, CV, PF-бенчмарки M3, важность признаков |
| `feature_importance.tsv` | ранжирование признаков |
| `training.log` | человекочитаемая сводка |

## Отчёт `eval_report.json`

```json
{
  "model": "catboost",
  "seed": 42,
  "dataset": { "rows": 1000, "train_rows": 800, "test_rows": 200, "features": [...] },
  "metrics": { "accuracy": 0.55, "roc_auc": 0.62, "log_loss": 0.68, "best_iteration": 310, ... },
  "cv": { "folds": 5, "mean_roc_auc": 0.6, "std_roc_auc": 0.03 },
  "trading": {
    "threshold": 0.5,
    "all_trades":    { "n": 200, "win_rate": 0.5,  "profit_factor": 1.2, "total_pnl_rub": 4200 },
    "llm_baseline":  { "n": 130, "win_rate": 0.54, "profit_factor": 1.3, "total_pnl_rub": 3800 },
    "ml_selected":   { "n": 95,  "win_rate": 0.6,  "profit_factor": 1.6, "total_pnl_rub": 5100 }
  },
  "m3": { "ml_beats_llm_pf": true, "ml_beats_all_pf": true },
  "feature_importance": [ { "feature": "rsi14", "importance": 0.21 }, ... ]
}
```

`profit_factor = null` означает отсутствие убыточных сделок в выборке (PF = ∞).

## Ключевые принципы

- **Temporal OOS**: тест — последние 20% сделок по `opened_at`; случайный
  сплит исключён (lookahead-утечка).
- **Метрика M3**: `m3.ml_beats_llm_pf` — PF модели > PF строк со
  `strategy_action ∈ {BUY, SELL}` на OOS (см. ограничения в roadmap).
- **Схема**: пайплайн требует все 29 колонок экспорта — дрейф формата
  ловится ошибкой, а не тихой порчей модели.

## CI

- `ml-train.yml` — обучение: `workflow_dispatch` + еженедельный schedule.
  Датасет скачивается через API (секреты `ML_API_BASE`, `ML_API_USERNAME`,
  `ML_API_PASSWORD`; требуется `ml.enabled=true`) или из прямой ссылки
  (input `dataset_url`). Артефакты — `upload-artifact` (30 дней).
- `ci.yml` (джоба `ml-training`) — pytest-тесты `ml/tests/test_train.py` на
  лёгком наборе (lightgbm без catboost) при каждом push/PR.

## Инференс в бэкенде (раздел 13.11.4)

Бот загружает `model.cbm` через `ai.catboost:catboost-prediction:1.2.8`
(`ml.model-path`, default `ml/model.cbm`) и ранжирует тикеры эндпоинтом
`GET /api/v1/ml/screen?tickers=SBER,GAZP&topN=5`.

**Порядок признаков фиксируется дважды** — в `NUMERIC_FEATURES`/`CATEGORICAL_FEATURES`
(здесь) и в `MlFeatureVector` (Kotlin). При изменении списка признаков в `train.py`
обязательно синхронизируйте `MlFeatureVector.numericFeatures()`/`categoricalFeatures()`
(порядок покрыт юнит-тестом `MlFeatureVectorTest`).

На скрининге решение стратега ещё не принято: `strategy_action=""`,
`strategy_confidence=NaN` — те же кодировки, что модель видела при обучении
(пустая строка — отдельная категория). Модель прогоняется в обоих направлениях,
для тикера остаётся лучшее.

Промоушн: положить артефакт по `ML_MODEL_PATH` и включить `ml.enabled=true`
(иначе `/screen` отдаёт 404, а при отсутствии файла — 503).

## ML-фильтр входа (раздел 13.11.5)

Опциональная интеграция модели в торговый цикл: бэкенд (`MlEntryFilter`,
`DecisionEngine`) блокирует вход, если прогноз вероятности выигрышного исхода
для сигнала ниже `ml.filter.threshold` (default 0.5). Включается отдельным флагом
`ml.filter.enabled` (default false) — включение ML-модуля само по себе не гейтит
входы. При включённом фильтре и недоступной модели/недостатке данных вход
блокируется (fail-closed). Признаки для фильтра строятся с реальным решением
стратега (`strategy_action`/`strategy_confidence` из сигнала), в отличие от
скрининга (`""`/NaN).

## ML-фильтр в бэктесте (раздел 13.11.6)

`BacktestEngine` гейтит входы тем же `MlEntryFilter` при `bt.ml-filter-enabled=true`
(default false, env `BT_ML_FILTER_ENABLED`) — консистентность live/бэктест.
Признаки строятся на момент бара (без lookahead), `strategy_confidence=null`
(детерминированный генератор не даёт уверенности — отдельная категория).
Флаг бэктеста не влияет на live-гейт (`ml.filter.enabled`); модель должна быть
доступна — при `ml.enabled=false`/отсутствии файла входы блокируются (fail-closed).

## ML-прогноз удержания тренда (раздел 13.11.7)

`GET /api/v1/ml/trend?tickers=SBER,GAZP&topN=5` — ранжирование тикеров по оценке
удержания тренда: `trendScore = 0.6 * P(win) + 0.4 * сила_тренда_по_индикаторам`
(EMA-наклон, return20, MACD-гистограмма, отклонение %B) для направления LONG/SHORT
(`MlTrendScore`). В отличие от скрининга (ранжирование по сырой вероятности) —
ответ на вопрос «в какую сторону рынок скорее продолжит движение» на горизонте
`ml.trend.horizon-bars` (интерпретация: модель обучена на исходах позиций).

Тот же скоринг используется как опциональный тренд-гейт входа
(`ml.filter.trend-gate-enabled=true`): вход в позицию требует `trendScore >=
ml.filter.trend-min-score`. Отдельный флаг — включить гейт можно после валидации
прогноза тренда, не меняя поведение базового ML-фильтра входа (13.11.5).
