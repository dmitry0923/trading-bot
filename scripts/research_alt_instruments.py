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
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "artifacts"
OUT = ART / "alt_instruments_2026-11-04.json"

TRADING_DAYS = 249.0
MARGIN_CAP = 0.60          # RiskConfig.maxMarginUsagePercent
STRESS_MULTIPLIER = 1.5    # RiskConfig.stressedMarginMultiplier
KEY_RATE = 0.14            # ключевая ставка 2026 (docs/35)
TAX = 0.13                 # НДФЛ физлица


# --------------------------------------------------------------------------- #
# 1. Benchmark: денежный рынок
# --------------------------------------------------------------------------- #
def money_market_benchmark() -> dict:
    rows = list(csv.DictReader((ART / "cbr_rates_730d.csv").open(encoding="utf-8-sig")))
    ruonia = [float(r["ruonia"]) for r in rows if r.get("ruonia")]
    key = [float(r["key_rate"]) for r in rows if r.get("key_rate")]
    growth = 1.0
    for rate in ruonia:
        growth *= 1 + rate / 100 / 365
    years = len(ruonia) / 365
    annual = growth ** (1 / years) - 1
    return {
        "source": "artifacts/cbr_rates_730d.csv",
        "days": len(ruonia),
        "firstDate": rows[0]["rate_date"],
        "lastDate": rows[-1]["rate_date"],
        "years": round(years, 2),
        "ruoniaMeanPct": round(statistics.mean(ruonia), 2),
        "ruoniaMinPct": min(ruonia),
        "ruoniaMaxPct": max(ruonia),
        "keyRateFirstPct": key[0],
        "keyRateLastPct": key[-1],
        "cumulativePct": round((growth - 1) * 100, 2),
        "annualPct": round(annual * 100, 2),
        "annualVolPct": 0.0,
        "maxDrawdownPct": 0.0,
        "comment": (
            "Денежный рынок даёт почти бесрисковую доходность с нулевой просадкой; "
            "это планка, а не «ноль». Штраф за попытку: альфа должна превышать её."
        ),
    }


# --------------------------------------------------------------------------- #
# 2. Закон плеча
# --------------------------------------------------------------------------- #
def leverage_law() -> list[dict]:
    out = []
    for ratio in (0.2, 0.35, 0.5, 0.75, 1.0, 1.5, 2.0, 2.5, 3.0):
        out.append(
            {
                "annualReturnToMdd": ratio,
                "leverageFor100Pct": round(1.0 / ratio, 1) if ratio else None,
                "drawdownAt100Pct": round(100.0 / ratio, 1) if ratio else None,
                "verdict": (
                    "приемлемо (DD<=50%)" if ratio >= 2.0
                    else "погранично" if ratio >= 1.5
                    else "разорение раньше цели"
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
        {
            "name": "Денежный рынок (RUONIA)",
            "mu": 18.93, "mdd": 0.01, "basis": "100k капитала",
            "evidence": "фактическое компаундирование 2024-10..2026-10",
        },
    ]
    for s in sources:
        s["returnToMdd"] = round(s["mu"] / s["mdd"], 2) if s["mdd"] else None
        s["drawdownAt100Pct"] = (
            round(100.0 / s["returnToMdd"], 1) if s["returnToMdd"] else None
        )
    return sources


# --------------------------------------------------------------------------- #
# 4. Ёмкость инструментов: notional, ГО, максимальное плечо
# --------------------------------------------------------------------------- #
SPECS = [
    # ticker, type, lotSize, price, go
    ("CNYRUBF", "FUTURES", 1000, 12.63, 850.0),
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
        else:
            go_basis = "InstrumentsConfig (provisional, сверять с live GO)"
        for capital in (50_000.0, 100_000.0):
            affordable = (capital * MARGIN_CAP) / (go * STRESS_MULTIPLIER)
            notional_max = affordable * notional
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
                    "maxContractsAtStress": round(affordable, 2),
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
    # реалистичная оценка через премию за риск волатильности (IV^2 − RV^2)
    vrp = []
    for iv, rv_mult in ((0.20, 0.85), (0.25, 0.85), (0.25, 0.90)):
        rv = iv * rv_mult
        # приближение дельта-хеджированной короткой волатильности:
        # EV ≈ 0.2 · S · (σ_iv² − σ_rv²) · √T / σ_iv на месяц
        ev_month = 0.2 * f * (iv * iv - rv * rv) * math.sqrt(t) / iv
        vrp.append(
            {
                "ivPct": round(iv * 100, 1),
                "rvPct": round(rv * 100, 1),
                "ivMinusRvVolPoints": round((iv - rv) * 100, 1),
                "expectedRubPerMonth": round(ev_month, 0),
                "expectedPctOfNotionalPerMonth": round(100 * ev_month / f, 2),
                "expectedAnnualOnNotionalPct": round(1200 * ev_month / f, 1),
                "expectedAnnualOnMargin20Pct": round(6000 * ev_month / f, 1),
            }
        )
    return {
        "underlyingNotionalRub": f,
        "days": 30,
        "riskFreePct": r * 100,
        "structure": "продажа 15Δ стрэнгла (put и call вне денег)",
        "grid": rows,
        "tail": tail,
        "varianceRiskPremium": vrp,
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


# --------------------------------------------------------------------------- #
# 6. Диверсификация: сколько сливов нужно
# --------------------------------------------------------------------------- #
def diversification() -> dict:
    rows = []
    for r_single in (0.4, 0.6, 0.8, 1.0, 1.5):
        for n in (1, 3, 5, 8, 13):
            r_port = r_single * math.sqrt(n)
            rows.append(
                {
                    "singleReturnToMdd": r_single,
                    "sleeves": n,
                    "portfolioReturnToMdd": round(r_port, 2),
                    "drawdownAt100Pct": round(100 / r_port, 1),
                    "reaches100WithAcceptableDd": r_port >= 2.0,
                }
            )
    return {"assumption": "сливы некоррелированы, r_portfolio ≈ r·√N", "rows": rows}


def available_portfolio() -> dict:
    """Что даёт портфель ВСЕХ найденных источников при нулевой корреляции."""
    sleeves = [
        ("S1 momentum/carry", 0.56),
        ("Carry H1", 0.88),
        ("U8 cross-section (полная история)", 0.37),
    ]
    rows = []
    for n in (1, 2, 3):
        chosen = sleeves[:n]
        r_port = math.sqrt(sum(r * r for _, r in chosen))
        rows.append(
            {
                "sleeves": [name for name, _ in chosen],
                "portfolioReturnToMdd": round(r_port, 2),
                "drawdownAt100Pct": round(100 / r_port, 1),
                "reaches100WithDdUnder50": r_port >= 2.0,
            }
        )
    return {
        "assumption": "равные риск-веса, нулевая корреляция, r_portfolio = sqrt(Σ r_i²)",
        "rows": rows,
        "upperBoundIfU8HoldoutIsReal": {
            "portfolioReturnToMdd": round(
                math.sqrt(0.56 ** 2 + 0.88 ** 2 + 1.57 ** 2), 2
            ),
            "drawdownAt100Pct": round(
                100 / math.sqrt(0.56 ** 2 + 0.88 ** 2 + 1.57 ** 2), 1
            ),
            "note": "только если holdout-оценка U8 (r=1,57) устойчива; dev-гейт её не подтверждает",
        },
        "note": (
            "Даже объединение всех трёх независимых источников даёт r ≈ 1,1: просадка при "
            "100% около 90%. Для DD ≤ 35% нужен r ≈ 2,9 — этого в доступном наборе нет."
        ),
    }


def main() -> None:
    result = {
        "generatedAt": "2026-11-04",
        "purpose": "Скрининг альтернативных инструментов/классов под цель 100% годовых",
        "constraints": {
            "capitalRub": [50_000, 100_000],
            "marginCapPct": MARGIN_CAP * 100,
            "stressMultiplier": STRESS_MULTIPLIER,
            "taxPct": TAX * 100,
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
        "diversification": diversification(),
        "availablePortfolio": available_portfolio(),
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"written {OUT}")
    mm = result["moneyMarket"]
    print(f"money market: {mm['annualPct']}%/yr, DD {mm['maxDrawdownPct']}%, "
          f"RUONIA mean {mm['ruoniaMeanPct']}%")
    for s in result["measuredSources"]:
        print(f"  {s['name'][:55]:55s} mu={s['mu']:6.2f}% D={s['mdd']:6.2f}% "
              f"r={s['returnToMdd']} DD@100={s['drawdownAt100Pct']}%")
    print("instrument capacity (50k):")
    for row in result["instrumentCapacity"]:
        if row["capitalRub"] == 50_000:
            print(f"  {row['ticker']:9s} notional={row['notionalRub']:>10.0f} "
                  f"GO={row['goRub']:>9.0f} ({row['goToNotionalPct']:>5.1f}%) "
                  f"maxContracts={row['maxContractsAtStress']:>6.2f} "
                  f"lev={row['maxNotionalLeverage']:>5.2f}x")


if __name__ == "__main__":
    main()
