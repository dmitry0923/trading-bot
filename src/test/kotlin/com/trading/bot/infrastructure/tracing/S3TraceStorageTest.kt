package com.trading.bot.infrastructure.tracing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.TraceStorageConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Unit-тесты [S3TraceStorage] поверх локального фейкового S3 (MinIO-клиент ходит на
 * `TraceStorageConfig.endpoint`): успешная запись/чтение/листинг, создание бакета,
 * lifecycle-retention применяется один раз, disabled-режим не делает вызовов и все
 * error-пути возвращают null/пустой список (best-effort). Облако не используется.
 */
class S3TraceStorageTest {
    /** Тот же mapper, что Spring инжектит в продовый бин (с Kotlin-модулем). */
    private val mapper = jacksonObjectMapper()

    private fun trace(agent: String = "strategy") =
        LlmTrace(
            traceId = "cycle-1",
            ticker = "SBER",
            agent = agent,
            provider = "KIMI",
            model = "kimi-k3",
            fingerprint = "fp",
            systemPrompt = "system",
            userPrompt = "user",
            responseContent = "{}",
            tokensUsed = 10,
            latencyMs = 100,
            isFallback = false,
            fromCache = false,
            createdAt = Instant.parse("2026-08-07T10:00:00Z"),
        )

    private fun config(
        server: FakeS3Server,
        enabled: Boolean = true,
        retentionDays: Int = 0,
    ) = TraceStorageConfig().apply {
        this.enabled = enabled
        endpoint = server.baseUrl
        accessKey = "test-access"
        secretKey = "test-secret"
        bucket = "llm-traces"
        region = "us-east-1"
        this.retentionDays = retentionDays
    }

    @Test
    fun `save uploads trace json and returns deterministic key`() =
        runBlocking {
            FakeS3Server().use { server ->
                val storage = S3TraceStorage(config(server), mapper)

                val key = storage.save(trace(), null)

                assertNotNull(key)
                assertTrue(key!!.startsWith("cycle-1/strategy/"), key)
                val upload = server.requests.single { it.method == "PUT" && it.path.startsWith("/llm-traces/cycle-1/") }
                assertTrue(upload.body.contains("systemPrompt"))
                assertTrue(upload.body.contains("\"isFallback\""), "Kotlin bean naming must be preserved: ${upload.body}")
                assertEquals("/llm-traces/$key", upload.path)
            }
        }

    @Test
    fun `save creates missing bucket and applies lifecycle only once`() =
        runBlocking {
            FakeS3Server().use { server ->
                server.bucketExists = false
                val storage = S3TraceStorage(config(server, retentionDays = 7), mapper)

                assertNotNull(storage.save(trace(), "cycle-1/strategy/a.json"))
                assertNotNull(storage.save(trace(), "cycle-1/strategy/b.json"))

                assertEquals(
                    1,
                    server.requests.count { it.method == "PUT" && it.path == "/llm-traces" && it.query?.contains("lifecycle") != true },
                    server.requests.joinToString { "${it.method} ${it.path}?${it.query}" },
                )
                assertEquals(1, server.requests.count { it.query?.contains("lifecycle") == true })
            }
        }

    @Test
    fun `save is best effort and returns null when upload fails`() =
        runBlocking {
            FakeS3Server().use { server ->
                server.failUpload = true
                val storage = S3TraceStorage(config(server), mapper)

                assertNull(storage.save(trace(), null))
            }
        }

    @Test
    fun `disabled storage never calls S3`() =
        runBlocking {
            FakeS3Server().use { server ->
                val storage = S3TraceStorage(config(server, enabled = false), mapper)

                assertNull(storage.save(trace(), null))
                assertTrue(storage.list(10).isEmpty())
                assertTrue(storage.listByTraceId("cycle-1", 10).isEmpty())
                assertNull(storage.read("cycle-1/strategy/a.json"))
                assertTrue(server.requests.isEmpty())
            }
        }

    @Test
    fun `read parses stored trace and degrades to null on failures`() =
        runBlocking {
            FakeS3Server().use { server ->
                server.downloadBody = mapper.writeValueAsString(trace("arbitrator"))
                val storage = S3TraceStorage(config(server), mapper)

                val loaded = storage.read("cycle-1/arbitrator/a.json")
                assertEquals("arbitrator", loaded?.agent)
                assertEquals(10, loaded?.tokensUsed)
                assertEquals(false, loaded?.isFallback)

                server.downloadBody = "not-json"
                assertNull(storage.read("cycle-1/arbitrator/a.json"))

                server.downloadBody = mapper.writeValueAsString(trace("arbitrator"))
                server.failDownload = true
                assertNull(storage.read("cycle-1/arbitrator/a.json"))
            }
        }

    @Test
    fun `list returns keys sorted by last modified and honours limit`() =
        runBlocking {
            FakeS3Server().use { server ->
                val storage = S3TraceStorage(config(server), mapper)

                val limited = storage.list(1)
                assertEquals(listOf("cycle-2/strategy/new.json"), limited)

                val byTrace = storage.listByTraceId("cycle-1", 10)
                assertEquals(listOf("cycle-1/strategy/old.json"), byTrace)
                assertTrue(server.requests.any { it.query?.contains("prefix=cycle-1") == true })
            }
        }

    @Test
    fun `list returns empty when S3 listing fails`() =
        runBlocking {
            FakeS3Server().use { server ->
                val storage = S3TraceStorage(config(server), mapper)

                server.failList = true
                assertTrue(storage.list(10).isEmpty())
                assertTrue(storage.listByTraceId("cycle-1", 10).isEmpty())
            }
        }

    @Test
    fun `bucket probe failure degrades list and save to empty results`() =
        runBlocking {
            FakeS3Server().use { server ->
                server.failHead = true
                val storage = S3TraceStorage(config(server), mapper)

                assertTrue(storage.list(10).isEmpty())
                assertTrue(storage.listByTraceId("cycle-1", 10).isEmpty())
                assertNull(storage.save(trace(), null))
            }
        }

    @Test
    fun `lifecycle failure is tolerated and retried on the next save`() =
        runBlocking {
            FakeS3Server().use { server ->
                server.failLifecycle = true
                val storage = S3TraceStorage(config(server, retentionDays = 7), mapper)

                assertNotNull(storage.save(trace(), "cycle-1/strategy/a.json"))
                assertNotNull(storage.save(trace(), "cycle-1/strategy/b.json"))
                assertEquals(2, server.requests.count { it.query?.contains("lifecycle") == true })
            }
        }

    @Test
    fun `keyFor matches the key used by async enqueue`() {
        FakeS3Server().use { server ->
            val storage = S3TraceStorage(config(server), mapper)
            assertTrue(storage.keyFor(trace()).startsWith("cycle-1/strategy/"))
        }
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val body: String,
    )

    private class FakeS3Server : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

        var bucketExists: Boolean = true
        var failHead: Boolean = false
        var failUpload: Boolean = false
        var failDownload: Boolean = false
        var failList: Boolean = false
        var failLifecycle: Boolean = false
        var downloadBody: String = "{}"

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { handle(it) }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(RecordedRequest(method, path, query, body))
            val isBucketRoot = path == BUCKET_ROOT
            when {
                method == "HEAD" && isBucketRoot -> {
                    val status =
                        when {
                            failHead -> 500
                            bucketExists -> 200
                            else -> 404
                        }
                    respond(exchange, status, "")
                }

                method == "PUT" && isBucketRoot && query?.contains("lifecycle") == true -> {
                    respond(exchange, if (failLifecycle) 500 else 200, "")
                }

                method == "PUT" && isBucketRoot -> {
                    bucketExists = true
                    respond(exchange, 200, "")
                }

                method == "PUT" && path.startsWith("$BUCKET_ROOT/") -> {
                    respond(exchange, if (failUpload) 500 else 200, "")
                }

                method == "GET" && isBucketRoot && query?.contains("location") == true -> {
                    respond(
                        exchange,
                        200,
                        """<LocationConstraint xmlns="http://s3.amazonaws.com/doc/2006-03-01/">us-east-1</LocationConstraint>""",
                        xml = true,
                    )
                }

                method == "GET" && isBucketRoot -> {
                    respondList(exchange, query)
                }

                method == "GET" && path.startsWith("$BUCKET_ROOT/") -> {
                    respond(exchange, if (failDownload) 500 else 200, downloadBody, xml = false)
                }

                else -> {
                    respond(exchange, 404, "")
                }
            }
        }

        private fun respondList(
            exchange: HttpExchange,
            query: String?,
        ) {
            if (failList) {
                respond(exchange, 500, "")
                return
            }
            val rawPrefix = query?.substringAfter("prefix=", "")?.substringBefore("&") ?: ""
            val prefix = java.net.URLDecoder.decode(rawPrefix, java.nio.charset.StandardCharsets.UTF_8)
            val objects =
                listOf(
                    "cycle-1/strategy/old.json" to "2026-08-07T10:00:00.000Z",
                    "cycle-2/strategy/new.json" to "2026-08-07T12:00:00.000Z",
                ).filter { it.first.startsWith(prefix) }
            val contents =
                objects.joinToString("") { (key, modified) ->
                    "<Contents><Key>$key</Key><LastModified>$modified</LastModified>" +
                        "<ETag>\"etag\"</ETag><Size>10</Size><StorageClass>STANDARD</StorageClass></Contents>"
                }
            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Name>llm-traces</Name><Prefix>$prefix</Prefix><KeyCount>${objects.size}</KeyCount>
                <MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>$contents</ListBucketResult>
                """.trimIndent()
            respond(exchange, 200, xml, xml = true)
        }

        private fun respond(
            exchange: HttpExchange,
            status: Int,
            body: String,
            xml: Boolean = false,
        ) {
            val bytes = body.toByteArray()
            if (xml) exchange.responseHeaders.add("Content-Type", "application/xml")
            exchange.responseHeaders.add("ETag", "\"etag\"")
            exchange.responseHeaders.add("x-amz-bucket-region", "us-east-1")
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }

        override fun close() {
            server.stop(0)
        }

        private companion object {
            const val BUCKET_ROOT = "/llm-traces"
        }
    }
}
