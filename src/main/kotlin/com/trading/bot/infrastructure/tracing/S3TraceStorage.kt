package com.trading.bot.infrastructure.tracing

import com.trading.bot.config.TraceStorageConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Сохранение LLM-трейсов в объектное хранилище MinIO/S3.
 *
 * Ключ объекта: `<traceId>/<agent>/<createdAt>-<uuid>.json`, где traceId = cycleId.
 * Бакет создаётся автоматически при первом сохранении (idempotent).
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

    private val client: MinioClient by lazy {
        MinioClient
            .builder()
            .endpoint(config.endpoint)
            .credentials(config.accessKey, config.secretKey)
            .region(config.region)
            .build()
    }

    override suspend fun save(trace: LlmTrace): String? {
        if (!config.enabled) return null
        return try {
            withContext(Dispatchers.IO) {
                ensureBucket()
                val key = keyFor(trace)
                val bytes = objectMapper.writeValueAsBytes(trace)
                client.putObject(
                    PutObjectArgs
                        .builder()
                        .bucket(config.bucket)
                        .`object`(key)
                        .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                        .contentType("application/json")
                        .build(),
                )
                key
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to store LLM trace to S3/MinIO (bucket=${config.bucket})" }
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
    }

    private fun keyFor(trace: LlmTrace): String {
        val traceDir = trace.traceId?.takeIf { it.isNotBlank() } ?: "no-trace"
        val uid = UUID.randomUUID()
        return "$traceDir/${trace.agent}/${trace.createdAt}-$uid.json"
    }

    override suspend fun list(limit: Int): List<String> {
        if (!config.enabled) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                ensureBucket()
                client
                    .listObjects(
                        ListObjectsArgs
                            .builder()
                            .bucket(config.bucket)
                            .build(),
                    ).mapNotNull { item ->
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
        } catch (e: Exception) {
            logger.warn(e) { "Failed to list LLM traces in S3/MinIO (bucket=${config.bucket})" }
            emptyList()
        }
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
}
