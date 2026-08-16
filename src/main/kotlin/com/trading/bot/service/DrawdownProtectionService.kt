package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.DailyRiskGuard
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.infrastructure.metrics.MutableGauges
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
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.boot.context.event.ApplicationReadyEvent
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
    private val tradingAccountService: TradingAccountService,
) : DailyRiskGuard {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @PreDestroy
    fun close() {
        scope.cancel()
    }

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

    // Per-account дневной P&L (multi-account, roadmap v2.2). Ключ — accountId.
    private val accountPnl: java.util.concurrent.ConcurrentHashMap<Long, BigDecimal> = java.util.concurrent.ConcurrentHashMap()
    private val accountLossReached: java.util.concurrent.ConcurrentHashMap<Long, Boolean> = java.util.concurrent.ConcurrentHashMap()
    private val accountLoadedDate: java.util.concurrent.ConcurrentHashMap<Long, LocalDate> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Загрузка дневного состояния из daily_risk_snapshot при старте (ApplicationReadyEvent),
     * чтобы дневной P&L не «обнулялся» до первого стратегического цикла/закрытия позиции
     * (см. roadmap 13.7.2: при рестарте в течение дня лимит убытка должен быть восстановлен).
     */
    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        try {
            resetDailyStateIfNewDay()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load daily risk state at startup" }
        }
    }

    /**
     * Полный пересчёт Multi-Tier статуса из фактических сделок в БД.
     * Вызывается один раз за стратегический цикл и при закрытии позиции.
     *
     * @return текущий [DrawdownStatus]
     */
    suspend fun computeStatus(): DrawdownStatus {
        resetDailyStateIfNewDay()
        aumProvider.currentAum() // обновление баланса из Alor перед расчётом лимитов
        // МСК, а не серверный LocalDateTime.now() — иначе граница дня в computeStatus
        // может разойтись с аккумулятором (resetDailyStateIfNewDay/updateDailyPnl).
        val now = LocalDateTime.now(moscowZone)
        val todayStart = now.toLocalDate().atStartOfDay()
        // Оконные запросы вместо полного сканирования всех закрытых позиций.
        val closedSince30d = positionRepo.findClosedSince(now.minusDays(30))
        val closedToday = positionRepo.findClosedSince(todayStart)
        val open = positionRepo.findByStatus(PositionStatus.OPEN)
        val aggregates = positionRepo.findClosedAggregates()
        val aum = currentAum(open)
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
    override fun cachedOrNeutral(accountId: Long?): DrawdownStatus {
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
    override fun updateDailyPnl(
        pnl: BigDecimal,
        accountId: Long?,
    ) {
        resetDailyStateIfNewDay()
        if (accountId != null) {
            updateAccountDailyPnl(accountId, pnl)
            return
        }
        todayPnl = todayPnl.add(pnl)
        val aum = cachedStatus?.aum ?: aumProvider.latestAum()
        val dailyLimit = effectiveDailyLossLimitRub(aum)
        if (todayPnl <= dailyLimit.negate()) {
            todayDailyLossReached = true
            logger.error { "DAILY LOSS LIMIT reached: dailyPnL=$todayPnl <= -$dailyLimit (${riskConfig.maxDailyLossPercent}% of AUM)" }
        }
        persistDailyState()
        MutableGauges.set(meterRegistry, "risk.daily.pnl", todayPnl.toDouble())
        MutableGauges.set(meterRegistry, "risk.daily.limit.reached", if (todayDailyLossReached) 1.0 else 0.0)
        // Синхронное обновление кэша — входы блокируются немедленно, без ожидания цикла.
        cachedStatus?.let { s ->
            val updated = s.copy(dailyPnlRub = todayPnl, dailyLimitBreached = todayDailyLossReached)
            cachedStatus = updated
            recordMetrics(updated)
        }
    }

    /**
     * Per-account аккумулятор дневного P&L. Персональный лимит:
     * account.maxDailyLossRub ?: % AUM аккаунта (fallback — глобальный процентный лимит).
     * Снапшот персистится в daily_risk_snapshot(account_id).
     */
    private fun updateAccountDailyPnl(
        accountId: Long,
        pnl: BigDecimal,
    ) {
        val day = LocalDate.now(moscowZone)
        loadAccountDailyState(accountId, day)
        val newPnl = (accountPnl[accountId] ?: BigDecimal.ZERO).add(pnl)
        accountPnl[accountId] = newPnl
        val aum = aumProvider.latestAum(accountId)
        val dailyLimit = effectiveDailyLossLimitRubFor(accountId, aum)
        if (newPnl <= dailyLimit.negate()) {
            accountLossReached[accountId] = true
            logger.error { "DAILY LOSS LIMIT reached (account=$accountId): dailyPnL=$newPnl <= -$dailyLimit" }
        }
        persistDailyState(accountId)
        MutableGauges.set(meterRegistry, "risk.daily.pnl", newPnl.toDouble(), Tags.of("account", accountId.toString()))
        MutableGauges.set(
            meterRegistry,
            "risk.daily.limit.reached",
            if (accountLossReached[accountId] == true) 1.0 else 0.0,
            Tags.of("account", accountId.toString()),
        )
    }

    private fun loadAccountDailyState(
        accountId: Long,
        day: LocalDate,
    ) {
        if (accountLoadedDate[accountId] == day) return
        val snapshot =
            try {
                dailyRiskSnapshotRepo.findByDate(day, accountId)
            } catch (e: Exception) {
                logger.warn(e) { "Daily risk snapshot load failed for account=$accountId" }
                null
            }
        accountPnl[accountId] = snapshot?.dailyPnl ?: BigDecimal.ZERO
        accountLossReached[accountId] = snapshot?.limitReached ?: false
        accountLoadedDate[accountId] = day
    }

    /**
     * Достигнут ли дневной лимит убытка (кэш, без БД).
     * При рестарте в течение дня для аккаунта восстанавливает снапшот из БД
     * (по аналогии с [getDailyPnl]) — до этого возвращала false без проверки.
     */
    override fun isDailyLossLimitReached(accountId: Long?): Boolean {
        if (accountId == null) return cachedOrNeutral().dailyLimitBreached
        val day = LocalDate.now(moscowZone)
        if (accountLoadedDate[accountId] != day) {
            loadAccountDailyState(accountId, day)
        }
        return (accountLossReached[accountId] ?: false) || cachedOrNeutral().dailyLimitBreached
    }

    /**
     * Текущий дневной P&L (кэш, без БД).
     */
    override fun getDailyPnl(accountId: Long?): BigDecimal {
        if (accountId == null) return cachedOrNeutral().dailyPnlRub
        val day = LocalDate.now(moscowZone)
        if (accountLoadedDate[accountId] != day) {
            loadAccountDailyState(accountId, day)
        }
        return accountPnl[accountId] ?: BigDecimal.ZERO
    }

    /**
     * Заблокированы ли новые входы (кэш). Покрывает все tier-лимиты и Shadow/Read-only,
     * а также per-account дневной лимит.
     */
    override fun isEntryBlocked(accountId: Long?): Boolean = cachedOrNeutral().blocking() || isDailyLossLimitReached(accountId)

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

    private fun currentAum(open: List<Position>): BigDecimal {
        val unrealized = unrealizedPnl(open)
        // F-3 (roadmap 13.25): latestAum() = текущий баланс счёта (moneyAmount) уже
        // содержит реализованный P&L — totalRealized добавлять НЕЛЬЗЯ (двойной счёт
        // в AUM и дневном лимите). Equity = баланс + нереализованный P&L открытых.
        return aumProvider
            .latestAum()
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
     * F-3 (roadmap 13.25): [latestAum] — ТЕКУЩИЙ баланс счёта (уже включает
     * реализованный P&L), а не стартовый депозит. Стартовый депозит восстанавливается
     * как deposit = balance - totalRealized; текущая equity running = balance;
     * peak = deposit + peakRealized.
     *
     * @return (peakAum, drawdownPercent), drawdownPercent в [0..100]
     */
    private fun peakAumAndDrawdown(aggregates: PositionRepository.ClosedPositionAggregates): PeakAndDrawdown {
        val balance = aumProvider.latestAum()
        val deposit = balance.subtract(aggregates.totalRealized).coerceAtLeast(BigDecimal.ZERO)
        val running = balance
        val peak = deposit.add(aggregates.peakRealized.coerceAtLeast(BigDecimal.ZERO))
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

    private fun persistDailyState(accountId: Long) {
        try {
            dailyRiskSnapshotRepo.upsert(
                LocalDate.now(moscowZone),
                accountPnl[accountId] ?: BigDecimal.ZERO,
                accountLossReached[accountId] ?: false,
                (accountPnl[accountId] ?: BigDecimal.ZERO).coerceAtMost(BigDecimal.ZERO),
                accountId,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist daily risk snapshot for account=$accountId" }
        }
    }

    /**
     * Персональный дневной лимит аккаунта: account.maxDailyLossRub, иначе % AUM аккаунта.
     */
    private fun effectiveDailyLossLimitRubFor(
        accountId: Long,
        aum: BigDecimal,
    ): BigDecimal {
        val override = tradingAccountService.cachedMaxDailyLossRubFor(accountId)
        return override ?: effectiveDailyLossLimitRub(aum)
    }

    private fun recordMetrics(status: DrawdownStatus) {
        MutableGauges.set(meterRegistry, "drawdown.aum", status.aum.toDouble())
        MutableGauges.set(meterRegistry, "drawdown.peak_aum", status.peakAum.toDouble())
        MutableGauges.set(meterRegistry, "drawdown.percent", status.drawdownPercent)
        MutableGauges.set(meterRegistry, "drawdown.daily.pnl", status.dailyPnlRub.toDouble(), Tags.of("unit", "rub"))
        MutableGauges.set(meterRegistry, "drawdown.daily.percent", percentOf(status.dailyPnlRub, status.aum))
        MutableGauges.set(meterRegistry, "drawdown.rolling7d.percent", percentOf(status.rolling7dPnlRub, status.aum))
        MutableGauges.set(meterRegistry, "drawdown.rolling30d.percent", percentOf(status.rolling30dPnlRub, status.aum))
        MutableGauges.set(meterRegistry, "drawdown.consecutive.losses", status.consecutiveLosses.toDouble())
        MutableGauges.set(meterRegistry, "drawdown.shadow.mode", if (status.shadowModeActive) 1.0 else 0.0)
        MutableGauges.set(meterRegistry, "drawdown.blocked", if (status.blocking()) 1.0 else 0.0)
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
