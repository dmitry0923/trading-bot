package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.repository.DailyRiskSnapshotRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Единый потокобезопасный источник дневного P&L для акций и фьючерсов.
 *
 * Раньше два risk-сервиса независимо кэшировали и перезаписывали одну строку
 * `daily_risk_snapshot`, из-за чего параллельные закрытия теряли часть P&L.
 * Этот сервис владеет состоянием один раз и атомарно персистит общий итог.
 */
@Service
class DailyRiskService(
    private val riskConfig: RiskConfig,
    private val repository: DailyRiskSnapshotRepository,
) {
    private val logger = KotlinLogging.logger {}
    private val moscowZone = ZoneId.of("Europe/Moscow")

    private var tradeDate: LocalDate = LocalDate.MIN
    private var dailyPnl: BigDecimal = BigDecimal.ZERO
    private var maxDrawdown: BigDecimal = BigDecimal.ZERO
    private var limitReached: Boolean = false

    init {
        reset()
    }

    @Synchronized
    fun addPnl(pnl: BigDecimal) {
        resetIfNewDay()
        dailyPnl = dailyPnl.add(pnl)
        maxDrawdown = maxDrawdown.min(dailyPnl)
        limitReached = limitReached || dailyPnl <= riskConfig.maxDailyLossRub.negate()
        persist()
        if (limitReached) {
            logger.error { "DAILY LOSS LIMIT reached: dailyPnl=$dailyPnl <= -${riskConfig.maxDailyLossRub}" }
        }
    }

    @Synchronized
    fun isLimitReached(): Boolean {
        resetIfNewDay()
        if (!limitReached && dailyPnl <= riskConfig.maxDailyLossRub.negate()) {
            limitReached = true
            persist()
        }
        return limitReached
    }

    @Synchronized
    fun currentPnl(): BigDecimal {
        resetIfNewDay()
        return dailyPnl
    }

    @Synchronized
    fun reset() {
        tradeDate = LocalDate.now(moscowZone)
        val snapshot =
            try {
                repository.findByDate(tradeDate)
            } catch (e: Exception) {
                logger.warn(e) { "Daily risk snapshot load failed" }
                null
            }
        dailyPnl = snapshot?.dailyPnl ?: BigDecimal.ZERO
        maxDrawdown = snapshot?.maxDrawdownToday ?: BigDecimal.ZERO
        limitReached = snapshot?.limitReached ?: (dailyPnl <= riskConfig.maxDailyLossRub.negate())
        logger.info { "Daily risk state for $tradeDate: dailyPnl=$dailyPnl limitReached=$limitReached" }
    }

    private fun resetIfNewDay() {
        if (tradeDate != LocalDate.now(moscowZone)) {
            reset()
        }
    }

    private fun persist() {
        try {
            repository.upsert(tradeDate, dailyPnl, limitReached, maxDrawdown)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist daily risk snapshot" }
        }
    }
}
