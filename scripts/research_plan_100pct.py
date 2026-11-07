#!/usr/bin/env python3
"""Пересчёт экономики цели «100% годовых» из существующих артефактов.

Не запускает бэктест и не меняет данные: только читает
`artifacts/*.csv|json`, полученные предрегистрированными прогонами
2026-11-02, и считает то, чего в них нет:

  * дневной closed-trade MDD по датам выхода и annualized return/MDD;
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

from research_metadata import provenance

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "artifacts"
OUT = ART / "plan_100pct_2026-11-04.json"

TRADING_DAYS_PER_YEAR = 249.0
CNY_LOT = 1000.0  # CNY за контракт
CNY_PRICE = 12.8  # ориентир 2026, для notional
CONTRACT_NOTIONAL = CNY_LOT * CNY_PRICE  # ≈ 12 800 ₽
GO_CONSERVATIVE = 850.0
GO_STRESSED = 2700.0
TAX_RATE = 0.13
TARGET_NET_RETURN = 1.0
ENGINE_VERSION = "2026-10-26.2"


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
    ordered = sorted(trades, key=lambda t: (t["exit"], t["ticker"], t["entry"]))
    pnls = [t["pnl"] for t in ordered]
    daily_pnl: dict[str, float] = {}
    for trade in ordered:
        day = trade["exit"][:10]
        daily_pnl[day] = daily_pnl.get(day, 0.0) + trade["pnl"]

    # Closed-trade daily equity: одинаковый день не должен зависеть от порядка строк.
    # Это не mark-to-market MDD открытых позиций.
    cum = peak = mdd = 0.0
    for day in sorted(daily_pnl):
        cum += daily_pnl[day]
        peak = max(peak, cum)
        mdd = max(mdd, peak - cum)
    total = sum(pnls)
    gains = sum(p for p in pnls if p > 0)
    losses = -sum(p for p in pnls if p < 0)
    years = _years(ordered)
    annual = total / years
    return {
        "trades": len(pnls),
        "tradesPerYear": round(len(pnls) / years, 1),
        "netRub": round(total, 2),
        "avgNetRub": round(statistics.mean(pnls), 2),
        "medianNetRub": round(statistics.median(pnls), 2),
        "winRatePct": round(100 * sum(1 for p in pnls if p > 0) / len(pnls), 1),
        "profitFactor": round(gains / losses, 3) if losses else None,
        "mddRub": round(mdd, 2),
        "mddMethod": "daily-closed-trades-by-exit-date",
        "windowReturnToMdd": round(total / mdd, 2) if mdd else None,
        "annualReturnToMdd": round(annual / mdd, 2) if mdd else None,
        "years": round(years, 2),
        "netRubPerYear": round(annual, 2),
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
    requirement = []
    for deposit in (50_000.0, 100_000.0):
        target_net = deposit * TARGET_NET_RETURN
        target_pre_tax = target_net / (1 - TAX_RATE)
        for max_c in (1, 2, 3, 4, 6, 8):
            ceiling = 100 * annual_per_contract * max_c / deposit
            rows.append(
                {
                    "depositRub": deposit,
                    "maxContracts": max_c,
                    "targetNetProfitRub": round(target_net, 2),
                    "targetPreTaxProfitRub": round(target_pre_tax, 2),
                    "ceilingAnnualPctPreTax": round(ceiling, 2),
                    "requiredCaptureOfIdealPctPreTax": round(
                        100.0 * target_pre_tax / (annual_per_contract * max_c), 1
                    ),
                }
            )
            requirement.append(
                {
                    "depositRub": deposit,
                    "maxContracts": max_c,
                    "targetPreTaxProfitRub": round(target_pre_tax, 2),
                    "requiredPreTaxRubPerContractTradeByTradesPerYear": {
                        str(n): round(target_pre_tax / (n * max_c), 2)
                        for n in (41, 100, 250, 500)
                    },
                    "requiredCaptureOfAvgDailyRangePctPreTax": round(
                        100
                        * (target_pre_tax / TRADING_DAYS_PER_YEAR)
                        / (max_c * avg_daily_range * CNY_LOT),
                        1,
                    ),
                }
            )
    return {
        "annualIdealProfitPerContractRub": annual_per_contract,
        "avgDailyRangeRubPerContract": round(avg_daily_range * CNY_LOT, 2),
        "tradingDays": data["tradingDays"],
        "targetNetReturnPct": TARGET_NET_RETURN * 100,
        "taxRatePct": TAX_RATE * 100,
        "ceiling": rows,
        "requirements": requirement,
    }


def scenario(name: str, mean_pct: float, vol_pct: float) -> dict:
    """Терминальный lognormal-return при заданных arithmetic mean/vol.

    Вероятность не моделирует путь счёта, маржин-колл или ликвидацию.
    """
    arithmetic_mean = 1 + mean_pct / 100
    arithmetic_vol = vol_pct / 100
    if arithmetic_mean <= 0 or arithmetic_vol <= 0:
        raise ValueError("scenario requires positive mean wealth ratio and volatility")
    sigma = math.sqrt(math.log(1 + (arithmetic_vol / arithmetic_mean) ** 2))
    mu = math.log(arithmetic_mean) - 0.5 * sigma**2
    z = (math.log(2.0) - mu) / sigma
    p_100 = 0.5 * math.erfc(z / math.sqrt(2))
    median = 100 * (math.exp(mu) - 1)
    return {
        "scenario": name,
        "assumedMeanPct": mean_pct,
        "assumedVolPct": vol_pct,
        "volatilityDomain": "standard deviation of arithmetic annual return",
        "assumedLogVolPct": round(sigma * 100, 4),
        "medianReturnPct": round(median, 1),
        "probabilityOf100PctOrMore": round(p_100, 6),
        "probabilityOfLoss": round(0.5 * math.erfc(mu / (sigma * math.sqrt(2))), 6),
        "probabilityScope": "terminal return only; no path, margin or liquidation model",
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
            "targetNetReturnPct": TARGET_NET_RETURN * 100,
            "taxRatePct": TAX_RATE * 100,
            **provenance(ENGINE_VERSION),
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
            f"pf={o['profitFactor']} mdd={o['mddRub']} annual_ret/dd={o['annualReturnToMdd']} "
            f"net/yr={o['netRubPerYear']} annual%={candidate['annualReturnOnDepositPct']}"
        )
    for s in result["scenarios"]:
        print(s)


if __name__ == "__main__":
    main()
