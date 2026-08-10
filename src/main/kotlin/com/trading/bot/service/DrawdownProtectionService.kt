package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.DailyRiskGuard
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.DrawdownStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.DailyRiskSnapshotRepository
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Multi-Tier Drawdown Protection — защита от медленных просадок на длительной дистанции.
 *
 * Все лимиты — в **% от AUM** (в отличие от жёсткого `risk.max-daily-loss-rub`, который
 * не масштабируется при росте/падении капитала):
 *
 *  1. **Дневной лимит** — P&L за сегодня (реализованный по закрытым сделкам +
 *     нереализованный mark-to-market по открытым позициям) не может опуститься ниже
 *     `-maxDailyLossPercent%` AUM;
 *  2. **Скользящий лимит 7 дней** — защита от серии мелких убыточных сделок,
 *     которые не пробивают дневной лимит, но накапливают просадку за неделю;
 *  3. **Скользящий лимит 30 дней** — «смерть от тысячи порезов» на горизонте месяца;
 *  4. **Consecutive Losses Limiter** — при [RiskConfig.maxConsecutiveLosses] убыточных
 *     сделок подряд LLM-агент переводится в Shadow/Read-only режим (см. `shadowModeActive`
 *     в [DrawdownStatus]) для переобучения/калибровки: минимум [RiskConfig.shadowModeCooldownHours],
 *     снимается только после прибыльной сделки (сброс серии).
 *
 * AUM = актуальный баланс портфеля из Alor ([AumProvider], кэшируется на 60с) +
 * реализованный P&L всех закрытых сделок + **нереализованный P&L открытых позиций**
 * (фьючерсы — по вариационной марже, акции — по текущей цене). Кэшируется в памяти
 * и обновляется на каждое закрытие позиции и каждый стратегический цикл — горячие
 * проверки входа читают кэш без БД.
 *
 * Единый источник истины дневного P&L: синхронный аккумулятор [updateDailyPnl] кормится
 * путями закрытия акций (RiskManagementService) и фьючерсов (DailyLossCircuitBreaker),
 * персистится в daily_risk_snapshot и реконсилится полным пересчётом из БД в [computeStatus].
 */
@Service
class DrawdownProtectionService(
    private val riskConfig: RiskConfig,
    private val positionRepo: PositionRepository,
    private val dailyRiskSnapshotRepo: DailyRiskSnapshotRepository,
    private val instrumentsConfig: InstrumentsConfig,
    private val meterRegistry: MeterRegistry,
    private val aumProvider: AumProvider,
) : DailyRiskGuard {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val moscowZone = ZoneId.of("Europe/Moscow")

    @Volatile
    private var cachedStatus: DrawdownStatus? = null

    @Volatile
    private var shadowModeUntil: Instant? = null

    // Синхронный дневной аккумулятор — единственная точка учёта дневного P&L.
    @Volatile
    private var todayPnl: BigDecimal = BigDecimal.ZERO

    @Volatile
    private var todayDailyLossReached: Boolean = false

    private var lastTradingDate: LocalDate = LocalDate.MIN

    /**
     * Полный пересчёт Multi-Tier статуса из фактических сделок в БД.
     * Вызывается один раз за стратегический цикл и при закрытии позиции.
     *
     * @return текущий [DrawdownStatus]
     */
    suspend fun computeStatus(): DrawdownStatus {
        resetDailyStateIfNewDay()
        aumProvider.currentAum() // обновление баланса из Alor перед расчётом лимитов
        val now = LocalDateTime.now()
        val todayStart = now.toLocalDate().atStartOfDay()
        // Оконные запросы вместо полного сканирования всех закрытых позиций.
        val closedSince30d = positionRepo.findClosedSince(now.minusDays(30))
        val closedToday = positionRepo.findClosedSince(todayStart)
        val open = positionRepo.findByStatus(PositionStatus.OPEN)
        val aggregates = positionRepo.findClosedAggregates()
        val aum = currentAum(aggregates.totalRealized, open)
        val (peakAum, drawdownPercent) = peakAumAndDrawdown(aggregates)

        val realizedToday = sumPnl(closedToday)
        val dailyUnrealized = open.filter { !it.openedAt.isBefore(todayStart) }.sumOf { unrealizedPnl(it) }
        val dailyPnl = realizedToday.add(dailyUnrealized)

        // Реконсиляция синхронного аккумулятора с фактами из БД (перезапись, не сложение).
        todayPnl = dailyPnl
        todayDailyLossReached = dailyPnl <= effectiveDailyLossLimitRub(aum).negate()
        persistDailyState()

        val rolling7d = sumPnl(closedSince30d.filter { isClosedOnOrAfter(it, now.minusDays(7)) })
        val rolling30d = sumPnl(closedSince30d)

        val dailyLimit = effectiveDailyLossLimitRub(aum)
        val rolling7dLimit = percentOfAum(aum, riskConfig.maxRollingLossPercent7d)
        val rolling30dLimit = percentOfAum(aum, riskConfig.maxRollingLossPercent30d)

        val dailyBreached = todayDailyLossReached
        val rolling7dBreached = rolling7d <= rolling7dLimit.negate()
        val rolling30dBreached = rolling30d <= rolling30dLimit.negate()

        val consecutive = consecutiveLosses(closedSince30d)
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
                peakAum = peakAum,
                drawdownPercent = drawdownPercent,
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
            "Drawdown status: aum=$aum peak=$peakAum dd=$drawdownPercent% " +
                "daily=${percentOf(status.dailyPnlRub, aum)}% " +
                "7d=${percentOf(status.rolling7dPnlRub, aum)}% 30d=${percentOf(status.rolling30dPnlRub, aum)}% " +
                "losses=$consecutive shadow=$shadowActive reasons=$reasons"
        }
        return status
    }

    /**
     * Текущий статус из кэша (без БД) для горячих проверок входа.
     * Если кэш ещё не заполнен (старт до первого цикла) — считает консервативно-нейтрально
     * от стартового депозита и синхронного дневного аккумулятора.
     */
    override fun cachedOrNeutral(): DrawdownStatus {
        cachedStatus?.let { return it }
        val aum = aumProvider.latestAum()
        return DrawdownStatus(
            aum = aum,
            peakAum = aum,
            drawdownPercent = 0.0,
            dailyPnlRub = todayPnl,
            dailyLimitRub = effectiveDailyLossLimitRub(aum),
            dailyLimitBreached = todayDailyLossReached,
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
     * Синхронный учёт P&L закрытой сделки. Единственный аккумулятор дневного P&L:
     * вызывается из RiskManagementService (акции) и DailyLossCircuitBreaker (фьючерсы).
     * Персистит состояние в daily_risk_snapshot (восстановление после рестарта).
     *
     * Метод сериализован ([Synchronized]): конкурирующие вызовы из разных корутин
     * не должны терять обновления (`todayPnl = todayPnl + pnl` — read-modify-write).
     */
    @Synchronized
    override fun updateDailyPnl(pnl: BigDecimal) {
        resetDailyStateIfNewDay()
        todayPnl = todayPnl.add(pnl)
        val aum = cachedStatus?.aum ?: aumProvider.latestAum()
        val dailyLimit = effectiveDailyLossLimitRub(aum)
        if (todayPnl <= dailyLimit.negate()) {
            todayDailyLossReached = true
            logger.error { "DAILY LOSS LIMIT reached: dailyPnL=$todayPnl <= -$dailyLimit (${riskConfig.maxDailyLossPercent}% of AUM)" }
        }
        persistDailyState()
        meterRegistry.gauge("risk.daily.pnl", todayPnl.toDouble())
        meterRegistry.gauge("risk.daily.limit.reached", if (todayDailyLossReached) 1.0 else 0.0)
        // Синхронное обновление кэша — входы блокируются немедленно, без ожидания цикла.
        cachedStatus?.let { s ->
            val updated = s.copy(dailyPnlRub = todayPnl, dailyLimitBreached = todayDailyLossReached)
            cachedStatus = updated
            recordMetrics(updated)
        }
    }

    /**
     * Достигнут ли дневной лимит убытка (кэш, без БД).
     */
    override fun isDailyLossLimitReached(): Boolean = cachedOrNeutral().dailyLimitBreached

    /**
     * Текущий дневной P&L (кэш, без БД).
     */
    override fun getDailyPnl(): BigDecimal = cachedOrNeutral().dailyPnlRub

    /**
     * Заблокированы ли новые входы (кэш). Покрывает все tier-лимиты и Shadow/Read-only.
     */
    override fun isEntryBlocked(): Boolean = cachedOrNeutral().blocking()

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
     * При включённом процентном лимите (`maxDailyLossPercent > 0`) используется ТОЛЬКО
     * `% от AUM` — лимит масштабируется при росте и падении капитала без рублёвого
     * ослабления. Рублёвое значение [RiskConfig.maxDailyLossRub] — только fallback,
     * если процентный лимит отключён (<= 0).
     */
    fun effectiveDailyLossLimitRub(): BigDecimal {
        val aum = cachedStatus?.aum ?: aumProvider.latestAum()
        return effectiveDailyLossLimitRub(aum)
    }

    /**
     * Серия убыточных сделок подряд по факту закрытий.
     */
    fun consecutiveLosses(closed: List<Position>): Int =
        closed
            .sortedByDescending { it.closedAt ?: LocalDateTime.MIN }
            .takeWhile { it.pnl?.compareTo(BigDecimal.ZERO) == -1 }
            .count()

    private fun currentAum(
        totalRealized: BigDecimal,
        open: List<Position>,
    ): BigDecimal {
        val unrealized = unrealizedPnl(open)
        return aumProvider
            .latestAum()
            .add(totalRealized)
            .add(unrealized)
            .coerceAtLeast(BigDecimal.ZERO)
    }

    /**
     * Нереализованный P&L открытых позиций.
     * - Фьючерсы: вариационная маржа (обновляется на каждый тик); fallback — расчёт
     *   по [Position.currentPrice] с pointValue инструмента.
     * - Акции: (currentPrice - entryPrice) * qty с учётом направления.
     * Без актуальной цены вклад позиции = 0.
     */
    private fun unrealizedPnl(open: List<Position>): BigDecimal = open.sumOf { unrealizedPnl(it) }

    private fun unrealizedPnl(pos: Position): BigDecimal {
        if (pos.status != PositionStatus.OPEN) return BigDecimal.ZERO
        if (pos.instrumentType == InstrumentType.FUTURES) {
            if (pos.variationMargin.compareTo(BigDecimal.ZERO) != 0) return pos.variationMargin
        }
        val current = pos.currentPrice ?: return BigDecimal.ZERO
        if (current <= BigDecimal.ZERO) return BigDecimal.ZERO
        val qty = BigDecimal(pos.quantity)
        val raw =
            when (pos.direction) {
                PositionDirection.LONG -> current.subtract(pos.entryPrice).multiply(qty)
                PositionDirection.SHORT -> pos.entryPrice.subtract(current).multiply(qty)
            }
        return if (pos.instrumentType == InstrumentType.FUTURES) {
            raw.multiply(instrumentsConfig.pointValue(pos.ticker))
        } else {
            raw
        }
    }

    /**
     * Пиковый AUM и текущая просадка от пика в %.
     *
     * Running equity: стартовый депозит + накопленный реализованный P&L
     * в хронологическом порядке закрытий (агрегируется в БД). Просадка =
     * (peak - current) / peak * 100. (Нереализованный P&L в пике не учитывается —
     * только реализованные закрытия.)
     *
     * @return (peakAum, drawdownPercent), drawdownPercent в [0..100]
     */
    private fun peakAumAndDrawdown(aggregates: PositionRepository.ClosedPositionAggregates): PeakAndDrawdown {
        val start = aumProvider.latestAum()
        val running = start.add(aggregates.totalRealized)
        val peak = start.add(aggregates.peakRealized.coerceAtLeast(BigDecimal.ZERO))
        val drawdownPercent =
            if (peak > BigDecimal.ZERO) {
                peak
                    .subtract(running)
                    .multiply(BigDecimal("100"))
                    .divide(peak, 4, RoundingMode.HALF_UP)
                    .toDouble()
            } else {
                0.0
            }
        return PeakAndDrawdown(peakAum = peak, drawdownPercent = drawdownPercent)
    }

    private data class PeakAndDrawdown(
        val peakAum: BigDecimal,
        val drawdownPercent: Double,
    )

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

    /**
     * Дневной лимит убытка в рублях: чистый % от AUM.
     * Рублёвое значение используется только при отключённом процентном лимите (<= 0).
     */
    private fun effectiveDailyLossLimitRub(aum: BigDecimal): BigDecimal =
        if (riskConfig.maxDailyLossPercent > 0) {
            percentOfAum(aum, riskConfig.maxDailyLossPercent)
        } else {
            riskConfig.maxDailyLossRub
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

    /**
     * Сброс/восстановление дневного состояния аккумулятора при смене календарного дня (МСК).
     * При рестарте в течение дня восстанавливает значения из daily_risk_snapshot.
     */
    private fun resetDailyStateIfNewDay() {
        val today = LocalDate.now(moscowZone)
        if (lastTradingDate == today) return
        lastTradingDate = today
        loadDailyState(today)
    }

    private fun loadDailyState(today: LocalDate) {
        val snapshot =
            try {
                dailyRiskSnapshotRepo.findByDate(today)
            } catch (e: Exception) {
                logger.warn(e) { "Daily risk snapshot load failed" }
                null
            }
        todayPnl = snapshot?.dailyPnl ?: BigDecimal.ZERO
        todayDailyLossReached = snapshot?.limitReached ?: false
        logger.info { "Daily risk state for $today: dailyPnL=$todayPnl limitReached=$todayDailyLossReached" }
    }

    private fun persistDailyState() {
        try {
            dailyRiskSnapshotRepo.upsert(lastTradingDate, todayPnl, todayDailyLossReached, todayPnl.coerceAtMost(BigDecimal.ZERO))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist daily risk snapshot" }
        }
    }

    private fun recordMetrics(status: DrawdownStatus) {
        meterRegistry.gauge("drawdown.aum", status.aum.toDouble())
        meterRegistry.gauge("drawdown.peak_aum", status.peakAum.toDouble())
        meterRegistry.gauge("drawdown.percent", status.drawdownPercent)
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
     * Полный пересчёт из БД реконсилит синхронный дневной аккумулятор (перезапись).
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
