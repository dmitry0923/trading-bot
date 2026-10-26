package com.trading.bot.controller

import com.trading.bot.model.dto.MicrostructureCoverage
import com.trading.bot.service.MicrostructureResearchService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

/**
 * Research-доступ к микроструктурным данным (PLAN-001, шаг «а»).
 *
 * - GET /api/v1/microstructure/coverage — покрытие трёх источников по тикеру;
 * - GET /api/v1/microstructure/snapshots — forward-L1 снапшоты (JSON);
 * - GET /api/v1/microstructure/bbo — исторические BBO-бакеты (JSON);
 * - GET /api/v1/microstructure/ticks — исторические сделки (JSON);
 * - GET /api/v1/microstructure/export.csv — выгрузка одного источника в CSV.
 *
 * Окно выборки — полуоткрытое `[from, to)`; при отсутствии границ берётся весь
 * диапазон источника. Требует аутентификации (все пути `/api/v1`, GET).
 */
@RestController
@RequestMapping("/api/v1/microstructure")
class ResearchMicrostructureController(
    private val service: MicrostructureResearchService,
) {
    @GetMapping("/coverage")
    suspend fun coverage(
        @RequestParam ticker: String,
    ): Map<String, Any?> =
        mapOf(
            "ticker" to ticker,
            "sources" to service.coverage(ticker).map { it.toMap() },
        )

    @GetMapping("/snapshots")
    suspend fun snapshots(
        @RequestParam ticker: String,
        @RequestParam(required = false) from: LocalDateTime?,
        @RequestParam(required = false) to: LocalDateTime?,
        @RequestParam(required = false) limit: Int?,
    ): Map<String, Any?> = rows(ticker, service.snapshots(ticker, from, to, limit))

    @GetMapping("/bbo")
    suspend fun bbo(
        @RequestParam ticker: String,
        @RequestParam(required = false) from: LocalDateTime?,
        @RequestParam(required = false) to: LocalDateTime?,
        @RequestParam(required = false) limit: Int?,
    ): Map<String, Any?> = rows(ticker, service.bbo(ticker, from, to, limit))

    @GetMapping("/ticks")
    suspend fun ticks(
        @RequestParam ticker: String,
        @RequestParam(required = false) from: LocalDateTime?,
        @RequestParam(required = false) to: LocalDateTime?,
        @RequestParam(required = false) limit: Int?,
    ): Map<String, Any?> = rows(ticker, service.ticks(ticker, from, to, limit))

    @GetMapping("/export.csv")
    suspend fun export(
        @RequestParam source: String,
        @RequestParam ticker: String,
        @RequestParam(required = false) from: LocalDateTime?,
        @RequestParam(required = false) to: LocalDateTime?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<String> {
        val csv =
            try {
                service.exportCsv(source, ticker, from, to, limit)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            }
        // Тикер приходит из query и попадает в заголовок ответа: без фильтра `?ticker=A"B`
        // ломает header (скан-7 P3).
        if (!ticker.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ticker in export request")
        }
        val filename = "microstructure_${source.lowercase()}_$ticker.csv"
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(csv)
    }

    private fun rows(
        ticker: String,
        rows: List<*>,
    ): Map<String, Any?> =
        mapOf(
            "ticker" to ticker,
            "count" to rows.size,
            "rows" to rows,
        )

    private fun MicrostructureCoverage.toMap(): Map<String, Any?> =
        mapOf(
            "source" to source,
            "rows" to rows,
            "from" to from,
            "to" to to,
        )
}
