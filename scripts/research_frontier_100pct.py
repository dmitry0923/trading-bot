#!/usr/bin/env python3
"""60. Дюрация, обеспечение и налоги: воспроизводимые прокси без ложной MDD-алгебры.

Читает только artifacts/cbr_rates_730d.csv. Реальные цены ОФЗ, ставка финансирования,
условия брокера и синхронные equity-ряды сливов отсутствуют. Поэтому скрипт НЕ считает
портфельный MDD, вероятность достижения 100% или оптимальную композицию.

Запуск: python3 scripts/research_frontier_100pct.py
Выход:  artifacts/frontier_100pct_2026-11-04.json
"""
from __future__ import annotations

import bisect
import csv
import json
import statistics
from datetime import date, timedelta
from pathlib import Path

from research_metadata import provenance

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "artifacts"
OUT = ART / "frontier_100pct_2026-11-04.json"

TAX = 0.13
IIS3_CAP = 400_000.0
IIS3_MAX_DEDUCTION = 52_000.0
LDEV_LIMIT = 3_000_000.0
ENGINE_VERSION = "2026-10-26.2"


def load_rates() -> list[tuple[date, float, float | None]]:
    rows = list(csv.DictReader((ART / "cbr_rates_730d.csv").open(encoding="utf-8-sig")))
    out = []
    for row in rows:
        if not row.get("key_rate"):
            continue
        ruonia = float(row["ruonia"]) if row.get("ruonia") else None
        out.append((date.fromisoformat(row["rate_date"]), float(row["key_rate"]), ruonia))
    out.sort()
    if len(out) < 2:
        raise ValueError("CBR rates history must contain at least two key-rate rows")
    return out


def money_market(rates) -> dict:
    dated = [(d, r) for d, _, r in rates if r is not None]
    growth = 1.0
    for (d0, r0), (d1, _) in zip(dated, dated[1:]):
        growth *= 1 + r0 / 100 * (d1 - d0).days / 365
    years = (dated[-1][0] - dated[0][0]).days / 365.25
    return {
        "annualPct": round((growth ** (1 / years) - 1) * 100, 2),
        "cumulativePct": round((growth - 1) * 100, 2),
        "years": round(years, 2),
        "meanRuoniaPct": round(statistics.mean(r for _, r in dated), 2),
        "maxDrawdownPct": None,
        "accrualSeriesMaxDrawdownPct": 0.0,
        "scope": "gross RUONIA accrual benchmark, not an investable fund equity curve",
    }


def bond_curve(
    rates,
    spread_pp: float,
    mod_duration: float,
    start: str | None = None,
    end: str | None = None,
) -> tuple[list, float, float]:
    """Синтетическая постоянная дюрация: carry за elapsed days − D·Δkey_rate.

    Ключевая ставка НЕ является доходностью ОФЗ. Это sensitivity proxy, не бэктест.
    """
    pts = [
        (d, k)
        for d, k, _ in rates
        if (start is None or d >= date.fromisoformat(start))
        and (end is None or d <= date.fromisoformat(end))
    ]
    if len(pts) < 2:
        raise ValueError("bond curve requires at least two observations in the selected phase")
    equity, peak, mdd = 1.0, 1.0, 0.0
    prev_date = pts[0][0]
    y_prev = pts[0][1] + spread_pp
    curve = []
    for d, k in pts:
        y = k + spread_pp
        elapsed_days = (d - prev_date).days
        dy = (y - y_prev) / 100.0
        ret = (y_prev / 100.0) * elapsed_days / 365.0 - mod_duration * dy
        equity *= 1 + ret
        peak = max(peak, equity)
        mdd = max(mdd, (peak - equity) / peak)
        curve.append((d.isoformat(), round(equity, 6), round(y, 2)))
        prev_date = d
        y_prev = y
    years = (pts[-1][0] - pts[0][0]).days / 365.25
    annual = (equity ** (1 / years) - 1) * 100
    return curve, annual, mdd * 100


def rate_move_stress(rates, mod_duration: float) -> list[dict]:
    """Худший рост/снижение ставки за календарное окно, не за число строк CSV."""
    points = [(d, k) for d, k, _ in rates]
    dates = [d for d, _ in points]
    rows = []
    for window_days in (10, 20, 60):
        increases = []
        decreases = []
        for i, (end_date, end_rate) in enumerate(points):
            start_index = bisect.bisect_right(dates, end_date - timedelta(days=window_days)) - 1
            if start_index < 0 or start_index >= i:
                continue
            start_date, start_rate = points[start_index]
            item = {
                "fromTo": [start_date.isoformat(), end_date.isoformat()],
                "deltaPp": end_rate - start_rate,
            }
            increases.append(item)
            decreases.append(item)
        if not increases:
            continue
        increase = max(increases, key=lambda item: item["deltaPp"])
        decrease = min(decreases, key=lambda item: item["deltaPp"])
        loss_1x = -mod_duration * increase["deltaPp"]
        rows.append(
            {
                "windowCalendarDays": window_days,
                "maxRateIncreasePp": round(increase["deltaPp"], 2),
                "increaseFromTo": increase["fromTo"],
                "pnlPctAt1x": round(loss_1x, 1),
                "pnlPctAt3xBeforeFinancing": round(loss_1x * 3, 1),
                "maxRateDecreasePp": round(decrease["deltaPp"], 2),
                "decreaseFromTo": decrease["fromTo"],
            }
        )
    return rows


def duration_sleeve(rates) -> dict:
    variants = []
    for name, spread, dur in (
        ("ОФЗ 5л, D=4,2 (база)", -1.0, 4.2),
        ("ОФЗ 5л, D=4,2, спред -2 п.п.", -2.0, 4.2),
        ("ОФЗ 3л, D=2,6", -0.5, 2.6),
        ("ОФЗ 10л, D=7,0", -1.5, 7.0),
    ):
        _, annual, mdd = bond_curve(rates, spread, dur)
        variants.append(
            {
                "variant": name,
                "annualPct": round(annual, 1),
                "mddPct": round(mdd, 1),
                "returnToMdd": round(annual / mdd, 2) if mdd else None,
                "evidence": "synthetic key-rate sensitivity proxy",
            }
        )
    phases = []
    for name, start, end in (
        ("рост ставки / удержание 2024-10..2025-07", None, "2025-07-27"),
        ("снижение 2025-07..2026-10", "2025-07-28", None),
        ("полное окно 2024-10..2026-10", None, None),
    ):
        _, annual, mdd = bond_curve(rates, -1.0, 4.2, start, end)
        phases.append({"phase": name, "annualPct": round(annual, 1), "mddPct": round(mdd, 1)})
    base = variants[0]
    return {
        "model": "carry за фактические календарные дни − D·Δkey_rate; постоянная дюрация",
        "evidenceQuality": "ПРОКСИ: реальные цены/доходности ОФЗ отсутствуют",
        "variants": variants,
        "phases": phases,
        "stress": rate_move_stress(rates, 4.2),
        "haircuts": [
            {
                "haircut": haircut,
                "assumedMddPct": round(base["mddPct"] * haircut, 1),
                "returnToMdd": round(base["returnToMdd"] / haircut, 2),
            }
            for haircut in (1.0, 1.25, 1.5, 2.0)
        ],
        "caveat": (
            "Не учитывает реальную кривую доходностей, кредитный/ликвидностный спред, "
            "купоны конкретных выпусков, roll-down, издержки и финансирование. "
            "Точность ±2 п.п. не установлена и не утверждается."
        ),
    }


def rate_scenarios(rates) -> dict:
    """Форвардная sensitivity-таблица; плечо показано после условного financing."""
    financing_pct = rates[-1][1]
    rows = []
    for name, delta_pp, coupon, note in (
        ("ставка 14 → 10", -4.0, 12.5, "продолжение цикла снижения"),
        ("ставка 14 → 12", -2.0, 13.0, "условный базовый сценарий"),
        ("ставка 14 → 14", 0.0, 13.5, "без изменения"),
        ("ставка 14 → 16", 2.0, 14.5, "инфляционный шок"),
        ("ставка 14 → 18", 4.0, 15.5, "кризисный сценарий"),
    ):
        price = -4.2 * delta_pp
        gross_1x = price + coupon
        financing_cost = 2 * financing_pct
        rows.append(
            {
                "scenario": name,
                "priceEffectPct": round(price, 1),
                "couponPct": coupon,
                "return1xPct": round(gross_1x, 1),
                "return3xBeforeFinancingPct": round(gross_1x * 3, 1),
                "assumedFinancingRatePct": financing_pct,
                "financingCostAt3xPct": round(financing_cost, 1),
                "return3xAfterFinancingPct": round(gross_1x * 3 - financing_cost, 1),
                "adverseRateMovePnlAt3xPct": round(-4.2 * max(delta_pp, 0) * 3, 1),
                "note": note,
            }
        )
    return {
        "assumption": (
            "12 мес, D=4.2, 3x через заём 2x по последней ключевой ставке; "
            "реальная ставка маржинального финансирования может быть выше"
        ),
        "rows": rows,
    }


def sleeve_catalog(ruonia: float, duration_mu: float, duration_dd: float, duration_r: float) -> list[dict]:
    sleeves = [
        {"id": "CASH", "name": "RUONIA accrual benchmark", "mu": ruonia, "dd": None,
         "evidence": "ставка, не equity фонда", "status": "benchmark"},
        {"id": "DUR", "name": "Дюрация ОФЗ 5л", "mu": duration_mu, "dd": duration_dd,
         "evidence": "синтетический прокси", "status": "требует реальных цен и финансирования"},
        {"id": "CARRY", "name": "Carry CNYRUBF (H1)", "mu": 2.1, "dd": 2.4,
         "evidence": "488 сделок, p=0.055", "status": "NO_CANDIDATE"},
        {"id": "CAL", "name": "Календарные спреды", "mu": 3.75, "dd": 2.0,
         "evidence": "произвольная модель базиса", "status": "не измерено"},
        {"id": "VOL", "name": "Продажа волатильности", "mu": 4.0, "dd": 3.0,
         "evidence": "допущение, не VRP-измерение", "status": "не измерено"},
        {"id": "S1", "name": "S1 momentum/carry", "mu": 21.5, "dd": 38.6,
         "evidence": "pre-screen OOS, p=0.0875", "status": "INCONCLUSIVE"},
        {"id": "U8", "name": "U8 cross-section", "mu": 26.2, "dd": 16.7,
         "evidence": "INVALID по docs/29", "status": "не использовать как доказательство"},
    ]
    for sleeve in sleeves:
        dd = sleeve["dd"]
        sleeve["r"] = round(sleeve["mu"] / dd, 2) if dd else None
    next(sleeve for sleeve in sleeves if sleeve["id"] == "DUR")["r"] = duration_r
    return sleeves


def collateral(ruonia: float) -> dict:
    rows = []
    for name, eligible_fraction, fund_yield in (
        ("LQDT принят как ГО без дисконта", 1.0, ruonia),
        ("LQDT принят с дисконтом 50%", 0.5, ruonia),
        ("деньги как ГО, доходности нет", 1.0, 0.0),
        ("деньги как ГО, брокер платит 5%", 1.0, 5.0),
    ):
        rows.append(
            {
                "variant": name,
                "eligibleCollateralFraction": eligible_fraction,
                "cashOrFundYieldPct": round(fund_yield, 2),
                "totalPortfolioReturnPct": None,
            }
        )
    return {
        "mechanic": (
            "Доходность обеспечения можно добавить только после балансовой схемы: "
            "eligible collateral, haircut, variation margin, источник и стоимость плеча. "
            "Полное R не добавляется автоматически к каждой leveraged-позиции."
        ),
        "verification": [
            "риск-рейт НКЦ/брокера на LQDT",
            "приём паёв в обеспечение конкретного типа позиции",
            "покрытие variation margin и возможность продажи паёв",
            "ставка финансирования/репо и применимость к ИИС",
        ],
        "rows": rows,
        "status": "НЕ ПРОВЕРЕНО; +19 п.п. не является установленным результатом",
    }


def tax_layer() -> dict:
    rows = []
    for capital in (100_000, 300_000, 400_000):
        contribution = min(IIS3_CAP, capital)
        deduction = min(contribution * TAX, IIS3_MAX_DEDUCTION)
        rows.append(
            {
                "capitalRub": capital,
                "annualContributionRub": int(contribution),
                "deductionRub": int(deduction),
                "deductionPctOfCapital": round(deduction / capital * 100, 1),
                "isInvestmentReturn": False,
            }
        )
    return {
        "iis3": {
            "rules": "упрощённая модель вычета 13% до 52 тыс.; правила и сроки счёта проверить",
            "rows": rows,
            "note": "вычет субсидирует новый взнос и требует налогооблагаемого дохода",
        },
        "ldv": {
            "rule": "долгосрочное владение; лимит 3 млн ₽ × число лет, условия проверить",
            "estimatedTaxOnAnnualGainPctAt15": round(15 * TAX, 2),
            "note": "это налог при реализации, а не ежегодная альфа; активная торговля не подходит",
        },
    }


def new_candidates() -> list[dict]:
    return [
        {"id": "N1", "name": "Дюрация ОФЗ / БПИФ облигаций", "rEstimate": None,
         "dataNeed": "реальные цены ОФЗ/RGBI, финансирование", "verdict": "сначала данные"},
        {"id": "N2", "name": "Обеспечение в фонде денежного рынка", "rEstimate": None,
         "dataNeed": "условия брокера и балансовая схема", "verdict": "проверить операционно"},
        {"id": "N3", "name": "БПИФ против индексного фьючерса", "rEstimate": None,
         "dataNeed": "NAV и стакан", "verdict": "гипотеза"},
        {"id": "N4", "name": "Конверсии/реверсалы", "rEstimate": None,
         "dataNeed": "опционные цепочки bid/ask", "verdict": "после данных"},
        {"id": "N5", "name": "Событийная волатильность", "rEstimate": None,
         "dataNeed": "опционы и календарь", "verdict": "после данных"},
        {"id": "N6", "name": "Кросс-курсовой треугольник", "rEstimate": None,
         "dataNeed": "офшорные котировки", "verdict": "нет данных"},
        {"id": "N7", "name": "Дивидендный carry с хеджем", "rEstimate": None,
         "dataNeed": "дивиденды, финансирование, налоги", "verdict": "точечная гипотеза"},
        {"id": "N8", "name": "Репо с ЦК / свопы", "rEstimate": None,
         "dataNeed": "доступ к репо-секции", "verdict": "не для текущего счёта"},
        {"id": "N9", "name": "ИИС-3 + ЛДВ", "rEstimate": None,
         "dataNeed": "налоговый статус", "verdict": "не альфа"},
    ]


def main() -> None:
    rates = load_rates()
    mm = money_market(rates)
    duration = duration_sleeve(rates)
    base = duration["variants"][0]
    result = {
        "generatedAt": "2026-11-04",
        "purpose": "Дюрация, обеспечение и налоги: прокси и проверяемые гипотезы",
        "provenance": provenance(ENGINE_VERSION),
        "moneyMarket": mm,
        "duration": duration,
        "rateScenarios": rate_scenarios(rates),
        "sleeveCatalog": sleeve_catalog(
            mm["annualPct"], base["annualPct"], base["mddPct"], base["returnToMdd"]
        ),
        "collateral": collateral(mm["annualPct"]),
        "taxLayer": tax_layer(),
        "newCandidates": new_candidates(),
        "portfolioLimitation": (
            "Портфельный MDD, optimizer и вероятность достижения 100% намеренно не считаются: "
            "нет синхронных equity-рядов, реальных цен ОФЗ и условий финансирования. "
            "MDD не объединяется формулой sqrt(sum(MDD^2))."
        ),
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"written {OUT}")
    print(f"RUONIA benchmark: {mm['annualPct']}%/yr over {mm['years']} years")
    for variant in duration["variants"]:
        print(
            f"{variant['variant']}: annual={variant['annualPct']}%, "
            f"proxy MDD={variant['mddPct']}%, r={variant['returnToMdd']}"
        )
    for stress in duration["stress"]:
        print(
            f"{stress['windowCalendarDays']} calendar days: "
            f"+{stress['maxRateIncreasePp']} pp -> {stress['pnlPctAt3xBeforeFinancing']}% at 3x"
        )


if __name__ == "__main__":
    main()
