"""
Обучающий пайплайн ML-модели (roadmap v2.4, шаг 2 — «Обучение на CI», раздел 13.11.3).

Потребляет CSV-датасет, экспортированный `GET /api/v1/ml/dataset`
(структура строк — `MlDatasetRow.CSV_HEADER`, 29 колонок), и обучает
бинарный классификатор исхода позиции (цель `win` = 1/0).

Ключевые принципы:

- **Temporal out-of-sample**: тестовая выборка — последние `--test-size`
  (20% по умолчанию) сделок по времени `opened_at`. Никакого случайного
  сплита, который «заглядывал бы в будущее» (lookahead).
- **Модель**: CatBoost (по умолчанию, `--model catboost`) или LightGBM
  (`--model lightgbm`). Категориальные признаки (`strategy_action`,
  `direction`) объявляются как categorical; пропуски (`strategy_signal_strength`,
  пустой `strategy_action`) обрабатываются нативно.
- **Метрика M3**: сравнение profit factor на OOS между ML-выборкой
  (строки с `p >= --threshold`), LLM-baseline (строки, где стратег сказал
  BUY/SELL) и «всеми сделками».

Артефакты в `--output-dir`:

- `model.cbm` / `model.txt` — модель (формат CatBoost/LightGBM, пригоден для
  JVM-инференса на следующем инкременте);
- `eval_report.json` — полный отчёт (метрики, CV, PF-бенчмарки, importance);
- `feature_importance.tsv` — ранжирование признаков;
- `training.log` — человекочитаемая сводка.

Коды выхода: 0 — успех; 2 — данные не пригодны для обучения (пусто / мало /
один класс); 3 — не установлена запрошенная библиотека.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn import metrics
from sklearn.model_selection import StratifiedKFold

TARGET = "win"
TIME_COLUMN = "opened_at"
ID_COLUMN = "position_id"

# Полный заголовок CSV — совпадает с MlDatasetRow.CSV_HEADER.
HEADER_COLUMNS = [
    "position_id", "ticker", "direction", "opened_at", "closed_at",
    "duration_min", "entry_price", "exit_price", "pnl_rub", "pnl_percent",
    "close_reason", "win", "hour_of_day", "rsi14", "atr_percent",
    "macd_hist_percent", "bb_percent_b", "ema_slope_percent",
    "volatility20_percent", "ret_3", "ret_10", "ret_20", "cbr_rate",
    "brent", "usd_rub", "macro_source", "strategy_action",
    "strategy_signal_strength", "in_blind_spot_hour",
]

# Признаки (см. раздел 13.11.1 roadmap). Мета-колонки (id, даты, цены,
# P&L, причина закрытия, источник макро) в модель НЕ подаются.
NUMERIC_FEATURES = [
    "rsi14", "atr_percent", "macd_hist_percent", "bb_percent_b",
    "ema_slope_percent", "volatility20_percent", "ret_3", "ret_10",
    "ret_20", "cbr_rate", "brent", "usd_rub", "strategy_signal_strength",
    "in_blind_spot_hour", "hour_of_day",
]
CATEGORICAL_FEATURES = ["strategy_action", "direction"]
META_COLUMNS = [
    "position_id", "ticker", "opened_at", "closed_at", "duration_min",
    "entry_price", "exit_price", "pnl_rub", "pnl_percent", "close_reason",
    "macro_source",
]

# Значения strategy_action, при которых стратег «предлагал» сделку
# (используется для LLM-baseline на OOS).
TRADING_ACTIONS = {"BUY", "SELL"}

MODEL_FILE = {"catboost": "model.cbm", "lightgbm": "model.txt"}


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Обучение ML-модели исхода позиции на экспортированном датасете.",
    )
    parser.add_argument("--dataset", required=True, help="Путь к CSV-датасету (экспорт /api/v1/ml/dataset).")
    parser.add_argument("--output-dir", default="ml-artifacts", help="Каталог для артефактов.")
    parser.add_argument("--model", choices=("catboost", "lightgbm"), default="catboost", help="Библиотека градиентного бустинга.")
    parser.add_argument("--test-size", type=float, default=0.2, help="Доля OOS-выборки по времени (0.0..0.5).")
    parser.add_argument("--seed", type=int, default=42, help="Сид воспроизводимости.")
    parser.add_argument("--cv-folds", type=int, default=5, help="Число фолдов CV на train-части.")
    parser.add_argument("--min-rows", type=int, default=50, help="Мин. число строк датасета для обучения.")
    parser.add_argument("--threshold", type=float, default=0.5, help="Порог вероятности для отбора ML-сделок.")
    return parser.parse_args(argv)


def load_dataset(path: str) -> pd.DataFrame:
    """Загрузка CSV с проверкой заголовка (защита от дрейфа схемы экспорта)."""
    df = pd.read_csv(path)
    missing = [c for c in HEADER_COLUMNS if c not in df.columns]
    if missing:
        sys.exit(f"Ошибка: в датасете нет колонок: {', '.join(missing)}")
    df[TIME_COLUMN] = pd.to_datetime(df[TIME_COLUMN], errors="coerce")
    df["strategy_signal_strength"] = pd.to_numeric(df["strategy_signal_strength"], errors="coerce")
    for col in NUMERIC_FEATURES:
        if col not in ("strategy_signal_strength",):
            df[col] = pd.to_numeric(df[col], errors="coerce")
    # Категориальные колонки приводим к pandas `category`: read_csv превращает
    # пустые strategy_action в NaN (float-dtype), а в pandas 3.x строки получают
    # `str`-dtype — оба варианта ломают LightGBM. `category` маппится на int-коды,
    # пустая строка = «действие не сгенерировано» (отдельная категория).
    for col in CATEGORICAL_FEATURES:
        df[col] = df[col].fillna("").astype(str).astype("category")
    return df


def split_temporal(df: pd.DataFrame, test_size: float, seed: int) -> tuple[pd.DataFrame, pd.DataFrame]:
    """
    Temporal split: последние `test_size` сделок по opened_at — OOS.
    Детерминирован (сортировка по времени, без случайности).
    """
    df = df.sort_values(TIME_COLUMN, kind="mergesort").reset_index(drop=True)
    test_rows = max(1, int(round(len(df) * test_size)))
    return df.iloc[: len(df) - test_rows].reset_index(drop=True), df.iloc[len(df) - test_rows:].reset_index(drop=True)


def profit_factor(pnl: pd.Series) -> float | None:
    """PF = сумма прибыльных / |сумма убыточных|. None = нет убыточных (бесконечность)."""
    gross_win = float(pnl[pnl > 0].sum())
    gross_loss = float(abs(pnl[pnl < 0].sum()))
    if gross_loss > 0:
        return round(gross_win / gross_loss, 4)
    if gross_win > 0:
        return None  # inf
    return 0.0


def trading_stats(rows: pd.DataFrame) -> dict:
    """Сводка по выбранным сделкам: объём, win rate, PF, суммарный P&L."""
    if rows.empty:
        return {"n": 0, "win_rate": None, "profit_factor": None, "total_pnl_rub": None}
    pnl = rows["pnl_rub"].astype(float)
    return {
        "n": int(len(rows)),
        "win_rate": round(float(rows["win"].mean()), 4),
        "profit_factor": profit_factor(pnl),
        "total_pnl_rub": round(float(pnl.sum()), 2),
    }


def train_catboost(
    x_train: pd.DataFrame,
    y_train: pd.Series,
    x_val: pd.DataFrame,
    y_val: pd.Series,
    cat_indices: list[int],
    seed: int,
):
    from catboost import CatBoostClassifier

    model = CatBoostClassifier(
        iterations=2000,
        learning_rate=0.03,
        depth=6,
        auto_class_weights="Balanced",
        random_seed=seed,
        verbose=False,
        allow_writing_files=False,
    )
    model.fit(
        x_train, y_train,
        cat_features=cat_indices,
        eval_set=(x_val, y_val),
        early_stopping_rounds=100,
    )
    return model


def train_lightgbm(
    x_train: pd.DataFrame,
    y_train: pd.Series,
    x_val: pd.DataFrame,
    y_val: pd.Series,
    cat_features: list[str],
    seed: int,
):
    import lightgbm as lgb

    model = lgb.LGBMClassifier(
        n_estimators=2000,
        learning_rate=0.03,
        num_leaves=31,
        random_state=seed,
        verbose=-1,
        class_weight="balanced",
    )
    model.fit(
        x_train, y_train,
        eval_set=[(x_val, y_val)],
        categorical_feature=cat_features,
        callbacks=[lgb.early_stopping(100, verbose=False), lgb.log_evaluation(0)],
    )
    return model


def predict_proba(model, x: pd.DataFrame) -> np.ndarray:
    return model.predict_proba(x)[:, 1]


def feature_importance(model, columns: list[str], model_name: str) -> list[dict]:
    if model_name == "catboost":
        imp = model.get_feature_importance()
    else:
        imp = model.booster_.feature_importance()
    if imp.ndim > 1:
        imp = imp.mean(axis=0)
    pairs = sorted(zip(columns, (float(v) for v in imp)), key=lambda p: p[1], reverse=True)
    return [{"feature": name, "importance": round(value, 6)} for name, value in pairs]


def sanitize(value):
    """Рекурсивная нормализация numpy/json-типов: NaN/Inf → None."""
    if isinstance(value, np.integer):
        return int(value)
    if isinstance(value, np.floating):
        return float(value)
    if isinstance(value, float):
        return None if (math.isnan(value) or math.isinf(value)) else value
    if isinstance(value, dict):
        return {k: sanitize(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [sanitize(v) for v in value]
    return value


def print_summary(report: dict) -> str:
    lines = [
        "=== ML training summary ===",
        f"model: {report['model']}   rows: {report['dataset']['rows']}   "
        f"train: {report['dataset']['train_rows']}   oos: {report['dataset']['test_rows']}",
        f"metrics: acc={report['metrics'].get('accuracy')}  "
        f"auc={report['metrics'].get('roc_auc')}  f1={report['metrics'].get('f1')}",
        f"cv auc: {report['cv']['mean_roc_auc']} (std {report['cv']['std_roc_auc']})",
        f"trading (threshold={report['trading']['threshold']}):",
        f"  all_trades   {report['trading']['all_trades']}",
        f"  llm_baseline {report['trading']['llm_baseline']}",
        f"  ml_selected  {report['trading']['ml_selected']}",
        f"M3: ml_beats_llm={report['m3']['ml_beats_llm_pf']}  ml_beats_all={report['m3']['ml_beats_all_pf']}",
        "feature importance: " + ", ".join(
            f"{i['feature']}={i['importance']:.4f}" for i in report["feature_importance"][:10]
        ),
    ]
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    args = parse_args(argv)

    dataset_path = Path(args.dataset)
    if not dataset_path.is_file():
        sys.exit(f"Ошибка: файл датасета не найден: {dataset_path}")

    df = load_dataset(str(dataset_path))
    if df.empty:
        sys.exit("Ошибка: датасет пуст (только заголовок) — экспортируйте данные /api/v1/ml/dataset.")
    if len(df) < args.min_rows:
        sys.exit(
            f"Ошибка: строк в датасете {len(df)} < --min-rows {args.min_rows}. "
            "Накопите больше закрытых позиций или снизьте порог."
        )
    if not 0.0 < args.test_size <= 0.5:
        sys.exit("Ошибка: --test-size должен быть в диапазоне (0.0, 0.5].")

    train, test = split_temporal(df, args.test_size, args.seed)
    train, val = split_temporal(train, min(args.test_size, 0.1), args.seed)

    y_train = train[TARGET].astype(int)
    y_val = val[TARGET].astype(int)
    y_test = test[TARGET].astype(int)
    if y_train.nunique() < 2:
        sys.exit("Ошибка: в train-выборке только один класс — обучение не имеет смысла.")

    features = NUMERIC_FEATURES + CATEGORICAL_FEATURES
    cat_indices = list(range(len(NUMERIC_FEATURES), len(features)))
    x_train = train[features]
    x_val = val[features]
    x_test = test[features]

    model_name = args.model
    try:
        if model_name == "catboost":
            model = train_catboost(x_train, y_train, x_val, y_val, cat_indices, args.seed)
        else:
            model = train_lightgbm(x_train, y_train, x_val, y_val, CATEGORICAL_FEATURES, args.seed)
    except ImportError as exc:
        sys.exit(f"Ошибка: не установлена библиотека для модели '{model_name}': {exc}")

    y_pred_proba = predict_proba(model, x_test)
    y_pred = (y_pred_proba >= args.threshold).astype(int)

    report: dict = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "model": model_name,
        "seed": args.seed,
        "threshold": args.threshold,
        "dataset": {
            "rows": int(len(df)),
            "train_rows": int(len(train)),
            "val_rows": int(len(val)),
            "test_rows": int(len(test)),
            "win_rate_train": round(float(y_train.mean()), 4),
            "win_rate_test": round(float(y_test.mean()), 4),
            "features": features,
            "categorical_features": CATEGORICAL_FEATURES,
        },
    }

    roc_auc = None
    if y_test.nunique() == 2:
        roc_auc = float(metrics.roc_auc_score(y_test, y_pred_proba))
    report["metrics"] = {
        "accuracy": float(metrics.accuracy_score(y_test, y_pred)),
        "precision": float(metrics.precision_score(y_test, y_pred, zero_division=0)),
        "recall": float(metrics.recall_score(y_test, y_pred, zero_division=0)),
        "f1": float(metrics.f1_score(y_test, y_pred, zero_division=0)),
        "roc_auc": roc_auc,
        "log_loss": float(metrics.log_loss(y_test, y_pred_proba)),
        "best_iteration": int(getattr(model, "best_iteration_", 0) or 0),
    }

    cv_aucs: list[float] = []
    if args.cv_folds > 1 and y_train.nunique() == 2:
        skf = StratifiedKFold(n_splits=args.cv_folds, shuffle=True, random_state=args.seed)
        for fold_train_idx, fold_val_idx in skf.split(x_train, y_train):
            try:
                if model_name == "catboost":
                    fold_model = train_catboost(
                        x_train.iloc[fold_train_idx], y_train.iloc[fold_train_idx],
                        x_train.iloc[fold_val_idx], y_train.iloc[fold_val_idx],
                        cat_indices, args.seed,
                    )
                else:
                    fold_model = train_lightgbm(
                        x_train.iloc[fold_train_idx], y_train.iloc[fold_train_idx],
                        x_train.iloc[fold_val_idx], y_train.iloc[fold_val_idx],
                        CATEGORICAL_FEATURES, args.seed,
                    )
                fold_proba = predict_proba(fold_model, x_train.iloc[fold_val_idx])
                if y_train.iloc[fold_val_idx].nunique() == 2:
                    cv_aucs.append(float(metrics.roc_auc_score(y_train.iloc[fold_val_idx], fold_proba)))
            except Exception:
                pass
    report["cv"] = {
        "folds": args.cv_folds,
        "mean_roc_auc": round(float(np.mean(cv_aucs)), 4) if cv_aucs else None,
        "std_roc_auc": round(float(np.std(cv_aucs)), 4) if cv_aucs else None,
    }

    test_with_pred = test.copy()
    test_with_pred["pred_proba"] = y_pred_proba
    test_with_pred["pred_win"] = y_pred
    all_trades = trading_stats(test_with_pred)
    llm_baseline = trading_stats(test_with_pred[test_with_pred["strategy_action"].isin(TRADING_ACTIONS)])
    ml_selected = trading_stats(test_with_pred[test_with_pred["pred_win"] == 1])
    report["trading"] = {
        "threshold": args.threshold,
        "all_trades": all_trades,
        "llm_baseline": llm_baseline,
        "ml_selected": ml_selected,
    }

    def beats_llm(baseline: dict, ml: dict) -> bool | None:
        if baseline["profit_factor"] is None or ml["profit_factor"] is None:
            return None
        return ml["profit_factor"] > baseline["profit_factor"]

    report["m3"] = {
        "ml_beats_llm_pf": beats_llm(llm_baseline, ml_selected),
        "ml_beats_all_pf": beats_llm(all_trades, ml_selected),
    }

    report["feature_importance"] = feature_importance(model, features, model_name)

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    model_path = out_dir / MODEL_FILE[model_name]
    if model_name == "catboost":
        model.save_model(str(model_path))
    else:
        model.booster_.save_model(str(model_path), num_iteration=model.best_iteration_ or None)
    (out_dir / "eval_report.json").write_text(json.dumps(sanitize(report), indent=2, ensure_ascii=False), encoding="utf-8")
    (out_dir / "feature_importance.tsv").write_text(
        "feature\timportance\n" + "\n".join(f"{i['feature']}\t{i['importance']}" for i in report["feature_importance"]),
        encoding="utf-8",
    )

    summary = print_summary(report)
    print(summary)
    (out_dir / "training.log").write_text(summary + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
