package com.trading.bot.controller

import com.trading.bot.model.entity.TradingAccount
import com.trading.bot.repository.DailyRiskSnapshotRepository
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.AumProvider
import com.trading.bot.service.DrawdownProtectionService
import com.trading.bot.service.TradingAccountService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Управление торговыми аккаунтами (multi-account, roadmap v2.2).
 *
 * - `GET /api/v1/accounts` — список аккаунтов с числом открытых позиций;
 * - `GET /api/v1/accounts/{id}` — аккаунт + его открытые позиции;
 * - `GET /api/v1/accounts/{id}/dashboard` — live-снимок аккаунта (AUM, daily P&L,
 *   лимиты, открытые/закрытые сегодня позиции);
 * - `GET /api/v1/accounts/{id}/daily-pnl` — история дневных P&L аккаунта;
 * - `POST /api/v1/accounts` — создать (ADMIN);
 * - `PUT /api/v1/accounts/{id}` — полная замена (ADMIN): nullable-поля `null` очищают,
 *   непустые — обязательны;
 * - `DELETE /api/v1/accounts/{id}` — удалить (ADMIN), 409 при позициях
 *   или неотправленных outbox-ордерах.
 *
 * Пустая таблица = legacy single-account режим (портфель из AlorConfig.portfolio).
 */
@RestController
@RequestMapping("/api/v1/accounts")
class TradingAccountController(
    private val tradingAccountService: TradingAccountService,
    private val positionRepository: PositionRepository,
    private val orderOutboxRepository: OrderOutboxRepository,
    private val dailyRiskSnapshotRepository: DailyRiskSnapshotRepository,
    private val aumProvider: AumProvider,
    private val drawdownProtectionService: DrawdownProtectionService,
) {
    @GetMapping
    suspend fun list(): List<Map<String, Any?>> =
        tradingAccountService.findAll().map { account ->
            summary(account) + mapOf("openPositions" to positionRepository.findOpenCountByAccount(account.id))
        }

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: Long,
    ): Map<String, Any?> {
        val account = tradingAccountService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "account not found: $id")
        return summary(account) + mapOf(
            "openPositions" to positionRepository.findOpenByAccount(id),
            "openPositionsCount" to positionRepository.findOpenCountByAccount(id),
        )
    }

    /**
     * Live-снимок аккаунта: AUM, дневной P&L и лимиты, открытые позиции с P&L,
     * закрытые сегодня. Per-account аналог `/api/v1/dashboard`.
     */
    @GetMapping("/{id}/dashboard")
    suspend fun accountDashboard(
        @PathVariable id: Long,
    ): Map<String, Any?> {
        val account = tradingAccountService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "account not found: $id")
        val openPositions = positionRepository.findOpenByAccount(id)
        val openPnl = openPositions.sumOf { it.pnl?.toDouble() ?: 0.0 }
        val todayStart = LocalDate.now().atStartOfDay()
        val closedToday = positionRepository.findClosedByAccountSince(id, todayStart)
        val realizedPnlToday = closedToday.sumOf { it.pnl?.toDouble() ?: 0.0 }
        return mapOf(
            "account" to summary(account),
            "portfolio" to account.alorPortfolio,
            "aum" to aumProvider.currentAum(id),
            "dailyPnl" to drawdownProtectionService.getDailyPnl(id),
            "dailyLossLimitReached" to drawdownProtectionService.isDailyLossLimitReached(id),
            "entryBlocked" to drawdownProtectionService.isEntryBlocked(id),
            "maxDailyLossRub" to tradingAccountService.maxDailyLossRubFor(id),
            "maxOpenPositions" to tradingAccountService.maxOpenPositionsFor(id),
            "openPositions" to openPositions,
            "openPositionsCount" to openPositions.size,
            "openPnl" to BigDecimal(openPnl),
            "realizedPnlToday" to BigDecimal(realizedPnlToday),
            "closedTodayCount" to closedToday.size,
            "timestamp" to LocalDateTime.now().toString(),
        )
    }

    /**
     * История дневных P&L аккаунта из daily_risk_snapshot (по одной точке на дату,
     * по возрастанию). Источник графика дневных результатов конкретного аккаунта.
     */
    @GetMapping("/{id}/daily-pnl")
    suspend fun dailyPnlHistory(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "30") days: Int,
    ): Map<String, Any> {
        tradingAccountService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "account not found: $id")
        val clamped = days.coerceIn(1, 365)
        val points =
            dailyRiskSnapshotRepository.findRecent(clamped, id).map { snapshot ->
                mapOf(
                    "tradeDate" to snapshot.tradeDate.toString(),
                    "pnl" to snapshot.dailyPnl,
                    "limitReached" to snapshot.limitReached,
                )
            }
        return mapOf("accountId" to id, "points" to points)
    }

    @PostMapping
    suspend fun create(
        @RequestBody request: TradingAccountRequest,
    ): Map<String, Any?> {
        validate(request)
        val account =
            tradingAccountService.create(
                name = request.name.trim(),
                alorPortfolio = request.alorPortfolio.trim(),
                exchange = request.exchange,
                enabled = request.enabled,
                aumRub = request.aumRub,
                maxOpenPositions = request.maxOpenPositions,
                maxDailyLossRub = request.maxDailyLossRub,
                weight = request.weight,
            )
        return summary(account)
    }

    /**
     * Полная замена: `name`/`alorPortfolio`/`exchange`/`enabled`/`weight` обязательны,
     * `aumRub`/`maxOpenPositions`/`maxDailyLossRub` — `null` очищает персональный лимит.
     */
    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable id: Long,
        @RequestBody request: TradingAccountRequest,
    ): Map<String, Any?> {
        validate(request)
        val account =
            tradingAccountService.update(
                id = id,
                name = request.name.trim(),
                alorPortfolio = request.alorPortfolio.trim(),
                exchange = request.exchange,
                enabled = request.enabled,
                aumRub = request.aumRub,
                maxOpenPositions = request.maxOpenPositions,
                maxDailyLossRub = request.maxDailyLossRub,
                weight = request.weight,
            ) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "account not found: $id")
        return summary(account)
    }

    @DeleteMapping("/{id}")
    suspend fun delete(
        @PathVariable id: Long,
    ): Map<String, Any> {
        tradingAccountService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "account not found: $id")
        val positions = positionRepository.countByAccount(id)
        if (positions > 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "cannot delete account $id: $positions position(s) reference it (FK fk_positions_account); " +
                    "history must be preserved",
            )
        }
        val pendingOutbox = orderOutboxRepository.countPendingByAccount(id)
        if (pendingOutbox > 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "cannot delete account $id: $pendingOutbox undelivered outbox order(s); let them settle or fail first",
            )
        }
        tradingAccountService.delete(id)
        return mapOf("deleted" to true, "id" to id)
    }

    private fun summary(account: TradingAccount): Map<String, Any?> =
        mapOf(
            "id" to account.id,
            "name" to account.name,
            "alorPortfolio" to account.alorPortfolio,
            "exchange" to account.exchange,
            "enabled" to account.enabled,
            "aumRub" to account.aumRub,
            "maxOpenPositions" to account.maxOpenPositions,
            "maxDailyLossRub" to account.maxDailyLossRub,
            "weight" to account.weight,
            "createdAt" to account.createdAt.toString(),
            "updatedAt" to account.updatedAt.toString(),
        )

    private fun validate(request: TradingAccountRequest) {
        if (request.name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank")
        }
        if (request.alorPortfolio.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "alorPortfolio must not be blank")
        }
        if (request.weight < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "weight must be >= 1")
        }
        request.maxOpenPositions?.let {
            if (it < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "maxOpenPositions must be >= 0")
        }
        request.aumRub?.let {
            if (it.signum() <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "aumRub must be > 0")
        }
    }
}

/**
 * Тело запроса create/update аккаунта. `exchange`/`enabled`/`weight` имеют дефолты
 * (заполняются на сервере при отсутствии в JSON).
 */
data class TradingAccountRequest(
    val name: String,
    val alorPortfolio: String,
    val exchange: String = "MOEX",
    val enabled: Boolean = true,
    val aumRub: BigDecimal? = null,
    val maxOpenPositions: Int? = null,
    val maxDailyLossRub: BigDecimal? = null,
    val weight: Int = 1,
)
