package com.trading.bot.config.secrets

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Загрузка секретов из Yandex Lockbox (Cloud) без внешних SDK — только JDK HTTP.
 *
 * Порядок получения IAM-токена:
 *  1. явный `lockbox.iam-token`;
 *  2. сервисный аккаунт `lockbox.sa-key-json` (JWT-grant → IAM);
 *  3. metadata-сервис YC (если приложение крутится на VM с привязанным SA).
 *
 * Секреты добавляются как самый приоритетный PropertySource, поэтому
 * `${ALOR_TOKEN}`, `${LLM_API_KEY}` и т.д. резолвятся из Lockbox,
 * перекрывая переменные окружения.
 */
class LockboxSecretResolver(
    private val secretId: String,
    private val iamToken: String,
    private val saKeyJson: String,
    private val timeoutSeconds: Int = 10,
) {
    private val http =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .build()
    private val objectMapper = ObjectMapper()

    fun resolve(): Map<String, String> {
        require(secretId.isNotBlank()) { "lockbox.secret-id is not set" }
        val token = iamToken.ifBlank { resolveIamToken() }
        val payload = fetchSecretPayload(token)
        val entries =
            payload.get("entries")
                ?: throw IllegalStateException("Lockbox payload for secret '$secretId' has no entries")
        val result = LinkedHashMap<String, String>()
        for (entry in entries) {
            val key = entry.get("key")?.asString() ?: continue
            val textValue = entry.get("textValue")?.asString()
            val binaryValue = entry.get("binaryValue")?.asString()
            result[key] =
                textValue
                    ?: binaryValue?.let { String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) }
                    ?: ""
        }
        return result
    }

    private fun resolveIamToken(): String {
        if (saKeyJson.isNotBlank()) return exchangeForIamToken(saKeyJson)
        return runCatching { fetchMetadataToken() }.getOrNull()
            ?: throw IllegalStateException(
                "Unable to obtain IAM token: set LOCKBOX_IAM_TOKEN or LOCKBOX_SA_KEY_JSON, " +
                    "or run on a Yandex Cloud VM with a service account attached",
            )
    }

    private fun exchangeForIamToken(keyJson: String): String {
        val root = objectMapper.readTree(keyJson)
        val serviceAccountId =
            root.get("service_account_id")?.asString()
                ?: throw IllegalStateException("service_account_id is missing in service account key")
        val privateKeyPem =
            root.get("private_key")?.asString()
                ?: throw IllegalStateException("private_key is missing in service account key")
        val now = Instant.now().epochSecond
        val header = base64url("""{"alg":"RS256","typ":"JWT"}""")
        val payload =
            base64url(
                objectMapper.writeValueAsString(
                    mapOf(
                        "iss" to serviceAccountId,
                        "aud" to IAM_TOKENS_URL,
                        "iat" to now,
                        "exp" to now + 3600,
                        "jti" to UUID.randomUUID().toString(),
                    ),
                ),
            )
        val signingInput = "$header.$payload"
        val signature = signRsa256(signingInput, parsePrivateKey(privateKeyPem))
        val response = postJson("""{"jwt":"$signingInput.$signature"}""")
        return response.get("iamToken")?.asString()
            ?: throw IllegalStateException("IAM token endpoint returned no iamToken")
    }

    private fun fetchMetadataToken(): String {
        val request =
            HttpRequest
                .newBuilder(URI.create(METADATA_TOKEN_URL))
                .timeout(Duration.ofSeconds(3))
                .header("Metadata-Flavor", "Google")
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Metadata token endpoint returned HTTP ${response.statusCode()}")
        }
        return objectMapper.readTree(response.body()).get("access_token")?.asString()
            ?: throw IllegalStateException("Metadata token endpoint returned no access_token")
    }

    private fun fetchSecretPayload(token: String): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create("$PAYLOAD_URL/secrets/$secretId/payload"))
                .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
        return send(request)
    }

    private fun postJson(body: String): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create(IAM_TOKENS_URL))
                .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return send(request)
    }

    private fun send(request: HttpRequest): JsonNode {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Yandex Cloud API returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }
        return objectMapper.readTree(response.body())
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val base64 =
            pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace(Regex("\\s"), "")
        val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private fun signRsa256(
        data: String,
        privateKey: PrivateKey,
    ): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(data.toByteArray(StandardCharsets.UTF_8))
        return base64url(signer.sign())
    }

    private fun base64url(bytes: ByteArray): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)

    private fun base64url(s: String): String = base64url(s.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val IAM_TOKENS_URL = "https://iam.api.cloud.yandex.net/iam/v1/tokens"
        const val PAYLOAD_URL = "https://payload.lockbox.api.cloud.yandex.net/lockbox/v1"
        const val METADATA_TOKEN_URL =
            "http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token"
    }
}
