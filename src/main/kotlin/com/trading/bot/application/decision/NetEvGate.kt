package com.trading.bot.application.decision

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.dto.MarketSnapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * NET EV Gate (P0 audit): блокирует сделки с отрицательным или слишком низким
 * математическим ожиданием после учёта реальной стоимости исполнения.
 *
 * Формула:
 *   netEV = expectedNetPerLot − executionCostPerLot
 *
 * Где:
 *   expectedNetPerLot = Wilson(winRate) × avgWin − (1−Wilson(winRate)) × avgLoss
 *     (комиссия уже вычтена из avgWin/avgLoss через PnlCalculator)
 *   executionCostPerLot = halfSpread × lotSize × adverseSelectionMultiplier
 *     (текущий спред из market snapshot × 2 для ROUND TRIP × множитель adverse selection)
 *
 * Если netEV < minNetEvThresholdRub — вход блокируется.
 * При недостатке статистики (менее kellyMinTrades сделок) —
 *   если [RiskConfig.netEvBlockOnUnknown] = true → BLOCK (EV UNKNOWN ≠ EV > 0),
 *   иначе → PASS.
 */
@Component
class NetEvGate(
    private val instrumentsConfig: InstrumentsConfig,
    private val riskConfig: RiskConfig,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Проверка NET EV для допуска сделки.
     *
     * @param expectedNet чистое мат. ожидание на лот (из [AdaptiveRiskService.expectedNetProfitPerLot]),
     *   null = недостаточно статистики → gate PASS.
     */
    fun check(
        ticker: String,
        expectedNet: BigDecimal?,
        snapshot: MarketSnapshot?,
    ): GateResult {
        if (!riskConfig.netEvGateEnabled) return GateResult.Pass

        if (expectedNet == null) {
            if (riskConfig.netEvBlockOnUnknown) {
                logger.warn { "NET EV gate BLOCKED $ticker: insufficient historical data (EV UNKNOWN)" }
                return GateResult.Blocked(
                    netEV = null,
                    expectedNet = null,
                    executionCost = null,
                )
            }
            logger.debug { "NET EV gate PASS (insufficient data, netEvBlockOnUnknown=false) for $ticker" }
            return GateResult.Pass
        }

        val spec = instrumentsConfig.find(ticker)
        val lotSize = spec?.lotSize?.toLong() ?: 1L

        // Current spread cost for one round trip (entry + exit).
        // halfSpread × lotSize = cost of crossing the spread once.
        // × 2 for round trip, then × adverseSelectionMultiplier for slippage.
        val halfSpread = currentHalfSpread(snapshot, spec?.priceStep)
        val executionCostPerLot = halfSpread
            .multiply(BigDecimal(lotSize))
            .multiply(BigDecimal("2")) // round trip
            .multiply(riskConfig.netEvAdverseSelectionMultiplier)
            .setScale(6, RoundingMode.HALF_UP)

        val netEV = expectedNet.subtract(executionCostPerLot)

        return if (netEV < riskConfig.minNetEvThresholdRub) {
            logger.warn {
                "NET EV gate BLOCKED $ticker: netEV=$netEV " +
                    "(expectedNet=$expectedNet, executionCost=$executionCostPerLot, " +
                    "threshold=${riskConfig.minNetEvThresholdRub})"
            }
            GateResult.Blocked(
                netEV = netEV,
                expectedNet = expectedNet,
                executionCost = executionCostPerLot,
            )
        } else {
            logger.debug {
                "NET EV gate PASS $ticker: netEV=$netEV " +
                    "(expectedNet=$expectedNet, executionCost=$executionCostPerLot)"
            }
            GateResult.Pass
        }
    }

    private fun currentHalfSpread(
        snapshot: MarketSnapshot?,
        priceStep: BigDecimal?,
    ): BigDecimal {
        val bid = snapshot?.bid
        val ask = snapshot?.ask
        if (bid != null && ask != null && ask > bid) {
            return ask.subtract(bid).divide(BigDecimal("2"), 6, RoundingMode.HALF_UP)
        }
        // Fallback: estimate from priceStep (spread ≈ 1 tick)
        return priceStep ?: BigDecimal("0.01")
    }

    sealed class GateResult {
        data object Pass : GateResult()

        data class Blocked(
            val netEV: BigDecimal?,
            val expectedNet: BigDecimal?,
            val executionCost: BigDecimal?,
        ) : GateResult()
    }
}
