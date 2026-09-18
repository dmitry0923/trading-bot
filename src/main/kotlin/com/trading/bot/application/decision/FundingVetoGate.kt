package com.trading.bot.application.decision

import com.trading.bot.application.funding.FundingSnapshotService
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.PositionDirection
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Funding Veto Gate (research, дефолт off; docs/16, AGENTS.md «Актуальные параметры»).
 *
 * Вето на вход в фьючерс, когда per-clearing funding по стороне сделки слишком
 * «дорогой»: удержание позиции через клиринг съедает edge ещё до выхода.
 * Правило по знаку ставки:
 *   - LONG блокируется при funding > +[longThresholdRub] (лонг платит);
 *   - SHORT блокируется при funding < −[shortThresholdRub] (шорт платит).
 *
 * Источник: свежий MOEX-снапшот ([FundingSnapshotService.latestForVeto]) —
 * per-clearing SWAPRATE в руб/контракт/клиринг (LIVE) или CONFIG-ставка (SIM).
 * Недоступность/устаревание снапшота при включённом фильтре — BLOCK
 * (fail-closed: неизвестная ставка ≠ ставка в пределах порога).
 *
 * Гейт выключен, пока `trading.funding-veto-enabled=false`: live-поведение не
 * меняется, miss = pass. Включение — research-инструмент (наблюдение/валидация
 * в SIM): исторический ряд SWAPRATE в БД отсутствует, поэтому WFA-калибровка
 * порогов ограничена (см. AGENTS.md, открытый P1) — пороги задаются исследователем.
 */
@Component
class FundingVetoGate(
    private val fundingSnapshotService: FundingSnapshotService,
    private val tradingConfig: TradingConfig,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Проверка входа по funding-ставке.
     *
     * @return [VetoResult.Blocked] при превышении порога по стороне (или BLOCK
     *   на неизвестной ставке при fail-closed), иначе [VetoResult.Pass].
     */
    fun check(
        ticker: String,
        direction: PositionDirection,
    ): VetoResult {
        if (!tradingConfig.fundingVetoEnabled) return VetoResult.Pass

        val snapshot = fundingSnapshotService.latestForVeto(ticker)
        if (snapshot == null) {
            if (tradingConfig.fundingVetoBlockOnUnknown) {
                logger.warn { "Funding veto BLOCKED $ticker $direction: funding UNKNOWN (fail-closed)" }
                return VetoResult.Blocked(null)
            }
            return VetoResult.Pass
        }

        val fundingRub = snapshot
        val longThreshold = BigDecimal(tradingConfig.fundingVetoLongThresholdRub)
        val shortThreshold = BigDecimal(tradingConfig.fundingVetoShortThresholdRub)
        return when {
            direction == PositionDirection.LONG && fundingRub > longThreshold -> {
                logger.warn {
                    "Funding veto BLOCKED $ticker LONG: funding=$fundingRub " +
                        "> +$longThreshold ₽/контракт/клиринг (лонг платит)"
                }
                VetoResult.Blocked(fundingRub)
            }

            direction == PositionDirection.SHORT &&
                fundingRub < shortThreshold.negate() -> {
                logger.warn {
                    "Funding veto BLOCKED $ticker SHORT: funding=$fundingRub " +
                        "< -$shortThreshold ₽/контракт/клиринг (шорт платит)"
                }
                VetoResult.Blocked(fundingRub)
            }

            else -> {
                logger.debug {
                    "Funding veto PASS $ticker $direction: funding=$fundingRub " +
                        "(long≤+$longThreshold, short≥-$shortThreshold)"
                }
                VetoResult.Pass
            }
        }
    }

    sealed interface VetoResult {
        data object Pass : VetoResult

        /** @property fundingRub ставка (руб/контракт/клиринг), null = FLAG_OFF/unresolved. */
        data class Blocked(
            val fundingRub: BigDecimal?,
        ) : VetoResult
    }
}
