#!/usr/bin/env python3
"""Скрининг альтернативных инструментов и классов стратегий под цель 100% годовых.

Отвечает на вопрос: «может, взять другой инструмент?» — количественно, а не на словах.

Три блока:

1. **Benchmark**: фактическая доходность денежного рынка (RUONIA) за 2024-10..2026-10
   из `artifacts/cbr_rates_730d.csv`. Это планка, которую обязан побить любой план.
2. **Закон плеча**: для источника с годовой доходностью mu и просадкой D при 1x
   плечо L даёт (L*mu, L*D), значит при цели 100% просадка равна 100/(mu/D).
   Считаем, какие инструменты/классы это выдерживают.
3. **Ёмкость инструментов**: notional, ГО, максимальное плечо при 50k/100k и лимите
   маржи 60% — по фактическим спецификациям `InstrumentsConfig` и медианным ценам
   из торговых логов.

Плюс модельные расчёты для классов, которых в репозитории нет:
Black-76 для продажи стрэнгла (опционы), дивидендный захват, календарный спред.

Запуск: python3 scripts/research_alt_instruments.py
Выход:  artifacts/alt_instruments_2026-11-04.json
"""

from __future__ import annotations

import csv
import json
import math
import statistics
from datetime import date
from pathlib import Path

from research_metadata import provenance

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "artifacts"
OUT = ART / "alt_instruments_2026-11-04.json"

TRADING_DAYS = 249.0
MARGIN_CAP = 0.60          # RiskConfig.maxMarginUsagePercent
STRESS_MULTIPLIER = 1.5    # RiskConfig.stressedMarginMultiplier
KEY_RATE = 0.14            # ключевая ставка 2026 (docs/35)
TAX = 0.13                 # НДФЛ физлица
TARGET_NET_RETURN = 1.0
TARGET_PRETAX_PCT = 100 * TARGET_NET_RETURN / (1 - TAX)
ENGINE_VERSION = "2026-10-26.2"


# --------------------------------------------------------------------------- #
# 1. Benchmark: денежный рынок
# --------------------------------------------------------------------------- #
def money_market_benchmark() -> dict:
    rows = list(csv.DictReader((ART / "cbr_rates_730d.csv").open(encoding="utf-8-sig")))
    # Компаундинг по ФАКТИЧЕСКОМУ интервалу между наблюдениями (2026-11-04: было
    # по одному дню на строку — занижало кумулятив, т.к. строк ~491 на 727 дней).
    dated = [
        (date.fromisoformat(r["rate_date"]), float(r["ruonia"]))
        for r in rows
        if r.get("ruonia") and r.get("rate_date")
    ]
    dated.sort()
    ruonia = [rate for _, rate in dated]
    key = [float(r["key_rate"]) for r in rows if r.get("key_rate")]
    growth = 1.0
    for (d0, rate), (d1, _) in zip(dated, dated[1:]):
        elapsed = max((d1 - d0).days, 1)
        growth *= 1 + rate / 100 * elapsed / 365
    years = (dated[-1][0] - dated[0][0]).days / 365.25
    annual = growth ** (1 / years) - 1
    return {
        "source": "artifacts/cbr_rates_730d.csv",
        "days": len(ruonia),
        "firstDate": dated[0][0].isoformat(),
        "lastDate": dated[-1][0].isoformat(),
        "years": round(years, 2),
        "ruoniaMeanPct": round(statistics.mean(ruonia), 2),
        "ruoniaMinPct": min(ruonia),
        "ruoniaMaxPct": max(ruonia),
        "keyRateFirstPct": key[0],
        "keyRateLastPct": key[-1],
        "cumulativePct": round((growth - 1) * 100, 2),
        "annualPct": round(annual * 100, 2),
        "annualVolPct": None,
        "maxDrawdownPct": None,
        "accrualSeriesMaxDrawdownPct": 0.0,
        "comment": (
            "Это gross benchmark индекса ставки, а не доходность конкретного фонда. "
            "Нулевая просадка относится только к искусственно монотонному ряду накопления; "
            "для БПИФ нужны цены паёв, комиссии, налоги и спред."
        ),
    }


# --------------------------------------------------------------------------- #
# 2. Закон плеча
# --------------------------------------------------------------------------- #
def leverage_law() -> list[dict]:
    """Линейная иллюстрация масштабирования, а не прогноз выживаемости счёта.

    По одному r=mu/MDD нельзя определить плечо: для него отдельно нужна mu.
    """
    out = []
    for ratio in (0.2, 0.35, 0.5, 0.75, 1.0, 1.5, 2.0, 2.5, 3.0):
        out.append(
            {
                "annualReturnToMdd": ratio,
                "drawdownAt100PctNet": round(TARGET_PRETAX_PCT / ratio, 1),
                "verdict": (
                    "DD<=35% в линейной модели" if ratio >= TARGET_PRETAX_PCT / 35
                    else "DD>35% в линейной модели"
                ),
            }
        )
    return out


# --------------------------------------------------------------------------- #
# 3. Измеренные источники возврата (из артефактов, без переоценки)
# --------------------------------------------------------------------------- #
def measured_sources() -> list[dict]:
    """mu и D — годовая доходность и максимальная просадка в одинаковых единицах.

    Все значения — из артефактов 2026-11-02, пересчёт MDD по датам выхода см.
    `artifacts/plan_100pct_2026-11-04.json`.
    """
    sources = [
        {
            "name": "S1 momentum/carry 5 перпетуалов (pre-screen, OOS 2022-2024)",
            "mu": 21.5, "mdd": 38.6, "basis": "100k капитала, 1 контракт/инструмент",
            "evidence": "p=0,0875; 161 сделка; INCONCLUSIVE",
        },
        {
            "name": "S1 то же, формальный engine (OOS 2022-2024)",
            "mu": 8.7, "mdd": 36.6, "basis": "100k капитала, 1 контракт/инструмент",
            "evidence": "p=0,2795; 135 сделок; REJECTED dev-гейтом",
        },
        {
            "name": "Carry H1 CNYRUBF (2024-2026, 488 сделок)",
            "mu": 2.1, "mdd": 2.4, "basis": "100k капитала, 1 контракт",
            "evidence": "p=0,0545; NO_CANDIDATE (малая величина)",
        },
        {
            "name": "U8 cross-section 12-0: holdout-сегмент (2024-07..2026-10)",
            "mu": 26.2, "mdd": 16.7, "basis": "100k капитала, 1 позиция",
            "evidence": "p=0,042, CI95 содержит 0, robust=false, dev PF 1,065",
        },
        {
            "name": "U8 cross-section 12-0: полная история 2018-2026",
            "mu": 3.9, "mdd": 10.6, "basis": "100k капитала, 1 позиция",
            "evidence": "INCONCLUSIVE, consistency 0,75, p=0,075",
        },
        {
            "name": "Baseline CNYRUBF MINUTE_10 730д",
            "mu": 0.0, "mdd": 0.5, "basis": "100k капитала, maxC=1",
            "evidence": "PF 1,012; p=0,486; edge нет",
        },
    ]
    for s in sources:
        s["returnToMdd"] = round(s["mu"] / s["mdd"], 2) if s["mdd"] else None
        s["drawdownAt100PctNet"] = (
            round(TARGET_PRETAX_PCT / s["returnToMdd"], 1) if s["returnToMdd"] else None
        )
    return sources


# --------------------------------------------------------------------------- #
# 4. Ёмкость инструментов: notional, ГО, максимальное плечо
# --------------------------------------------------------------------------- #
SPECS = [
    # ticker, type, lotSize, price, go
    # Для CNYRUBF берём наблюдавшийся верхний live-GO 2700, а не устаревший spec.go=850.
    ("CNYRUBF", "FUTURES", 1000, 12.63, 2700.0),
    ("USDRUBF", "FUTURES", 1000, 91.61, 12630.0),
    ("EURRUBF", "FUTURES", 1000, 99.05, 14474.0),
    ("GLDRUBF", "FUTURES", 1, 8840.55, 1292.0),
    ("IMOEXF", "FUTURES", 10, 2753.50, 2264.0),
    ("SBERF", "FUTURES", 100, 304.59, 4901.15),
    ("GAZPF", "FUTURES", 100, 128.38, 1828.06),
    ("SBER", "STOCK", 10, 304.59, None),
    ("GAZP", "STOCK", 10, 128.38, None),
    ("LKOH", "STOCK", 1, 5917.75, None),
    ("NVTK", "STOCK", 1, 1144.60, None),
    ("PLZL", "STOCK", 1, 1958.60, None),
    ("ROSN", "STOCK", 1, 444.90, None),
    ("TATN", "STOCK", 1, 636.40, None),
    ("MGNT", "STOCK", 1, 3672.00, None),
]


def instrument_capacity() -> list[dict]:
    out = []
    for ticker, kind, lot, price, go in SPECS:
        notional = lot * price
        if go is None:
            # акции: маржинальное требование ~20% нотионала (брокерский риск-рейт)
            go = notional * 0.20
            go_basis = "оценка 20% нотионала (риск-рейт брокера), требуется сверка"
        elif ticker == "CNYRUBF":
            go_basis = "верх наблюдаемого live GO 850–2700 ₽; стресс ×1.5 дополнительно"
        else:
            go_basis = "InstrumentsConfig (provisional, сверять с live GO)"
        for capital in (50_000.0, 100_000.0):
            affordable = (capital * MARGIN_CAP) / (go * STRESS_MULTIPLIER)
            whole_contracts = math.floor(affordable)
            notional_max = whole_contracts * notional
            out.append(
                {
                    "ticker": ticker,
                    "type": kind,
                    "lotSize": lot,
                    "price": price,
                    "notionalRub": round(notional, 2),
                    "goRub": round(go, 2),
                    "goToNotionalPct": round(100 * go / notional, 2),
                    "capitalRub": capital,
                    "maxContractsAtStress": whole_contracts,
                    "fractionalContractCapacity": round(affordable, 2),
                    "maxNotionalRub": round(notional_max, 0),
                    "maxNotionalLeverage": round(notional_max / capital, 2),
                    "goBasis": go_basis,
                }
            )
    return out


# --------------------------------------------------------------------------- #
# 5. Модель short-vol (Black-76), дивиденды, календарный спред
# --------------------------------------------------------------------------- #
def norm_cdf(x: float) -> float:
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def black76(f: float, k: float, sigma: float, t: float, r: float) -> dict:
    if t <= 0 or sigma <= 0:
        return {"call": max(f - k, 0.0), "put": max(k - f, 0.0)}
    d1 = (math.log(f / k) + 0.5 * sigma * sigma * t) / (sigma * math.sqrt(t))
    d2 = d1 - sigma * math.sqrt(t)
    disc = math.exp(-r * t)
    call = disc * (f * norm_cdf(d1) - k * norm_cdf(d2))
    put = disc * (k * norm_cdf(-d2) - f * norm_cdf(-d1))
    return {"call": call, "put": put, "d1": d1, "d2": d2}


def short_vol_model() -> dict:
    """Продажа 15Δ стрэнгла на 30 дней на CNYRUBF-подобном underlying."""
    f = 12.63 * 1000           # нотионал контракта, ₽
    t = 30 / 365
    r = KEY_RATE
    rows = []
    for iv in (0.15, 0.20, 0.25, 0.30):
        z = 1.04               # ~15Δ
        k_put = f * math.exp(-z * iv * math.sqrt(t))
        k_call = f * math.exp(z * iv * math.sqrt(t))
        put = black76(f, k_put, iv, t, r)["put"]
        call = black76(f, k_call, iv, t, r)["call"]
        premium = put + call
        for margin_pct in (0.10, 0.20):
            margin = margin_pct * f
            monthly_on_margin = premium / margin
            rows.append(
                {
                    "ivPct": round(iv * 100, 1),
                    "marginPctOfNotional": round(margin_pct * 100, 1),
                    "premiumRub": round(premium, 0),
                    "premiumPctOfNotional": round(100 * premium / f, 2),
                    "marginRub": round(margin, 0),
                    "returnOnMarginIfExpiresPct": round(100 * monthly_on_margin, 2),
                    "annualizedIfAllWinPct": round(
                        100 * ((1 + monthly_on_margin) ** 12 - 1), 1
                    ),
                }
            )
    # tail-сценарии: гэп против позиции
    tail = []
    for iv in (0.20, 0.25):
        z = 1.04
        k_put = f * math.exp(-z * iv * math.sqrt(t))
        k_call = f * math.exp(z * iv * math.sqrt(t))
        premium = black76(f, k_put, iv, t, r)["put"] + black76(f, k_call, iv, t, r)["call"]
        for gap in (0.05, 0.10, 0.20):
            loss_put = max(k_put - f * (1 - gap), 0) - premium
            loss_call = max(f * (1 + gap) - k_call, 0) - premium
            worst = min(loss_put, loss_call)
            tail.append(
                {
                    "ivPct": round(iv * 100, 1),
                    "gapPct": gap * 100,
                    "pnlRub": round(worst, 0),
                    "pnlPctOfNotional": round(100 * worst / f, 1),
                    "pnlPctOfMargin20": round(100 * worst / (0.20 * f), 0),
                }
            )
    return {
        "underlyingNotionalRub": f,
        "days": 30,
        "riskFreePct": r * 100,
        "structure": "продажа 15Δ стрэнгла (put и call вне денег)",
        "grid": rows,
        "tail": tail,
        "note": (
            "Модель Black-76 на допущениях. Ожидаемая доходность = премия минус "
            "реализованные убытки; знак определяется разницей IV−RV, которую без "
            "истории опционов измерить нельзя. Здесь показан ТОЛЬКО верхний предел "
            "(все месяцы без убытка) и tail-сценарий."
        ),
    }


def dividend_model() -> dict:
    rows = []
    for dy in (0.08, 0.10, 0.12):
        for lev in (1.0, 2.0, 3.0):
            gross = dy * lev
            net = gross * (1 - TAX)
            rows.append(
                {
                    "dividendYieldPct": round(dy * 100, 1),
                    "leverage": lev,
                    "grossOnCapitalPct": round(100 * gross, 1),
                    "netAfterTaxPct": round(100 * net, 1),
                    "gapRiskPct": round(100 * 0.06 * lev, 1),
                    "comment": "гэп дивидендной отсечки 4–8% нивелирует 0,5–1 год дивиденда",
                }
            )
    return {
        "rows": rows,
        "note": (
            "Дивидендный захват: доходность ограничена дивидендной доходностью × плечо, "
            "а риск — гэпом отсечки и налогом 13% на дивиденды. Структурно не даёт 100%."
        ),
    }


def calendar_model() -> dict:
    """Базис между сериями: (r_rub - r_foreign) × T. Плюс спред ликвидности."""
    f = 100_000.0    # notional серии Si, ₽
    rows = []
    for months in (3, 6, 12):
        t = months / 12
        fair_basis = f * (KEY_RATE - 0.04) * t
        for capture in (0.25, 0.50):
            pnl = fair_basis * capture
            for margin_pct in (0.05, 0.10):
                margin = margin_pct * f
                rows.append(
                    {
                        "months": months,
                        "fairBasisRub": round(fair_basis, 0),
                        "capturedPct": capture * 100,
                        "pnlRub": round(pnl, 0),
                        "marginPctOfNotional": margin_pct * 100,
                        "marginRub": round(margin, 0),
                        "returnOnMarginPct": round(100 * pnl / margin, 1),
                        "annualizedPct": round(100 * pnl / margin / t, 1),
                    }
                )
    return {
        "notionalRub": f,
        "assumption": "ставка RUB 14%, валюта 4%, захват 25–50% справедливого базиса",
        "rows": rows,
        "note": (
            "Модель. Требует склейки срочных контрактов (в репозитории её нет) и "
            "проверки неттинга ГО на спред у брокера/биржи."
        ),
    }


def main() -> None:
    result = {
        "generatedAt": "2026-11-04",
        "purpose": "Скрининг альтернативных инструментов/классов под цель 100% годовых",
        "provenance": provenance(ENGINE_VERSION),
        "constraints": {
            "capitalRub": [50_000, 100_000],
            "marginCapPct": MARGIN_CAP * 100,
            "stressMultiplier": STRESS_MULTIPLIER,
            "taxPct": TAX * 100,
            "targetNetReturnPct": TARGET_NET_RETURN * 100,
            "targetPreTaxReturnPct": TARGET_PRETAX_PCT,
            "keyRatePct": KEY_RATE * 100,
            "note": (
                "В окружении нет ценовых рядов (только торговые логи артефактов), "
                "поэтому измеряемые блоки — из существующих артефактов, а модельные "
                "(опционы, дивиденды, календарные спреды) явно помечены как модель."
            ),
        },
        "moneyMarket": money_market_benchmark(),
        "leverageLaw": leverage_law(),
        "measuredSources": measured_sources(),
        "instrumentCapacity": instrument_capacity(),
        "shortVolModel": short_vol_model(),
        "dividendModel": dividend_model(),
        "calendarModel": calendar_model(),
        "portfolioLimitation": (
            "MDD разных сливов не объединяется через sqrt(sum(MDD^2)) или корреляционную "
            "формулу волатильности. Нужны синхронные daily equity/P&L ряды, которых нет."
        ),
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"written {OUT}")
    mm = result["moneyMarket"]
    print(f"money market: {mm['annualPct']}%/yr, DD {mm['maxDrawdownPct']}%, "
          f"RUONIA mean {mm['ruoniaMeanPct']}%")
    for s in result["measuredSources"]:
        print(f"  {s['name'][:55]:55s} mu={s['mu']:6.2f}% D={s['mdd']:6.2f}% "
              f"r={s['returnToMdd']} DD@100net={s['drawdownAt100PctNet']}%")
    print("instrument capacity (50k):")
    for row in result["instrumentCapacity"]:
        if row["capitalRub"] == 50_000:
            print(f"  {row['ticker']:9s} notional={row['notionalRub']:>10.0f} "
                  f"GO={row['goRub']:>9.0f} ({row['goToNotionalPct']:>5.1f}%) "
                  f"maxContracts={row['maxContractsAtStress']:>6.2f} "
                  f"lev={row['maxNotionalLeverage']:>5.2f}x")


if __name__ == "__main__":
    main()
