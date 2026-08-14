"""
Smoke-тесты обучающего пайплайна (ml/train.py) на синтетическом датасете.

Требуют lightgbm (лёгкая зависимость, используется для быстрого прогона);
при отсутствии библиотеки тесты пропускаются (pytest.importorskip).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

lightgbm = pytest.importorskip("lightgbm")

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import train  # noqa: E402

COLS = train.HEADER_COLUMNS
NUMERIC_FEATURES = train.NUMERIC_FEATURES


def make_synthetic_csv(path: Path, n_rows: int = 300) -> None:
    rng = np.random.default_rng(42)
    data = {}
    for col in COLS:
        if col in ("position_id", "hour_of_day", "in_blind_spot_hour", "win"):
            data[col] = np.zeros(n_rows, dtype=int)
        elif col in ("duration_min",):
            data[col] = np.zeros(n_rows, dtype=int)
        elif col in NUMERIC_FEATURES:
            data[col] = rng.normal(size=n_rows)
        else:
            data[col] = [""] * n_rows

    base = rng.normal(size=n_rows)
    data["win"] = (base > 0).astype(int)
    data["pnl_rub"] = np.where(data["win"] == 1, abs(base) * 100, -abs(base) * 100)
    data["strategy_signal_strength"] = rng.uniform(0, 1, n_rows)
    data["strategy_signal_strength"][::5] = np.nan
    data["strategy_action"] = rng.choice(["BUY", "SELL", "HOLD", "CLOSE"], n_rows)
    data["strategy_action"][::7] = ""
    data["direction"] = rng.choice(["LONG", "SHORT"], n_rows)
    data["hour_of_day"] = rng.integers(0, 24, n_rows)
    data["in_blind_spot_hour"] = rng.integers(0, 2, n_rows)
    data["opened_at"] = pd.date_range("2025-01-01", periods=n_rows, freq="1h")
    data["position_id"] = np.arange(1, n_rows + 1)
    data["macro_source"] = rng.choice(["SNAPSHOT", "CURRENT"], n_rows)

    df = pd.DataFrame(data)
    df["rsi14"] = df["rsi14"].astype(float)
    df["cbr_rate"] = rng.uniform(0, 20, n_rows)
    df["brent"] = rng.uniform(40, 120, n_rows)
    df["usd_rub"] = rng.uniform(60, 120, n_rows)
    df["strategy_signal_strength"] = df["strategy_signal_strength"].astype(float)
    df.to_csv(path, index=False)


def run_training(dataset: Path, output_dir: Path, **overrides) -> int:
    args = ["--dataset", str(dataset), "--output-dir", str(output_dir), "--model", "lightgbm"]
    for key, value in overrides.items():
        args += [f"--{key}", str(value)]
    return train.main(args)


def test_train_success_creates_artifacts(tmp_path: Path) -> None:
    dataset = tmp_path / "dataset.csv"
    out = tmp_path / "artifacts"
    make_synthetic_csv(dataset)

    code = run_training(dataset, out, seed=7)

    assert code == 0
    assert (out / "model.txt").is_file()
    report = json.loads((out / "eval_report.json").read_text(encoding="utf-8"))
    assert (out / "feature_importance.tsv").is_file()
    assert (out / "training.log").is_file()
    assert report["model"] == "lightgbm"
    assert report["dataset"]["rows"] == 300
    assert report["dataset"]["test_rows"] == 60
    assert report["metrics"]["roc_auc"] is not None
    assert len(report["feature_importance"]) == len(train.NUMERIC_FEATURES) + len(train.CATEGORICAL_FEATURES)
    assert {"threshold", "all_trades", "llm_baseline", "ml_selected"} <= report["trading"].keys()
    assert "ml_beats_llm_pf" in report["m3"]


def test_train_rejects_empty_dataset(tmp_path: Path) -> None:
    dataset = tmp_path / "empty.csv"
    dataset.write_text(",".join(COLS) + "\n", encoding="utf-8")
    with pytest.raises(SystemExit) as exc:
        run_training(dataset, tmp_path / "out")
    assert "пуст" in str(exc.value)


def test_train_rejects_too_few_rows(tmp_path: Path) -> None:
    dataset = tmp_path / "small.csv"
    out = tmp_path / "out"
    make_synthetic_csv(dataset, n_rows=20)
    with pytest.raises(SystemExit) as exc:
        run_training(dataset, out)
    assert "min-rows" in str(exc.value)


def test_train_rejects_schema_drift(tmp_path: Path) -> None:
    dataset = tmp_path / "drift.csv"
    pd.DataFrame({"win": [1, 0]}).to_csv(dataset, index=False)
    with pytest.raises(SystemExit) as exc:
        run_training(dataset, tmp_path / "out")
    assert "колонок" in str(exc.value)
