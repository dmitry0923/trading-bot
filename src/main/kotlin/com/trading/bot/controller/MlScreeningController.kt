package com.trading.bot.controller

import com.trading.bot.config.MlConfig
import com.trading.bot.model.dto.MlScreeningResult
import com.trading.bot.service.MlScreeningService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * ML-скрининг кандидатов (roadmap v2.4, раздел 13.11.4).
 *
 * GET /api/v1/ml/screen?tickers=SBER,GAZP&topN=5 — ранжирование тикеров по
 * вероятности выигрышного исхода модели CatBoost (13.11.3) на текущих данных.
 *
 * Гейтится ml.enabled: при выключенном модуле — 404. При недоступной модели
 * (нет файла/битый .cbm) сервис отвечает 503. Требует аутентификации (GET).
 */
@RestController
@RequestMapping("/api/v1/ml")
class MlScreeningController(
    private val mlConfig: MlConfig,
    private val mlScreeningService: MlScreeningService,
) {
    @GetMapping("/screen")
    suspend fun screen(
        @RequestParam tickers: List<String>,
        @RequestParam(required = false) topN: Int?,
    ): MlScreeningResult {
        requireEnabled()
        return mlScreeningService.screen(tickers, topN)
    }

    private fun requireEnabled() {
        if (!mlConfig.enabled) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "ML module disabled (ml.enabled=false)")
        }
    }
}
