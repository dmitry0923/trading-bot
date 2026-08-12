# ML пайплайн (roadmap v2.4, шаг 2 — обучение на CI)

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
