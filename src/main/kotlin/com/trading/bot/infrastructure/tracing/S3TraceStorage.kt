package com.trading.bot.infrastructure.tracing

import com.trading.bot.config.TraceStorageConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.SetBucketLifecycleArgs
import io.minio.messages.Expiration
import io.minio.messages.LifecycleConfiguration
import io.minio.messages.LifecycleRule
import io.minio.messages.RuleFilter
import io.minio.messages.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream

/**
 * Сохранение LLM-трейсов в объектное хранилище MinIO/S3.
 *
 * Ключ объекта: `<traceId>/<agent>/<createdAt>-<uuid>.json`, где traceId = cycleId.
 * Бакет создаётся автоматически при первом сохранении (idempotent).
 * Если задан [TraceStorageConfig.retentionDays] > 0 — на бакет один раз применяется
 * lifecycle-правило с Expiration (MinIO поддерживает с версии RELEASE.2021-12-29).
 *
 * Best-effort: любая ошибка S3 логируется и возвращает null — торговля
 * не прерывается из-за недоступности объектного хранилища.
 */
@Component
class S3TraceStorage(
    private val config: TraceStorageConfig,
    private val objectMapper: ObjectMapper,
) : TraceStorage {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var lifecycleApplied = false

    private val client: MinioClient by lazy {
        MinioClient
            .builder()
            .endpoint(config.endpoint)
            .credentials(config.accessKey, config.secretKey)
            .region(config.region)
            .build()
    }

    override suspend fun save(
        trace: LlmTrace,
        key: String?,
    ): String? {
        if (!config.enabled) return null
        return try {
            withContext(Dispatchers.IO) {
                ensureBucket()
                val objectKey = key ?: keyFor(trace)
                val bytes = objectMapper.writeValueAsBytes(trace)
                client.putObject(
                    PutObjectArgs
                        .builder()
                        .bucket(config.bucket)
                        .`object`(objectKey)
                        .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                        .contentType("application/json")
                        .build(),
                )
                objectKey
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to store LLM trace to S3/MinIO (bucket=${config.bucket})" }
            null
        }
    }

    /**
     * Детерминированный ключ объекта трейса. Синхронизирован с
     * [AsyncTraceStorage], который вычисляет ключ до постановки в очередь.
     */
    internal fun keyFor(trace: LlmTrace): String = traceObjectKey(trace)

    override suspend fun list(limit: Int): List<String> {
        if (!config.enabled) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                ensureBucket()
                listKeys(null, limit)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to list LLM traces in S3/MinIO (bucket=${config.bucket})" }
            emptyList()
        }
    }

    override suspend fun listByTraceId(
        traceId: String,
        limit: Int,
    ): List<String> {
        if (!config.enabled || traceId.isBlank()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                ensureBucket()
                listKeys("$traceId/", limit)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to list LLM traces for traceId=$traceId in S3/MinIO" }
            emptyList()
        }
    }

    private fun listKeys(
        prefix: String?,
        limit: Int,
    ): List<String> {
        val argsBuilder = ListObjectsArgs.builder().bucket(config.bucket)
        if (prefix != null) argsBuilder.prefix(prefix)
        return client
            .listObjects(argsBuilder.build())
            .mapNotNull { item ->
                try {
                    item.get()
                } catch (e: Exception) {
                    logger.warn { "Skipping trace object listing error: ${e.message}" }
                    null
                }
            }.sortedByDescending { it.lastModified() }
            .take(limit.coerceIn(1, 10_000))
            .map { it.objectName() }
    }

    override suspend fun read(key: String): LlmTrace? {
        if (!config.enabled) return null
        return try {
            withContext(Dispatchers.IO) {
                client
                    .getObject(
                        GetObjectArgs
                            .builder()
                            .bucket(config.bucket)
                            .`object`(key)
                            .build(),
                    ).use { stream ->
                        objectMapper.readValue(stream, LlmTrace::class.java)
                    }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read LLM trace $key from S3/MinIO" }
            null
        }
    }

    private fun ensureBucket() {
        val exists =
            client.bucketExists(
                BucketExistsArgs
                    .builder()
                    .bucket(config.bucket)
                    .build(),
            )
        if (!exists) {
            client.makeBucket(
                MakeBucketArgs
                    .builder()
                    .bucket(config.bucket)
                    .build(),
            )
            logger.info { "Created trace bucket ${config.bucket}" }
        }
        if (!lifecycleApplied && config.retentionDays > 0) {
            try {
                applyLifecycle()
                lifecycleApplied = true
            } catch (e: Exception) {
                logger.warn(e) { "Failed to apply S3 lifecycle for bucket ${config.bucket}" }
            }
        }
    }

    private fun applyLifecycle() {
        val rule =
            LifecycleRule(
                Status.ENABLED,
                null,
                Expiration(null as io.minio.messages.ResponseDate?, config.retentionDays, null),
                RuleFilter(""),
                "expire-traces",
                null,
                null,
                null,
            )
        val lifecycleConfig =
            LifecycleConfiguration(
                listOf(rule),
            )
        client.setBucketLifecycle(
            SetBucketLifecycleArgs
                .builder()
                .bucket(config.bucket)
                .config(lifecycleConfig)
                .build(),
        )
        logger.info { "Applied S3 lifecycle: expire ${config.bucket} after ${config.retentionDays} days" }
    }
}
