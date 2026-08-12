package com.trading.bot.controller

import com.trading.bot.config.MlConfig
import com.trading.bot.model.dto.MlTrendResult
import com.trading.bot.service.MlTrendForecastService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * ML-прогноз удержания тренда (roadmap v2.4, раздел 13.11.7).
 *
 * GET /api/v1/ml/trend?tickers=SBER,GAZP&topN=5 — ранжирование тикеров по оценке
 * удержания тренда (модель CatBoost + детерминированная сила тренда по индикаторам).
 *
 * Гейтится ml.enabled: при выключенном модуле — 404. При недоступной модели
 * (нет файла/битый .cbm) сервис отвечает 503. Требует аутентификации (GET).
 */
@RestController
@RequestMapping("/api/v1/ml")
class MlTrendController(
    private val mlConfig: MlConfig,
    private val mlTrendForecastService: MlTrendForecastService,
) {
    @GetMapping("/trend")
    suspend fun trend(
        @RequestParam tickers: List<String>,
        @RequestParam(required = false) topN: Int?,
    ): MlTrendResult {
        requireEnabled()
        return mlTrendForecastService.forecast(tickers, topN)
    }

    private fun requireEnabled() {
        if (!mlConfig.enabled) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "ML module disabled (ml.enabled=false)")
        }
    }
}
