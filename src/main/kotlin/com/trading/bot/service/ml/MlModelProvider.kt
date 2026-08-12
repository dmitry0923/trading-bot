package com.trading.bot.service.ml

import ai.catboost.CatBoostModel
import com.trading.bot.config.MlConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File

/**
 * Поставщик ML-модели для скрининга (roadmap v2.4, 13.11.4).
 *
 * Модель загружается лениво при первом обращении ([MlScreeningService]) и кэшируется.
 * Graceful degradation: при `ml.enabled=false` или отсутствующем/битом файле
 * ([MlConfig.model].path) подставляется [NoopMlModel] — бот не падает, скрининг
 * отвечает 503. Промоушн модели выполняется отдельно (env `ML_MODEL_PATH`).
 */
@Service
class MlModelProvider(
    private val mlConfig: MlConfig,
) {
    private val logger = KotlinLogging.logger {}

    val model: MlModel by lazy { load() }

    internal fun load(): MlModel {
        if (!mlConfig.enabled) {
            logger.info { "ML model disabled (ml.enabled=false), screening will return 503" }
            return NoopMlModel("ml.enabled=false")
        }
        val file = File(mlConfig.model.path)
        if (!file.isFile) {
            logger.warn { "ML model file not found at ${mlConfig.model.path}, screening will return 503" }
            return NoopMlModel("model file not found: ${mlConfig.model.path}")
        }
        return try {
            CatBoostMlModel(CatBoostModel.loadModel(file.absolutePath))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load ML model from ${mlConfig.model.path}, screening will return 503" }
            NoopMlModel("model load failed: ${e.message}")
        }
    }
}
