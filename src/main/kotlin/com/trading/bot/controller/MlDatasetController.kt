package com.trading.bot.controller

import com.trading.bot.config.MlConfig
import com.trading.bot.service.MlDatasetService
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
 * Экспорт ML-датасета (roadmap v2.4, раздел 13.11).
 *
 * - GET /api/v1/ml/dataset — CSV-датасет по закрытым позициям
 *   (positions + candles + agent_logs + макро + слепые зоны), скачивание файла;
 * - GET /api/v1/ml/dataset/stats — статистика для контроля качества данных.
 *
 * Оба эндпоинта гейтятся ml.enabled: при выключенном модуле — 404.
 * Требуют аутентификации (все пути /api/v1), GET — любой авторизованный роль.
 */
@RestController
@RequestMapping("/api/v1/ml")
class MlDatasetController(
    private val mlConfig: MlConfig,
    private val mlDatasetService: MlDatasetService,
) {
    @GetMapping("/dataset")
    suspend fun dataset(
        @RequestParam(required = false) since: LocalDateTime?,
        @RequestParam(required = false) ticker: String?,
        @RequestParam(required = false) maxRows: Int?,
    ): ResponseEntity<String> {
        requireEnabled()
        val export = mlDatasetService.export(since, ticker, maxRows)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ml_dataset.csv\"")
            .body(export.toCsv())
    }

    @GetMapping("/dataset/stats")
    suspend fun stats(
        @RequestParam(required = false) since: LocalDateTime?,
        @RequestParam(required = false) ticker: String?,
    ): Map<String, Any?> {
        requireEnabled()
        return mlDatasetService.stats(since, ticker)
    }

    private fun requireEnabled() {
        if (!mlConfig.enabled) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "ML module disabled (ml.enabled=false)")
        }
    }
}
