#!/usr/bin/env python3
"""Пересчёт экономики цели «100% годовых» из существующих артефактов.

Не запускает бэктест и не меняет данные: только читает
`artifacts/*.csv|json`, полученные предрегистрированными прогонами
2026-11-02, и считает то, чего в них нет:

  * MDD по датам выхода (а не по порядку строк) и return/MDD;
  * вклад каждого инструмента в OOS-прибыль (концентрация);
  * годовую доходность на депозитах 50k/100k при 1 контракте;
  * требуемый захват дневного диапазона и требуемый net на сделку
    для 100% годовых при maxC 1..8;
  * вероятности сценариев (логнормальное приближение).

Запуск: python3 scripts/research_plan_100pct.py
Выход:  artifacts/plan_100pct_2026-11-04.json
"""

from __future__ import annotations

import csv
import json
import math
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "artifacts"
OUT = ART / "plan_100pct_2026-11-04.json"

TRADING_DAYS_PER_YEAR = 249.0
CNY_LOT = 1000.0  # CNY за контракт
CNY_PRICE = 12.8  # ориентир 2026, для notional
CONTRACT_NOTIONAL = CNY_LOT * CNY_PRICE  # ≈ 12 800 ₽
GO_CONSERVATIVE = 850.0
GO_STRESSED = 2700.0


def _num(value: str) -> float:
    """Русская локаль в CSV артефактов: '−54,211' / '18,94'."""
    cleaned = (
        value.replace("\u00a0", "")
        .replace(" ", "")
        .replace("\u2212", "-")
        .replace(",", ".")
    )
    try:
        return float(cleaned)
    except ValueError:
        return 0.0


def load_trades(path: Path, pnl_column: str) -> list[dict]:
    with path.open(encoding="utf-8-sig") as handle:
        rows = list(csv.DictReader(handle))
    out = []
    for row in rows:
        out.append(
            {
                "ticker": row["ticker"],
                "entry": row.get("entryDate") or row.get("entryTime", ""),
                "exit": row.get("exitDate") or row.get("exitTime", ""),
                "pnl": _num(row[pnl_column]),
            }
        )
    return out


def stats(trades: list[dict]) -> dict:
    if not trades:
        return {"trades": 0}
    ordered = sorted(trades, key=lambda t: t["exit"])
    pnls = [t["pnl"] for t in ordered]
    cum = peak = mdd = 0.0
    for pnl in pnls:
        cum += pnl
        peak = max(peak, cum)
        mdd = max(mdd, peak - cum)
    gains = sum(p for p in pnls if p > 0)
    losses = -sum(p for p in pnls if p < 0)
    years = _years(ordered)
    return {
        "trades": len(pnls),
        "netRub": round(cum, 2),
        "avgNetRub": round(statistics.mean(pnls), 2),
        "medianNetRub": round(statistics.median(pnls), 2),
        "winRatePct": round(100 * sum(1 for p in pnls if p > 0) / len(pnls), 1),
        "profitFactor": round(gains / losses, 3) if losses else None,
        "mddRub": round(mdd, 2),
        "returnToMdd": round(cum / mdd, 2) if mdd else None,
        "years": round(years, 2),
        "netRubPerYear": round(cum / years, 2),
        "mddRubPerYear": round(mdd / years, 2),
    }


def _years(trades: list[dict]) -> float:
    first = min(t["entry"] for t in trades)[:10]
    last = max(t["exit"] for t in trades)[:10]
    days = _days_between(first, last)
    return max(days / 365.25, 0.1)


def _days_between(a: str, b: str) -> int:
    from datetime import date

    ya, ma, da = (int(x) for x in a.split("-"))
    yb, mb, db = (int(x) for x in b.split("-"))
    return (date(yb, mb, db) - date(ya, ma, da)).days


def by_ticker(trades: list[dict]) -> dict:
    result = {}
    for ticker in sorted({t["ticker"] for t in trades}):
        subset = [t for t in trades if t["ticker"] == ticker]
        st = stats(subset)
        st["shareOfNetPct"] = None
        result[ticker] = st
    total = sum(v["netRub"] for v in result.values() if "netRub" in v)
    for value in result.values():
        if value.get("netRub") is not None and total:
            value["shareOfNetPct"] = round(100 * value["netRub"] / total, 1)
    return result


def yearly(trades: list[dict]) -> dict:
    years = {}
    for t in trades:
        year = t["exit"][:4]
        years.setdefault(year, []).append(t["pnl"])
    return {y: {"trades": len(v), "netRub": round(sum(v), 2)} for y, v in sorted(years.items())}


def capacity_block() -> dict:
    data = json.loads((ART / "capacity_audit_2026-11-02.json").read_text(encoding="utf-8"))
    annual_per_contract = data["annualMaxProfitPerContractRub"]
    avg_daily_range = data["avgDailyRangeRubPerUnit"]
    rows = []
    for deposit in (50_000.0, 100_000.0):
        for max_c in (1, 2, 3, 4, 6, 8):
            ceiling = 100 * annual_per_contract * max_c / deposit
            rows.append(
                {
                    "depositRub": deposit,
                    "maxContracts": max_c,
                    "ceilingAnnualPct": round(ceiling, 2),
                    "requiredCaptureOfIdealPct": round(
                        100.0 * deposit / (annual_per_contract * max_c), 1
                    ),
                }
            )
    # требуемый net на сделку = 100% * депозит / (сделок за год)
    requirement = []
    for deposit in (50_000.0, 100_000.0):
        for max_c in (1, 2, 3, 4, 6, 8):
            requirement.append(
                {
                    "depositRub": deposit,
                    "maxContracts": max_c,
                    "requiredNetRubPerTradeByTradesPerYear": {
                        str(n): round(deposit / n, 2) for n in (41, 100, 250, 500)
                    },
                    "requiredCaptureOfAvgDailyRangePct": round(
                        100
                        * (deposit / TRADING_DAYS_PER_YEAR)
                        / (max_c * avg_daily_range * CNY_LOT),
                        1,
                    ),
                }
            )
    return {
        "annualIdealProfitPerContractRub": annual_per_contract,
        "avgDailyRangeRubPerContract": round(avg_daily_range * CNY_LOT, 2),
        "tradingDays": data["tradingDays"],
        "ceiling": rows,
        "requirements": requirement,
    }


def scenario(name: str, mean_pct: float, vol_pct: float) -> dict:
    """Логнормальное приближение: P(return >= 100%) при заданных mean/vol."""
    mu = math.log(1 + mean_pct / 100) - 0.5 * (vol_pct / 100) ** 2
    sigma = vol_pct / 100
    z = (math.log(2.0) - mu) / sigma
    p_100 = 0.5 * math.erfc(z / math.sqrt(2))
    median = 100 * (math.exp(mu) - 1)
    return {
        "scenario": name,
        "assumedMeanPct": mean_pct,
        "assumedVolPct": vol_pct,
        "medianReturnPct": round(median, 1),
        "probabilityOf100PctOrMore": round(p_100, 4),
        "probabilityOfLoss": round(
            0.5 * math.erfc(mu / (sigma * math.sqrt(2))), 4
        ),
    }


def main() -> None:
    engine = load_trades(ART / "engine_momentum_2026-11-02_oos.csv", "pnl")
    prescreen = load_trades(ART / "multi_momentum_2026-11-02_oos.csv", "net")

    result = {
        "generatedAt": "2026-11-04",
        "purpose": "Экономика цели 100% годовых на ММВБ: пересчёт из артефактов 2026-11-02",
        "assumptions": {
            "contractNotionalRub": CONTRACT_NOTIONAL,
            "goConservativeRub": GO_CONSERVATIVE,
            "goStressedRub": GO_STRESSED,
            "tradingDaysPerYear": TRADING_DAYS_PER_YEAR,
            "sourceEngine": "engineVersion 2026-10-26.2",
        },
        "candidates": {
            "engineMomentumOos": {
                "source": "artifacts/engine_momentum_2026-11-02_oos.csv",
                "universe": ["CNYRUBF", "USDRUBF", "EURRUBF", "GLDRUBF", "IMOEXF"],
                "sizing": "1 контракт на инструмент, maxC=1",
                "overall": stats(engine),
                "perTicker": by_ticker(engine),
                "perYear": yearly(engine),
            },
            "prescreenMomentumOos": {
                "source": "artifacts/multi_momentum_2026-11-02_oos.csv",
                "universe": ["CNYRUBF", "USDRUBF", "EURRUBF", "GLDRUBF", "IMOEXF"],
                "sizing": "1 контракт на инструмент",
                "overall": stats(prescreen),
                "perTicker": by_ticker(prescreen),
                "perYear": yearly(prescreen),
            },
        },
        "capacity": capacity_block(),
        "scenarios": [
            scenario("conservative", 20.0, 15.0),
            scenario("base", 50.0, 30.0),
            scenario("aggressive", 100.0, 60.0),
        ],
    }

    # доходность на депозит при 1 контракте (рубли не зависят от капитала)
    for key, candidate in result["candidates"].items():
        per_year = candidate["overall"]["netRubPerYear"]
        candidate["annualReturnOnDepositPct"] = {
            str(d): round(100 * per_year / d, 1) for d in (50_000, 100_000, 200_000)
        }
        candidate["mddAsShareOfDepositPct"] = {
            str(d): round(100 * candidate["overall"]["mddRub"] / d, 1)
            for d in (50_000, 100_000, 200_000)
        }

    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"written {OUT}")
    for key, candidate in result["candidates"].items():
        o = candidate["overall"]
        print(
            f"{key}: trades={o['trades']} net={o['netRub']} avg={o['avgNetRub']} "
            f"pf={o['profitFactor']} mdd={o['mddRub']} ret/dd={o['returnToMdd']} "
            f"net/yr={o['netRubPerYear']} annual%={candidate['annualReturnOnDepositPct']}"
        )
    for s in result["scenarios"]:
        print(s)


if __name__ == "__main__":
    main()
