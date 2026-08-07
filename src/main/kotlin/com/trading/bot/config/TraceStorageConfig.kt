package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация хранилища LLM-трейсов (S3/MinIO), prefix = "trace-storage".
 *
 * Каждый LLM-вызов (полный промпт + ответ + метаданные) сохраняется в объектное
 * хранилище как JSON; в БД (agent_logs.storage_key) остаётся ссылка на объект,
 * чтобы не раздувать таблицу сырыми промптами.
 *
 * @property enabled включает сохранение трейсов (по умолчанию выключено)
 * @property endpoint endpoint MinIO/S3 (например http://localhost:9000)
 * @property accessKey access key MinIO
 * @property secretKey secret key MinIO
 * @property bucket бакет для трейсов (создаётся при первом сохранении)
 * @property region регион (по умолчанию us-east-1, MinIO не проверяет)
 */
@Component
@ConfigurationProperties(prefix = "trace-storage")
class TraceStorageConfig {
    var enabled: Boolean = false
    var endpoint: String = "http://localhost:9000"
    var accessKey: String = ""
    var secretKey: String = ""
    var bucket: String = "llm-traces"
    var region: String = "us-east-1"
}
