#!/usr/bin/env python3
"""60. Что ещё можно придумать под цель 100%: риск-бюджет, дюрация, обеспечение, налоги.

Пересчёт к docs/60-what-else-100pct-2026-11-04.md.
Детерминирован, только stdlib, читает artifacts/cbr_rates_730d.csv и
artifacts/alt_instruments_2026-11-04.json.

Главные блоки:
  cashFloor        — закон риск-бюджета: mu_max = R + DD * sqrt(sum r_i^2)
  durationSleeve   — дюрация ОФЗ как неизмеренный слив (прокси по ставке ЦБ)
  rateScenarios    — форвардные сценарии по ставке
  sleeveCatalog    — все сливы: mu, DD, r, ёмкость, качество доказательств
  optimizer        — оптимальная композиция при DD-бюджете и лимитах плеча
  collateral       — механика обеспечения: LQDT против «мёртвого» кэша
  taxLayer         — ИИС-3 / ЛДВ: что добавляют к доходности
  newCandidates    — классы, которых нет ни в docs/58, ни в docs/59
  scenarios        — консервативный / базовый / агрессивный
"""
from __future__ import annotations

import csv
import json
import statistics
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "artifacts"
OUT = ART / "frontier_100pct_2026-11-04.json"

TAX = 0.13            # НДФЛ физлица
IIS3_CAP = 400_000.0  # лимит взноса ИИС-3 в год
IIS3_MAX_DEDUCTION = 52_000.0
LDEV_LIMIT = 3_000_000.0  # ЛДВ: 3 млн ₽ вычета за каждый год владения


# --------------------------------------------------------------------------- #
# Входные данные
# --------------------------------------------------------------------------- #
def load_rates() -> list[tuple[date, float, float | None]]:
    rows = list(csv.DictReader((ART / "cbr_rates_730d.csv").open(encoding="utf-8-sig")))
    out = []
    for r in rows:
        if not r.get("key_rate"):
            continue
        ruonia = float(r["ruonia"]) if r.get("ruonia") else None
        out.append((date.fromisoformat(r["rate_date"]), float(r["key_rate"]), ruonia))
    out.sort()
    return out


def money_market(rates) -> dict:
    dated = [(d, r) for d, _, r in rates if r is not None]
    growth = 1.0
    for (d0, r0), (d1, _) in zip(dated, dated[1:]):
        growth *= 1 + r0 / 100 * max((d1 - d0).days, 1) / 365
    years = (dated[-1][0] - dated[0][0]).days / 365.25
    return {
        "annualPct": round((growth ** (1 / years) - 1) * 100, 2),
        "cumulativePct": round((growth - 1) * 100, 2),
        "years": round(years, 2),
        "meanRuoniaPct": round(statistics.mean(r for _, r in dated), 2),
    }


# --------------------------------------------------------------------------- #
# 1. Закон риск-бюджета
# --------------------------------------------------------------------------- #
def cash_floor_theorem(R: float) -> dict:
    """mu(L) = R + L*mu_i, DD(L) = L*D_i  =>  r(L) = r_i + R/(L*D_i).

    Максимум доходности при бюджете просадки DC и наборе сливов:
        mu_max = R + DC * sqrt(sum r_i^2)
    (следует из Коши—Шварца: точку максимума sum w_i r_i при ||w|| <= DC
     даёт w_i пропорционально r_i).
    """
    sleeves = {
        "carry CNYRUBF (измерено)": {"mu": 2.1, "dd": 2.4},
        "календарный спред (модель)": {"mu": 3.75, "dd": 2.0},
        "дюрация ОФЗ 5л (прокси)": {"mu": 25.5, "dd": 8.3},
    }
    rows = []
    for name, s in sleeves.items():
        r_i = s["mu"] / s["dd"]
        for L in (1.0, 2.0, 3.0, 5.94):
            mu = R + L * s["mu"]
            dd = L * s["dd"]
            rows.append(
                {
                    "sleeve": name,
                    "leverage": L,
                    "rOfSleeve": round(r_i, 2),
                    "totalReturnPct": round(mu, 1),
                    "totalDrawdownPct": round(dd, 1),
                    "totalR": round(mu / dd, 2),
                    "drawdownAt100Pct": round(100 / (mu / dd), 1),
                    "cashTermInR": round(R / (L * s["dd"]), 2),
                    "note": "кэш добавляет к r слива слагаемое R/(L*D_i)",
                }
            )
    budget_rows = []
    for dc in (0.20, 0.25, 0.30, 0.35, 0.50):
        for quality in (1.0, 1.5, 2.0, 2.32, 3.0, 3.23):
            budget_rows.append(
                {
                    "ddBudgetPct": round(dc * 100),
                    "sleeveQualitySqrtSumR2": quality,
                    "maxReturnPct": round(R + dc * quality * 100, 1),
                    "reaches100": R + dc * quality * 100 >= 100,
                }
            )
    return {
        "formulaPerSleeve": "r_total = r_i + R/(L*D_i);  mu_max = R + DD_cap*sqrt(sum r_i^2)",
        "riskFreePct": round(R, 2),
        "perSleeve": rows,
        "returnByRiskBudget": budget_rows,
        "requiredQuality": {
            f"dd_{int(dc*100)}": round((100 - R) / (dc * 100), 2)
            for dc in (0.20, 0.25, 0.30, 0.35, 0.50)
        },
        "insight": (
            "Плечо масштабирует прибыль И просадку линейно и не меняет r слива. "
            "Меняют r только две вещи: качество слива (r_i) и денежная нога R/(L*D). "
            "Поэтому цель 100% — это не вопрос плеча, а вопрос бюджета просадки и качества сливов."
        ),
    }


# --------------------------------------------------------------------------- #
# 2. Дюрация: прокси-модель ОФЗ по ставке ЦБ
# --------------------------------------------------------------------------- #
def bond_curve(rates, spread_pp: float, mod_duration: float,
               start: str | None = None, end: str | None = None,
               widen_pp: float = 0.0, widen_until: str | None = None) -> tuple[list, float, float]:
    """Дневная доходность облигации: y*dt - D*dy (постоянная срочность).

    y_t = key_rate_t + spread_pp. Купон начисляется на номинал, цена
    переоценивается по модифицированной дюральности. Прокси, не реальные ОФЗ.
    """
    until = date.fromisoformat(widen_until) if widen_until else None
    pts = [
        (d, k + (widen_pp if until and d <= until else 0.0))
        for d, k, _ in rates
        if (start is None or d >= date.fromisoformat(start))
        and (end is None or d <= date.fromisoformat(end))
    ]
    equity, peak, mdd = 1.0, 1.0, 0.0
    y_prev = pts[0][1] + spread_pp
    curve = []
    for d, k in pts:
        y = k + spread_pp
        dy = (y - y_prev) / 100.0
        ret = (y_prev / 100.0) / 365.0 - mod_duration * dy
        equity *= 1 + ret
        peak = max(peak, equity)
        mdd = max(mdd, (peak - equity) / peak)
        curve.append((d.isoformat(), round(equity, 6), round(y, 2)))
        y_prev = y
    years = (pts[-1][0] - pts[0][0]).days / 365.25
    annual = (equity ** (1 / years) - 1) * 100
    return curve, annual, mdd * 100


def duration_sleeve(rates) -> dict:
    variants = []
    for name, spread, dur in (
        ("ОФЗ 5л, D=4,2 (база)", -1.0, 4.2),
        ("ОФЗ 5л, D=4,2, спред -2 п.п.", -2.0, 4.2),
        ("ОФЗ 3л, D=2,6", -0.5, 2.6),
        ("ОФЗ 10л, D=7,0", -1.5, 7.0),
    ):
        curve, annual, mdd = bond_curve(rates, spread, dur)
        variants.append(
            {
                "variant": name,
                "annualPct": round(annual, 1),
                "mddPct": round(mdd, 1),
                "returnToMdd": round(annual / mdd, 2) if mdd else None,
                "drawdownAt100Pct": round(100 / (annual / mdd), 1) if mdd else None,
            }
        )
    phases = []
    for name, st, en in (
        ("рост ставки / удержание 2024-10..2025-07", None, "2025-07-27"),
        ("снижение 2025-07..2026-10", "2025-07-28", None),
        ("полное окно 2024-10..2026-10", None, None),
    ):
        _, annual, mdd = bond_curve(rates, -1.0, 4.2, st, en)
        phases.append(
            {"phase": name, "annualPct": round(annual, 1), "mddPct": round(mdd, 1)}
        )

    # стресс: максимальный сдвиг ставки за 10/20/60 дней
    ks = [(d, k) for d, k, _ in rates]
    stress = []
    for window in (10, 20, 60):
        worst = 0.0
        worst_at = None
        for i in range(window, len(ks)):
            delta = ks[i][1] - ks[i - window][1]
            if abs(delta) > abs(worst):
                worst, worst_at = delta, (ks[i - window][0], ks[i][0])
        pnl_1x = -4.2 * worst
        stress.append(
            {
                "windowDays": window,
                "worstRateMovePp": round(worst, 2),
                "fromTo": [worst_at[0].isoformat(), worst_at[1].isoformat()],
                "pnlPctAt1x": round(pnl_1x, 1),
                "pnlPctAt3x": round(pnl_1x * 3, 1),
                "verdict": "хуже просадки нет" if pnl_1x > 0 else "это и есть режимный риск",
            }
        )
    base_variant = variants[0]
    haircuts = [
        {
            "haircut": h,
            "assumedMddPct": round(base_variant["mddPct"] * h, 1),
            "returnToMdd": round(base_variant["annualPct"] / (base_variant["mddPct"] * h), 2),
        }
        for h in (1.0, 1.25, 1.5, 2.0)
    ]
    return {
        "haircuts": haircuts,
        "model": "постоянная срочность, y = key_rate + spread, дневная доходность y/365 - D*dy",
        "evidenceQuality": "ПРОКСИ: реальных цен ОФЗ в песочнице нет",
        "variants": variants,
        "phases": phases,
        "stress": stress,
        "caveat": (
            "Прокси воспроизводит ставку, а не рынок: не учитывает кредитный/ликвидностный "
            "спред, купонную структуру и roll-down. Знак и масштаб реакции на ставку верны, "
            "точность уровня — ±2 п.п./год. Это единственный измеренный слив с r > 2, но он "
            "описывает ОДИН цикл снижения ставки (2025-07..2026-10), а не повторяемую премию."
        ),
    }


def rate_scenarios(R: float) -> dict:
    """Форвардные сценарии по ставке: 12 мес, D=4,2, купон ~13,5%."""
    out = []
    for name, delta_pp, coupon, note in (
        ("ставка 14 → 10", -4.0, 12.5, "продолжение цикла снижения"),
        ("ставка 14 → 12", -2.0, 13.0, "базовый консенсус"),
        ("ставка 14 → 14 (на месте)", 0.0, 13.5, "стагнация дезинфляции"),
        ("ставка 14 → 16", 2.0, 14.5, "разворот, инфляционный шок"),
        ("ставка 14 → 18", 4.0, 15.5, "кризисный сценарий"),
    ):
        price = -4.2 * delta_pp
        gross_1x = price + coupon
        out.append(
            {
                "scenario": name,
                "priceEffectPct": round(price, 1),
                "couponPct": coupon,
                "return1xPct": round(gross_1x, 1),
                "return3xPct": round(gross_1x * 3, 1),
                "return3xWithCashPct": round(gross_1x * 3 + R, 1),
                "worstIntraDrawdown3xPct": round(-4.2 * abs(delta_pp) * 3, 1),
                "note": note,
            }
        )
    return {
        "assumption": "горизонт 12 мес, D=4,2, плечо 3x, купон по средней ставке",
        "rows": out,
    }


# --------------------------------------------------------------------------- #
# 3. Каталог сливов
# --------------------------------------------------------------------------- #
def sleeve_catalog(R: float, duration_mu: float, duration_dd: float) -> list[dict]:
    sleeves = [
        {
            "id": "CASH",
            "name": "Денежный рынок (LQDT / RUONIA)",
            "mu": R, "dd": 0.01, "maxLeverage": 1.0,
            "evidence": "измерено, 2,0 года, 491 наблюдение",
            "status": "работает",
        },
        {
            "id": "DUR",
            "name": "Дюрация ОФЗ 5л",
            "mu": duration_mu, "dd": duration_dd, "maxLeverage": 3.0,
            "evidence": "прокси по ставке ЦБ (реальных цен ОФЗ нет)",
            "status": "требует данных и сверки ГО",
        },
        {
            "id": "CARRY",
            "name": "Carry CNYRUBF (H1)",
            "mu": 2.1, "dd": 2.4, "maxLeverage": 5.94,
            "evidence": "измерено, 488 сделок, p=0,055",
            "status": "кандидат",
        },
        {
            "id": "CAL",
            "name": "Календарные спреды Si/RI/CNY",
            "mu": 3.75, "dd": 2.0, "maxLeverage": 5.94,
            "evidence": "модель базиса (docs/59 §5.1)",
            "status": "не начато",
        },
        {
            "id": "VOL",
            "name": "Продажа волатильности с хеджем хвоста",
            "mu": 4.0, "dd": 3.0, "maxLeverage": 4.0,
            "evidence": "модель VRP (docs/59 §5.2)",
            "status": "не начато",
        },
        {
            "id": "S1",
            "name": "S1 momentum/carry 5 перпетуалов",
            "mu": 21.5, "dd": 38.6, "maxLeverage": 2.0,
            "evidence": "pre-screen OOS, p=0,0875, INCONCLUSIVE",
            "status": "не прошёл гейты",
        },
        {
            "id": "U8",
            "name": "U8 cross-section 8 акций (holdout)",
            "mu": 26.2, "dd": 16.7, "maxLeverage": 2.0,
            "evidence": "p=0,042, но движок до фикса → по docs/29 INVALID",
            "status": "недействительно как доказательство",
        },
    ]
    for s in sleeves:
        s["r"] = round(s["mu"] / s["dd"], 2) if s["dd"] else None
        s["ddAt100Pct"] = round(100 / s["r"], 1) if s["r"] else None
        s["capacityNotionalPctOfEquity"] = round(s["maxLeverage"] * 100)
    return sleeves


# --------------------------------------------------------------------------- #
# 4. Оптимизатор: DD-бюджет + лимиты плеча
# --------------------------------------------------------------------------- #
def optimize(sleeves: list[dict], R: float, dd_budget: float) -> dict:
    """w_i = L_i*D_i — вклад слива в просадку капитала. max sum w_i*r_i, ||w||<=budget."""
    active = [s for s in sleeves if s["r"] and s["dd"] and s["id"] != "CASH"]
    w: dict[str, float] = {s["id"]: 0.0 for s in active}
    remaining = dd_budget
    pool = list(active)
    binding = []
    while pool and remaining > 1e-9:
        norm = sum(s["r"] ** 2 for s in pool) ** 0.5
        capped = []
        for s in pool:
            want = remaining * s["r"] / norm
            cap = s["maxLeverage"] * s["dd"] / 100.0
            if want >= cap:
                w[s["id"]] = cap
                capped.append(s)
                binding.append(s["id"])
        if not capped:
            for s in pool:
                w[s["id"]] = remaining * s["r"] / norm
            pool = []
            break
        used = sum(w[k] ** 2 for k in w)  # все закэпированные, не только текущая итерация
        remaining = max(dd_budget ** 2 - used, 0.0) ** 0.5
        pool = [s for s in pool if s not in capped]
    rows = []
    for s in active:
        wi = w[s["id"]]
        L = wi / (s["dd"] / 100.0) if s["dd"] else 0.0
        rows.append(
            {
                "id": s["id"],
                "leverage": round(L, 2),
                "riskContributionPct": round(wi * 100, 1),
                "returnContributionPct": round(wi * s["r"] * 100, 1),
                "capacityBinding": s["id"] in binding,
            }
        )
    risky = sum(r["returnContributionPct"] for r in rows) / 100
    total_dd = sum(r["riskContributionPct"] ** 2 for r in rows) ** 0.5 / 100
    mu = R + risky * 100
    return {
        "ddBudgetPct": round(dd_budget * 100),
        "allocation": rows,
        "totalReturnPct": round(mu, 1),
        "totalDrawdownPct": round(total_dd * 100, 1),
        "portfolioR": round(mu / (total_dd * 100), 2),
        "drawdownAt100Pct": round(100 / (mu / (total_dd * 100)), 1),
        "capacityBindingSleeves": binding,
    }


def correlation_sensitivity(opt: dict, rhos=(0.0, 0.3, 0.6, 0.8)) -> list[dict]:
    """Просадка при парной корреляции rho: sqrt(sum w^2 + rho*sum_{i!=j} w_i w_j)."""
    ws = [(a["id"], a["riskContributionPct"] / 100) for a in opt["allocation"]
          if a["riskContributionPct"] > 0]
    out = []
    for rho in rhos:
        var = sum(w * w for _, w in ws) + rho * sum(
            ws[i][1] * ws[j][1] for i in range(len(ws)) for j in range(len(ws)) if i != j
        )
        dd = var ** 0.5 * 100
        out.append(
            {
                "pairwiseCorrelation": rho,
                "effectiveDrawdownPct": round(dd, 1),
                "returnPct": opt["totalReturnPct"],
                "portfolioR": round(opt["totalReturnPct"] / dd, 2),
                "drawdownAt100Pct": round(100 / (opt["totalReturnPct"] / dd), 1),
            }
        )
    return out


# --------------------------------------------------------------------------- #
# 5. Обеспечение: LQDT против мёртвого кэша
# --------------------------------------------------------------------------- #
def collateral(R: float, duration_mu: float) -> dict:
    rows = []
    for name, credit in (
        ("LQDT принят как ГО, доходность начисляется", R),
        ("LQDT принят с дисконтом 50%, доходность идёт", R),
        ("деньги как ГО, доходности нет", 0.0),
        ("деньги как ГО, брокер платит 5% на остаток", 5.0),
    ):
        rows.append(
            {
                "variant": name,
                "cashYieldPct": round(credit, 1),
                "returnAt3xDurationPct": round(3 * duration_mu + credit, 1),
                "lossVsBestPp": round(R - credit, 1),
            }
        )
    return {
        "mechanic": (
            "Одна и та же сумма работает дважды: как обеспечение фьючерсной/биржевой "
            "позиции и как пай фонда денежного рынка, приносящий RUONIA. "
            "Это не альфа, а устранение потери 19 п.п. на неработающем обеспечении."
        ),
        "verification": [
            "риск-рейт НКЦ/брокера на LQDT (какой процент стоимости идёт в ГО)",
            "принимает ли брокер паи БПИФ денежного рынка в обеспечение",
            "не блокируется ли вывод/покупка паёв при маржинальной позиции",
            "применимо ли к ИИС (там брокерское обеспечение часто уже)",
        ],
        "rows": rows,
        "status": "НЕ ПРОВЕРЕНО: ключевое допущение всей конструкции",
    }


# --------------------------------------------------------------------------- #
# 6. Налоговый слой
# --------------------------------------------------------------------------- #
def tax_layer(R: float) -> dict:
    rows = []
    for capital in (100_000, 300_000, 400_000):
        contribution = min(IIS3_CAP, capital)
        deduction = min(contribution * TAX, IIS3_MAX_DEDUCTION)
        rows.append(
            {
                "capitalRub": capital,
                "annualContributionRub": int(contribution),
                "deductionPctOfContribution": round(deduction / contribution * 100, 1),
                "deductionRub": int(deduction),
                "deductionPctOfCapital": round(deduction / capital * 100, 1),
                "amortizedOver3yPp": round(deduction / capital / 3 * 100, 1),
            }
        )
    return {
        "iis3": {
            "rules": "взнос до 400 тыс ₽/год, вычет 13% (до 52 тыс ₽), доход внутри счёта не облагается",
            "rows": rows,
            "note": (
                "Вычет — субсидия взносу, а не доходности: он требует нового капитала. "
                "Для счёта 100 тыс ₽ максимальный вычет (52 тыс) возможен только при взносе 400 тыс, "
                "то есть при капитале, вчетверо большем. Обещать «+52% к доходности» нельзя."
            ),
        },
        "ldv": {
            "rule": "3 года владения: освобождение прибыли до 3 млн ₽ × число лет",
            "effectPp": "при доходности 15%/год — ~1,95 п.п./год экономии на НДФЛ",
            "limit": "не работает для активной торговли (нужно держать бумагу, а не торговать ею)",
        },
        "combinedHonest": {
            "annualBoostPp": "3–6 п.п. в год на горизонте 3 лет при регулярных взносах",
            "caveat": "к цели 100% это добавляет единицы процентов, а не десятки",
        },
    }


# --------------------------------------------------------------------------- #
# 7. Новые кандидаты (нет ни в docs/58, ни в docs/59)
# --------------------------------------------------------------------------- #
def new_candidates() -> list[dict]:
    return [
        {
            "id": "N1", "name": "Дюрация ОФЗ / БПИФ облигаций",
            "mechanism": "премия за срочность: купон + переоценка при снижении ставки",
            "counterparty": "Минфин и банки, продающие дюрацию; вы покупаете риск ставки",
            "rEstimate": "3,0 (прокси, один цикл)",
            "capacity": "не ограничена: рынок ОФЗ 10+ трлн ₽",
            "dataNeed": "цены/доходности ОФЗ, индекс RGBI", "verdict": "ПРИОРИТЕТ 1",
        },
        {
            "id": "N2", "name": "Обеспечение в фонде денежного рынка",
            "mechanism": "одна сумма — и ГО, и RUONIA одновременно",
            "counterparty": "брокер/НКЦ принимает паи в обеспечение",
            "rEstimate": "+19 п.п. к любой leveraged-позиции",
            "capacity": "зависит от риск-рейта", "dataNeed": "условия брокера",
            "verdict": "ПРИОРИТЕТ 1 (проверка за 1 день)",
        },
        {
            "id": "N3", "name": "БПИФ против индексного фьючерса",
            "mechanism": "премия/дисконт пая к NAV при одинаковом риске",
            "counterparty": "маркет-мейкер фонда и арбитражёры",
            "rEstimate": "1,5–3,0 (оценка)", "capacity": "десятки млн ₽",
            "dataNeed": "NAV фонда и стакан", "verdict": "исследовать",
        },
        {
            "id": "N4", "name": "Конверсии/реверсалы (паритет пут-колл)",
            "mechanism": "синтетическая позиция из опционов против фьючерса при расхождении",
            "counterparty": "маркет-мейкеры опционов",
            "rEstimate": "высокий, но редко > 0 после спреда", "capacity": "мала",
            "dataNeed": "опционные цепочки, bid/ask", "verdict": "исследовать после N1–N2",
        },
        {
            "id": "N5", "name": "Событийная волатильность (ЦБ, CPI)",
            "mechanism": "премия за риск события: продажа стрэнгла перед заседанием",
            "counterparty": "покупатели страховки перед событием",
            "rEstimate": "1,0–2,0, но хвост", "capacity": "средняя",
            "dataNeed": "опционы, календарь ЦБ", "verdict": "после N1–N2",
        },
        {
            "id": "N6", "name": "Кросс-курсовой треугольник USDRUB/CNYRUB",
            "mechanism": "отклонение CIP между парами",
            "counterparty": "участники двух рынков",
            "rEstimate": "не оценить без офшорных данных", "capacity": "мала",
            "dataNeed": "CNH/офшорные котировки", "verdict": "нет доступа к данным",
        },
        {
            "id": "N7", "name": "Дивидендный carry с фьючерсным хеджем",
            "mechanism": "дивиденд минус ставка финансирования",
            "counterparty": "продавцы дивидендного риска",
            "rEstimate": "0,5–1,2; при ставке 14% часто отрицателен",
            "capacity": "средняя", "dataNeed": "дивидендный календарь",
            "verdict": "только точечно",
        },
        {
            "id": "N8", "name": "Репо с ЦК / свопы",
            "mechanism": "ставка денежного рынка под залог бумаг",
            "counterparty": "НКЦ", "rEstimate": "≈ RUONIA, без плеча",
            "capacity": "не ограничена", "dataNeed": "доступ к репо-секции",
            "verdict": "не для розничного счёта",
        },
        {
            "id": "N9", "name": "ИИС-3 + ЛДВ",
            "mechanism": "налоговая субсидия, а не рынок",
            "counterparty": "государство", "rEstimate": "3–6 п.п./год",
            "capacity": "400 тыс ₽ взноса/год", "dataNeed": "статус счёта",
            "verdict": "делать, но это не альфа",
        },
    ]


# --------------------------------------------------------------------------- #
# 8. Сценарии
# --------------------------------------------------------------------------- #
def scenarios(R: float) -> list[dict]:
    """Три сценария. Просадка — корень из суммы квадратов вкладов (нулевая корреляция)."""
    def build(name, legs, note, prob, breaks):
        mu = R + sum(l["leverage"] * l["mu"] for l in legs)
        dd = (sum((l["leverage"] * l["dd"]) ** 2 for l in legs)) ** 0.5
        return {
            "scenario": name,
            "composition": ", ".join(f'{l["name"]} {l["leverage"]}x' for l in legs),
            "returnPct": round(mu, 1),
            "drawdownPct": round(dd, 1),
            "r": round(mu / dd, 2),
            "drawdownAt100Pct": round(100 / (mu / dd), 1),
            "probability": prob,
            "note": note,
            "whenBreaks": breaks,
        }

    return [
        build(
            "Консервативный",
            [{"name": "carry CNYRUBF", "leverage": 2.0, "mu": 2.1, "dd": 2.4}],
            "кэш целиком в денежном рынке, один проверенный слив",
            "высокая (8/10)",
            "ставка уходит ниже 10% — кэш-нога перестаёт быть двигателем, остаётся 4–6% carry",
        ),
        build(
            "Базовый",
            [
                {"name": "дюрация ОФЗ 5л", "leverage": 2.0, "mu": 22.0, "dd": 12.0},
                {"name": "carry CNYRUBF", "leverage": 3.0, "mu": 2.1, "dd": 2.4},
                {"name": "календарные спреды", "leverage": 2.0, "mu": 3.75, "dd": 2.0},
            ],
            "дюрация с реалистичной просадкой 12% и плечом 2x + два малых слива",
            "средняя (5/10)",
            "разворот ставки +2 п.п. за квартал: −16,8% от дюрации на 2x при сохранении кэша",
        ),
        build(
            "Агрессивный",
            [
                {"name": "дюрация ОФЗ 5л", "leverage": 3.0, "mu": 22.0, "dd": 12.0},
                {"name": "carry CNYRUBF", "leverage": 5.94, "mu": 2.1, "dd": 2.4},
                {"name": "календарные спреды", "leverage": 5.0, "mu": 3.75, "dd": 2.0},
                {"name": "короткая вола", "leverage": 3.0, "mu": 4.0, "dd": 3.0},
            ],
            "все сливы на пределе ёмкости, кэш начисляется на обеспечение",
            "низкая (3/10)",
            "любая ошибка в оценке: непринятый LQDT −19 п.п., разворот ставки −36% на 3x, "
            "хвост волатильности −50% маржи за день",
        ),
    ]


# --------------------------------------------------------------------------- #
def main() -> None:
    rates = load_rates()
    mm = money_market(rates)
    R = mm["annualPct"]

    dur = duration_sleeve(rates)
    base = dur["variants"][0]
    catalog = sleeve_catalog(R, base["annualPct"], base["mddPct"])

    measured = [s for s in catalog if s["id"] in ("CASH", "DUR", "CARRY")]
    measured_plus = [s for s in catalog if s["id"] in ("CASH", "DUR", "CARRY", "CAL", "VOL")]
    with_u8 = [s for s in catalog if s["id"] in ("CASH", "DUR", "CARRY", "CAL", "VOL", "S1")]

    result = {
        "generatedAt": date.today().isoformat(),
        "purpose": "Что ещё можно придумать под 100%: риск-бюджет, дюрация, обеспечение, налоги",
        "moneyMarket": mm,
        "cashFloor": cash_floor_theorem(R),
        "duration": dur,
        "rateScenarios": rate_scenarios(R),
        "sleeveCatalog": catalog,
        "optimizer": {
            "measuredOnly": optimize(measured, R, 0.35),
            "measuredPlusModels": optimize(measured_plus, R, 0.35),
            "withS1": optimize(with_u8, R, 0.35),
            "budgets": [optimize(measured_plus, R, b) for b in (0.20, 0.25, 0.30, 0.35, 0.45)],
        },
        "correlationSensitivity": correlation_sensitivity(
            optimize(measured_plus, R, 0.35)
        ),
        "collateral": collateral(R, base["annualPct"]),
        "taxLayer": tax_layer(R),
        "newCandidates": new_candidates(),
        "scenarios": scenarios(R),
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"written {OUT}")
    print(f"денежный рынок: {R}%/год, кумулятив {mm['cumulativePct']}% за {mm['years']} года")
    print("\nдюрация (прокси):")
    for v in dur["variants"]:
        print(f"  {v['variant']:<34} {v['annualPct']:>6.1f}%/год  MDD {v['mddPct']:>5.1f}%  r={v['returnToMdd']}")
    print("\nфазы:")
    for p in dur["phases"]:
        print(f"  {p['phase']:<45} {p['annualPct']:>7.1f}%/год  MDD {p['mddPct']:>5.1f}%")
    print("\nстресс по ставке:")
    for s in dur["stress"]:
        print(f"  {s['windowDays']:>3}д: {s['worstRateMovePp']:>+5.2f} п.п. → 1x {s['pnlPctAt1x']:>6.1f}%  3x {s['pnlPctAt3x']:>6.1f}%")
    print("\nтребуемое качество сливов sqrt(sum r^2) для 100%:")
    for k, v in result["cashFloor"]["requiredQuality"].items():
        print(f"  {k:<8} {v}")
    print("\nоптимизация при DD-бюджете 35%:")
    for name, opt in result["optimizer"].items():
        if name == "budgets":
            continue
        print(f"  {name}: μ {opt['totalReturnPct']:.1f}%  DD {opt['totalDrawdownPct']:.1f}%  r={opt['portfolioR']}  DD@100={opt['drawdownAt100Pct']}%")
        for a in opt["allocation"]:
            if a["leverage"] > 0:
                print(f"     {a['id']:<6} L={a['leverage']:<6} риск {a['riskContributionPct']:>5.1f}%  вклад {a['returnContributionPct']:>6.1f}%" + (" [лимит]" if a["capacityBinding"] else ""))
    print("\nчувствительность к корреляции (DD-бюджет 35%, измеренное+модели):")
    for c in result["correlationSensitivity"]:
        print(f"  rho={c['pairwiseCorrelation']}: DD {c['effectiveDrawdownPct']:>5.1f}%  r={c['portfolioR']:>5.2f}  DD@100={c['drawdownAt100Pct']}%")
    print("\nсценарии:")
    for s in result["scenarios"]:
        print(f"  {s['scenario']:<16} {s['returnPct']:>6.1f}%  DD {s['drawdownPct']:>5.1f}%  r={s['r']}  P={s['probability']}")


if __name__ == "__main__":
    main()
