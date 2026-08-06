package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.model.DrawdownStatus
import com.trading.bot.model.Position
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Multi-Tier Drawdown Protection — защита от медленных просадок на длительной дистанции.
 *
 * Все лимиты — в **% от AUM** (в отличие от жёсткого `risk.max-daily-loss-rub`, который
 * не масштабируется при росте/падении капитала):
 *
 *  1. **Дневной лимит** — суммарный реализованный P&L закрытых сегодня сделок
 *     не может опуститься ниже `-maxDailyLossPercent%` AUM;
 *  2. **Скользящий лимит 7 дней** — защита от серии мелких убыточных сделок,
 *     которые не пробивают дневной лимит, но накапливают просадку за неделю;
 *  3. **Скользящий лимит 30 дней** — «смерть от тысячи порезов» на горизонте месяца;
 *  4. **Consecutive Losses Limiter** — при [RiskConfig.maxConsecutiveLosses] убыточных
 *     сделок подряд LLM-агент переводится в Shadow/Read-only режим (см. `shadowModeActive`
 *     в [DrawdownStatus]) для переобучения/калибровки: минимум [RiskConfig.shadowModeCooldownHours],
 *     снимается только после прибыльной сделки (сброс серии).
 *
 * AUM = стартовый депозит (`risk.max-position-rub`) + реализованный P&L всех закрытых
 * сделок (акции + фьючерсы). Кэшируется в памяти и обновляется на каждое закрытие
 * позиции и каждый стратегический цикл — горячие проверки входа читают кэш без БД.
 */
@Service
class DrawdownProtectionService(
    private val riskConfig: RiskConfig,
    private val positionRepo: PositionRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cachedStatus: DrawdownStatus? = null

    @Volatile
    private var shadowModeUntil: Instant? = null

    /**
     * Полный пересчёт Multi-Tier статуса из фактических сделок в БД.
     * Вызывается один раз за стратегический цикл и при закрытии позиции.
     *
     * @return текущий [DrawdownStatus]
     */
    suspend fun computeStatus(): DrawdownStatus {
        val now = LocalDateTime.now()
        val closed = positionRepo.findClosed()
        val aum = currentAum(closed)

        val todayStart = now.toLocalDate().atStartOfDay()
        val dailyPnl = sumPnl(closed.filter { isClosedOnOrAfter(it, todayStart) })
        val rolling7d = sumPnl(closed.filter { isClosedOnOrAfter(it, now.minusDays(7)) })
        val rolling30d = sumPnl(closed.filter { isClosedOnOrAfter(it, now.minusDays(30)) })

        val dailyLimit = effectiveDailyLossLimitRub(aum)
        val rolling7dLimit = percentOfAum(aum, riskConfig.maxRollingLossPercent7d)
        val rolling30dLimit = percentOfAum(aum, riskConfig.maxRollingLossPercent30d)

        val dailyBreached = dailyPnl <= dailyLimit.negate()
        val rolling7dBreached = rolling7d <= rolling7dLimit.negate()
        val rolling30dBreached = rolling30d <= rolling30dLimit.negate()

        val consecutive = consecutiveLosses(closed)
        val shadowUntil = refreshShadowMode(consecutive)
        val shadowActive = shadowUntil != null && Instant.now().isBefore(shadowUntil)

        val reasons =
            buildList {
                if (dailyBreached) add("DAILY_LOSS: $dailyPnl ₽ <= -$dailyLimit ₽")
                if (rolling7dBreached) add("ROLLING_7D_LOSS: $rolling7d ₽ <= -$rolling7dLimit ₽")
                if (rolling30dBreached) add("ROLLING_30D_LOSS: $rolling30d ₽ <= -$rolling30dLimit ₽")
                if (shadowActive) add("SHADOW_MODE: $consecutive consecutive losses")
            }

        val status =
            DrawdownStatus(
                aum = aum,
                dailyPnlRub = dailyPnl,
                dailyLimitRub = dailyLimit,
                dailyLimitBreached = dailyBreached,
                rolling7dPnlRub = rolling7d,
                rolling7dLimitRub = rolling7dLimit,
                rolling7dBreached = rolling7dBreached,
                rolling30dPnlRub = rolling30d,
                rolling30dLimitRub = rolling30dLimit,
                rolling30dBreached = rolling30dBreached,
                consecutiveLosses = consecutive,
                maxConsecutiveLosses = riskConfig.maxConsecutiveLosses,
                shadowModeActive = shadowActive,
                shadowModeUntil = shadowUntil,
                reasons = reasons,
                timestamp = Instant.now(),
            )
        cachedStatus = status
        recordMetrics(status)
        logger.info {
            "Drawdown status: aum=$aum daily=${percentOf(status.dailyPnlRub, aum)}% " +
                "7d=${percentOf(status.rolling7dPnlRub, aum)}% 30d=${percentOf(status.rolling30dPnlRub, aum)}% " +
                "losses=$consecutive shadow=$shadowActive reasons=$reasons"
        }
        return status
    }

    /**
     * Текущий статус из кэша (без БД) для горячих проверок входа.
     * Если кэш ещё не заполнен (старт до первого цикла) — считает консервативно-нейтрально
     * от стартового депозита.
     */
    fun cachedOrNeutral(): DrawdownStatus {
        cachedStatus?.let { return it }
        val aum = riskConfig.maxPositionRub
        return DrawdownStatus(
            aum = aum,
            dailyPnlRub = BigDecimal.ZERO,
            dailyLimitRub = effectiveDailyLossLimitRub(aum),
            dailyLimitBreached = false,
            rolling7dPnlRub = BigDecimal.ZERO,
            rolling7dLimitRub = percentOfAum(aum, riskConfig.maxRollingLossPercent7d),
            rolling7dBreached = false,
            rolling30dPnlRub = BigDecimal.ZERO,
            rolling30dLimitRub = percentOfAum(aum, riskConfig.maxRollingLossPercent30d),
            rolling30dBreached = false,
            consecutiveLosses = 0,
            maxConsecutiveLosses = riskConfig.maxConsecutiveLosses,
            shadowModeActive = isShadowModeActive(),
            shadowModeUntil = shadowModeUntil,
            reasons = emptyList(),
            timestamp = Instant.now(),
        )
    }

    /**
     * Заблокированы ли новые входы (кэш). Покрывает все tier-лимиты и Shadow/Read-only.
     */
    fun isEntryBlocked(): Boolean = cachedOrNeutral().blocking()

    /**
     * Причина блокировки входа (для логов/отказов). Пустая строка — вход разрешён.
     */
    fun entryBlockReason(): String = cachedOrNeutral().reasons.joinToString("; ")

    /**
     * Активен ли Shadow/Read-only режим LLM-агента (кэш, без БД).
     */
    fun isShadowModeActive(): Boolean {
        val until = shadowModeUntil ?: return false
        return Instant.now().isBefore(until)
    }

    /**
     * Эффективный дневной лимит убытка в рублях (кэш AUM, без БД).
     *
     * При росте капитала доминирует процентная компонента (лимит масштабируется),
     * при падении — рублёвый «пол» [RiskConfig.maxDailyLossRub] не даёт лимиту схлопнуться до нуля.
     */
    fun effectiveDailyLossLimitRub(): BigDecimal {
        val aum = cachedStatus?.aum ?: riskConfig.maxPositionRub
        return effectiveDailyLossLimitRub(aum)
    }

    /**
     * Серия убыточных сделок подряд по факту закрытий.
     */
    fun consecutiveLosses(closed: List<Position>): Int =
        closed
            .filter { it.pnl != null }
            .sortedByDescending { it.closedAt ?: LocalDateTime.MIN }
            .takeWhile { it.pnl!! < BigDecimal.ZERO }
            .count()

    private fun currentAum(closed: List<Position>): BigDecimal {
        val realized = sumPnl(closed)
        return riskConfig.maxPositionRub.add(realized).coerceAtLeast(BigDecimal.ZERO)
    }

    private fun sumPnl(positions: List<Position>): BigDecimal = positions.sumOf { it.pnl ?: BigDecimal.ZERO }

    private fun isClosedOnOrAfter(
        position: Position,
        from: LocalDateTime,
    ): Boolean {
        val closedAt = position.closedAt ?: return false
        return !closedAt.isBefore(from)
    }

    private fun percentOfAum(
        aum: BigDecimal,
        percent: Double,
    ): BigDecimal =
        aum
            .multiply(BigDecimal(percent.toString()))
            .divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)

    private fun percentOf(
        value: BigDecimal,
        aum: BigDecimal,
    ): Double {
        if (aum <= BigDecimal.ZERO) return 0.0
        return value
            .multiply(BigDecimal("100"))
            .divide(aum, 4, RoundingMode.HALF_UP)
            .toDouble()
    }

    private fun effectiveDailyLossLimitRub(aum: BigDecimal): BigDecimal {
        val percentBased =
            if (riskConfig.maxDailyLossPercent > 0) {
                percentOfAum(aum, riskConfig.maxDailyLossPercent)
            } else {
                BigDecimal.ZERO
            }
        return percentBased.max(riskConfig.maxDailyLossRub)
    }

    /**
     * Обновляет Shadow/Read-only состояние по серии убытков:
     * - серия >= лимита → shadow минимум на [RiskConfig.shadowModeCooldownHours];
     * - серия держится дольше кд → продлеваем (агент не торгует, пока продолжает сыпаться);
     * - серия сброшена прибыльной сделкой → shadow снимается.
     */
    private fun refreshShadowMode(consecutive: Int): Instant? {
        if (!riskConfig.shadowModeEnabled || consecutive < riskConfig.maxConsecutiveLosses) {
            shadowModeUntil = null
            return null
        }
        val now = Instant.now()
        val until = shadowModeUntil ?: Instant.EPOCH
        if (until.isBefore(now)) {
            val extended = now.plus(Duration.ofHours(riskConfig.shadowModeCooldownHours))
            shadowModeUntil = extended
            logger.warn {
                "SHADOW MODE activated for LLM agent: $consecutive consecutive losses >= ${riskConfig.maxConsecutiveLosses}; " +
                    "entries blocked until $extended (retraining/calibration)"
            }
            meterRegistry.counter("drawdown.shadow.activated").increment()
            return extended
        }
        return until
    }

    private fun recordMetrics(status: DrawdownStatus) {
        meterRegistry.gauge("drawdown.aum", status.aum.toDouble())
        meterRegistry.gauge("drawdown.daily.pnl", Tags.of("unit", "rub"), status.dailyPnlRub.toDouble())
        meterRegistry.gauge("drawdown.daily.percent", percentOf(status.dailyPnlRub, status.aum))
        meterRegistry.gauge("drawdown.rolling7d.percent", percentOf(status.rolling7dPnlRub, status.aum))
        meterRegistry.gauge("drawdown.rolling30d.percent", percentOf(status.rolling30dPnlRub, status.aum))
        meterRegistry.gauge("drawdown.consecutive.losses", status.consecutiveLosses.toDouble())
        meterRegistry.gauge("drawdown.shadow.mode", if (status.shadowModeActive) 1.0 else 0.0)
        meterRegistry.gauge("drawdown.blocked", if (status.blocking()) 1.0 else 0.0)
    }

    /**
     * При закрытии позиции пересчитываем статус в фоне: AUM, лимиты и серия убытков
     * должны обновиться немедленно (без ожидания следующего стратегического цикла).
     */
    @EventListener
    fun onPositionClosed(event: PositionClosedEvent) {
        scope.launch {
            try {
                val status = computeStatus()
                logger.info {
                    "Drawdown status refreshed after close ${event.ticker}: pnl=${event.pnl} reason=${event.reason} -> " +
                        "blocking=${status.blocking()} shadow=${status.shadowModeActive}"
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to refresh drawdown status after position close" }
            }
        }
    }
}
