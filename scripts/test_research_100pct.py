#!/usr/bin/env python3
"""Регрессионные тесты research-расчётов PR #3; только stdlib."""
from __future__ import annotations

import math
import unittest
from datetime import date

import research_alt_instruments as alt
import research_frontier_100pct as frontier
import research_plan_100pct as plan


class PlanMetricsTest(unittest.TestCase):
    def test_daily_mdd_is_order_independent_and_ratio_is_annual(self) -> None:
        trades = [
            {"ticker": "A", "entry": "2025-01-01", "exit": "2025-01-02", "pnl": 1000.0},
            {"ticker": "B", "entry": "2025-01-01", "exit": "2025-01-02", "pnl": -35000.0},
            {"ticker": "A", "entry": "2025-01-02", "exit": "2025-01-03", "pnl": -1000.0},
        ]
        first = plan.stats(trades)
        second = plan.stats(list(reversed(trades)))
        self.assertEqual(first["mddRub"], 35000.0)
        self.assertEqual(second["mddRub"], first["mddRub"])
        self.assertEqual(first["mddMethod"], "daily-closed-trades-by-exit-date")
        expected = (first["netRub"] / first["years"]) / first["mddRub"]
        self.assertAlmostEqual(first["annualReturnToMdd"], expected, places=2)

    def test_capacity_uses_after_tax_target_and_contract_count(self) -> None:
        block = plan.capacity_block()
        row = next(
            row for row in block["ceiling"]
            if row["depositRub"] == 50000 and row["maxContracts"] == 3
        )
        self.assertEqual(row["targetPreTaxProfitRub"], 57471.26)
        self.assertEqual(row["requiredCaptureOfIdealPctPreTax"], 44.3)
        requirement = next(
            item for item in block["requirements"]
            if item["depositRub"] == 50000 and item["maxContracts"] == 3
        )
        self.assertEqual(
            requirement["requiredPreTaxRubPerContractTradeByTradesPerYear"]["500"],
            38.31,
        )

    def test_scenario_converts_arithmetic_volatility_to_lognormal_sigma(self) -> None:
        result = plan.scenario("base", 50.0, 30.0)
        expected_sigma = math.sqrt(math.log(1 + (0.30 / 1.50) ** 2))
        self.assertAlmostEqual(result["assumedLogVolPct"] / 100, expected_sigma, places=4)
        self.assertIn("no path, margin or liquidation", result["probabilityScope"])


class AlternativeInstrumentsTest(unittest.TestCase):
    def test_leverage_law_does_not_invent_leverage_from_ratio(self) -> None:
        row = alt.leverage_law()[0]
        self.assertNotIn("leverageFor100Pct", row)
        self.assertEqual(row["drawdownAt100PctNet"], 574.7)

    def test_capacity_uses_whole_contracts_and_conservative_cny_go(self) -> None:
        row = next(
            row for row in alt.instrument_capacity()
            if row["ticker"] == "CNYRUBF" and row["capitalRub"] == 50000
        )
        self.assertEqual(row["goRub"], 2700.0)
        self.assertEqual(row["maxContractsAtStress"], 7)
        self.assertEqual(row["maxNotionalLeverage"], 1.77)

    def test_short_vol_model_does_not_publish_arbitrary_vrp_expectation(self) -> None:
        self.assertNotIn("varianceRiskPremium", alt.short_vol_model())


class DurationProxyTest(unittest.TestCase):
    def test_carry_uses_elapsed_calendar_days(self) -> None:
        rates = [
            (date(2026, 1, 1), 10.0, 10.0),
            (date(2026, 1, 4), 10.0, 10.0),
        ]
        curve, annual, mdd = frontier.bond_curve(rates, 0.0, 0.0)
        expected_growth = 1 + 0.10 * 3 / 365
        self.assertAlmostEqual(curve[-1][1], expected_growth, places=6)
        expected_annual = (expected_growth ** (365.25 / 3) - 1) * 100
        self.assertAlmostEqual(annual, expected_annual, places=6)
        self.assertEqual(mdd, 0.0)

    def test_stress_uses_calendar_window_and_selects_rate_increase(self) -> None:
        rates = [
            (date(2026, 1, 1), 10.0, None),
            (date(2026, 1, 5), 5.0, None),
            (date(2026, 1, 11), 11.0, None),
            (date(2026, 1, 20), 12.0, None),
        ]
        row = frontier.rate_move_stress(rates, 4.2)[0]
        self.assertEqual(row["windowCalendarDays"], 10)
        self.assertGreater(row["maxRateIncreasePp"], 0)
        self.assertLessEqual(row["pnlPctAt1x"], 0)

    def test_no_mdd_optimizer_or_target_probability_is_returned(self) -> None:
        self.assertFalse(hasattr(frontier, "optimize"))
        self.assertFalse(hasattr(frontier, "scenarios"))


if __name__ == "__main__":
    unittest.main()
